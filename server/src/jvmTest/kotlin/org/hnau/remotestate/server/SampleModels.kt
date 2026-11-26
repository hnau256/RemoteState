package org.hnau.remotestate.server

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.commons.kotlin.coroutines.flow.state.mapWithScope
import org.hnau.commons.kotlin.coroutines.flow.state.mutable.toMutableStateFlowAsInitial
import org.hnau.commons.kotlin.foldBoolean
import org.hnau.commons.kotlin.KeyValue
import org.hnau.remotestate.common.toKeyedListRemoteState
import org.hnau.remotestate.common.toSnapshotRemoteState

class TaskModel(
    title: String,
    remove: suspend () -> Unit,
) {

    val state: TaskState = TaskState(
        title = title,
        remove = { remove() },
    )
}

class LoggedModel(
    scope: CoroutineScope,
    logout: suspend () -> Unit,
) {

    private val tasksTitles: MutableStateFlow<List<String>> =
        emptyList<String>().toMutableStateFlowAsInitial()

    val state: LoggedState = LoggedState(
        tasks = tasksTitles
            .mapListReusable(
                scope = scope,
                extractKey = { it },
            ) { _, title ->
                TaskModel(
                    title = title,
                    remove = { tasksTitles.update { titles -> titles.filter { it != title } } },
                ).state
            }
            .mapState(scope) { tasks -> tasks.map { task -> KeyValue(task.title, task) } }
            .toKeyedListRemoteState(),
        addTask = { title -> tasksTitles.update { it + title } },
        logout = { logout() },
    )
}

class AnonymousModel(
    login: suspend (login: String, password: String) -> Boolean,
) {

    val state: AnonymousState = AnonymousState(
        login = { (userLogin, password) -> login(userLogin, password) },
    )
}

data class RootSkeleton(
    var logged: Boolean = false,
)

class RootModel(
    scope: CoroutineScope,
    private val skeleton: RootSkeleton,
) {

    private val logged: MutableStateFlow<Boolean> = skeleton.logged.toMutableStateFlowAsInitial()

    private fun setLogged(
        newLogged: Boolean,
    ) {
        skeleton.logged = newLogged
        logged.value = newLogged
    }

    val state: RootState = RootState(
        loginState = logged
            .mapWithScope(scope) { scope, nowIsLogged ->
                nowIsLogged.foldBoolean(
                    ifTrue = {
                        LoggedModel(
                            scope = scope,
                            logout = { setLogged(false) },
                        ).state.right()
                    },
                    ifFalse = {
                        AnonymousModel(
                            login = { _, password ->
                                (password == "password").also(::setLogged)
                            },
                        ).state.left()
                    },
                )
            }
            .toSnapshotRemoteState(),
    )
}
