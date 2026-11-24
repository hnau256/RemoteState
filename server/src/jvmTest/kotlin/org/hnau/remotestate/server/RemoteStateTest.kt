package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hnau.remotestate.client.RemoteCallException
import org.hnau.remotestate.client.RemoteConnectionClosedException
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.invoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

class RemoteStateTest {

    private val login: String = "user"

    private fun password(
        raw: String,
    ): String = raw

    @Test
    fun loginAddRemoveLogout(): Unit = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            remoteStateModule(RootState.serializer()) { scope -> RootModel(scope).state }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        try {
            withTimeout(10.seconds) {
                val connection = Job(coroutineContext[Job])
                val root = client.connectRemoteState(
                    scope = CoroutineScope(coroutineContext + connection),
                    baseUrl = "ws://localhost:$port",
                    serializer = RootState.serializer(),
                )
                try {
                    val anonymous = root.loginState.state.value.leftOrNull()!!
                    assertFalse(anonymous.login(login to password("wrong")))
                    assertTrue(anonymous.login(login to password("password")))

                    val logged = root.loginState.state.first { it.isRight() }.getOrNull()!!
                    assertFailsWith<RemoteCallException> {
                        anonymous.login(login to password("password"))
                    }

                    logged.addTask("first")
                    logged.addTask("second")
                    val tasks = logged.tasks.state.first { it.size == 2 }
                    assertEquals(listOf("first", "second"), tasks.map(TaskState::title))

                    tasks[0].remove()
                    val remaining = logged.tasks.state.first { it.size == 1 }.single()
                    assertEquals("second", remaining.title)
                    assertFailsWith<RemoteCallException> { tasks[1].remove() }
                    remaining.remove()
                    logged.tasks.state.first { it.isEmpty() }

                    logged.logout()
                    root.loginState.state.first { it.isLeft() }
                    assertFailsWith<RemoteCallException> { logged.addTask("third") }

                    connection.cancel()
                    connection.join()
                    assertFailsWith<RemoteConnectionClosedException> {
                        root.loginState.state.value.leftOrNull()!!.login(login to password("password"))
                    }
                } finally {
                    connection.cancel()
                }
            }
        } finally {
            client.close()
            server.stop()
        }
    }
}
