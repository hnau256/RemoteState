package org.hnau.remotestate.server

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.serialization.EitherSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.commons.kotlin.coroutines.flow.state.mapWithScope
import org.hnau.commons.kotlin.foldBoolean
import org.hnau.remotestate.client.invokeCatching
import org.hnau.remotestate.common.CallError
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteMap
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.invoke
import org.hnau.remotestate.common.toRemoteMap
import org.hnau.remotestate.common.toRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginScenarioTest {

    @Serializable
    data class TaskState(
        val title: String,
        val remove: RemoteFunction<Unit, Unit>,
    )

    @Serializable
    data class LoggedState(
        val tasks: RemoteMap<String, TaskState>,
        val addTask: RemoteFunction<String, Unit>,
        val logout: RemoteFunction<Unit, Unit>,
    )

    @Serializable
    data class AnonymousState(
        val login: RemoteFunction<String, Boolean>,
    )

    @Serializable
    data class RootState(
        val loginState: RemoteState<@Serializable(with = EitherSerializer::class) Either<AnonymousState, LoggedState>>,
    )

    private class LoggedModel(
        scope: CoroutineScope,
        logout: () -> Unit,
    ) {

        private val titles: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

        val state: LoggedState = LoggedState(
            tasks = titles
                .mapListReusable(
                    scope = scope,
                    extractKey = { it },
                ) { _, title ->
                    TaskState(
                        title = title,
                        remove = { titles.update { it - title } },
                    )
                }
                .mapState(scope) { tasks -> tasks.associateBy(TaskState::title) }
                .toRemoteMap(),
            addTask = { title -> titles.update { it + title } },
            logout = { logout() },
        )
    }

    private class RootModel(
        scope: CoroutineScope,
    ) {

        private val logged: MutableStateFlow<Boolean> = MutableStateFlow(false)

        val state: RootState = RootState(
            loginState = logged
                .mapWithScope(scope) { loggedScope, isLogged ->
                    isLogged.foldBoolean(
                        ifTrue = {
                            LoggedModel(
                                scope = loggedScope,
                                logout = { logged.value = false },
                            ).state.right()
                        },
                        ifFalse = {
                            AnonymousState(
                                login = { password -> (password == "password").also { logged.value = it } },
                            ).left()
                        },
                    )
                }
                .toRemoteState(),
        )
    }

    @Test
    fun loginAddRemoveLogout() = withRemoteState(
        serializer = RootState.serializer(),
        createRoot = { scope -> RootModel(scope).state },
    ) { root ->
        val anonymous = root.loginState.state.value.leftOrNull()!!
        assertFalse(anonymous.login("wrong"))
        assertTrue(anonymous.login("password"))

        val logged = root.loginState.state.first { it.isRight() }.getOrNull()!!
        assertEquals(CallError.Outdated.left(), anonymous.login.invokeCatching("password"))

        logged.addTask("first")
        logged.addTask("second")
        val tasks = logged.tasks.state.first { it.size == 2 }
        assertEquals(listOf("first", "second"), tasks.values.map(TaskState::title))

        val first = tasks.getValue("first")
        first.remove()
        val remaining = logged.tasks.state.first { it.size == 1 }.values.single()
        assertEquals("second", remaining.title)
        assertEquals(CallError.Outdated.left(), first.remove.invokeCatching())

        logged.logout()
        root.loginState.state.first { it.isLeft() }
        assertEquals(CallError.Outdated.left(), logged.addTask.invokeCatching("third"))
    }
}
