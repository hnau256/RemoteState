package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hnau.remotestate.client.RemoteCallException
import org.hnau.remotestate.client.RemoteConnectionClosedException
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.common.invoke
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

    private fun startServer(
        port: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> = embeddedServer(CIO, port = port) {
        install(ServerWebSockets)
        routing {
            remoteState(path = "/remote", serializer = RootState.serializer()) { scope ->
                RootModel(
                    scope = scope,
                    skeleton = RootSkeleton(),
                ).state
            }
        }
    }.start()

    private suspend fun StateFlow<RemoteConnectionState<RootState>>.awaitRoot(): RootState =
        filterIsInstance<RemoteConnectionState.Connected<RootState>>().first().root

    @Test
    fun loginAddRemoveLogout(): Unit = runBlocking {
        val server = startServer(port = 0)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        try {
            withTimeout(10.seconds) {
                val connection = Job(coroutineContext[Job])
                val connectionState = client.connectRemoteState(
                    scope = CoroutineScope(coroutineContext + connection),
                    url = "ws://localhost:$port/remote",
                    serializer = RootState.serializer(),
                )
                assertEquals(RemoteConnectionState.Connecting, connectionState.value)
                val root = connectionState.awaitRoot()
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
                    assertEquals(listOf("first", "second"), tasks.values.map(TaskState::title))

                    val first = tasks.getValue("first")
                    first.remove()
                    val remaining = logged.tasks.state.first { it.size == 1 }.values.single()
                    assertEquals("second", remaining.title)
                    assertFailsWith<RemoteCallException> { first.remove() }
                    remaining.remove()
                    logged.tasks.state.first { it.isEmpty() }

                    logged.logout()
                    root.loginState.state.first { it.isLeft() }
                    assertFailsWith<RemoteCallException> { logged.addTask("third") }

                    server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
                    connectionState.first { it is RemoteConnectionState.Disconnected }
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
