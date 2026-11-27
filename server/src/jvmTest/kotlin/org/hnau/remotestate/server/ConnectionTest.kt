package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.hnau.remotestate.client.RemoteConnectionClosedException
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.invoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

class ConnectionTest {

    @Serializable
    data class Root(
        val ping: RemoteFunction<Unit, String>,
    )

    @Test
    fun callsFailAfterDisconnection(): Unit = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                remoteState(path = "/remote", serializer = Root.serializer()) { Root(ping = { "pong" }) }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        val connection = Job(coroutineContext[Job])
        try {
            withTimeout(10.seconds) {
                val state = client.connectRemoteState(
                    scope = CoroutineScope(coroutineContext + connection),
                    url = "ws://localhost:$port/remote",
                    serializer = Root.serializer(),
                )
                assertEquals(RemoteConnectionState.Connecting, state.value)
                val root = state.filterIsInstance<RemoteConnectionState.Connected<Root>>().first().root
                assertEquals("pong", root.ping())

                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
                state.first { it is RemoteConnectionState.Disconnected }
                assertFailsWith<RemoteConnectionClosedException> { root.ping() }
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }
}
