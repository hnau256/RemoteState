package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.hnau.remotestate.client.ConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.invoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class ConnectionTest {

    @Serializable
    data class Root(
        val ping: RemoteFunction<Unit, String>,
        val hang: RemoteFunction<Unit, Unit>,
    )

    @Test
    fun pendingCallsAreCancelledOnDisconnect(): Unit = runBlocking {
        val hangStarted = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                remoteState(path = "/remote", serializer = Root.serializer()) {
                    Root(
                        ping = { "pong" },
                        hang = {
                            hangStarted.complete(Unit)
                            awaitCancellation()
                        },
                    )
                }
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
                assertEquals(ConnectionState.Connecting, state.value)
                val connected = state.filterIsInstance<ConnectionState.Connected<Root>>().first()
                val root = connected.root

                assertEquals("pong", root.ping())

                val hanging = async { root.hang() }
                hangStarted.await()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
                state.first { it is ConnectionState.Disconnected }
                hanging.join()
                assertTrue(hanging.isCancelled)
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }

    @Test
    fun reconnectNowSkipsDelay(): Unit = runBlocking {
        val createServer = { port: Int ->
            embeddedServer(CIO, port = port) {
                install(ServerWebSockets)
                routing {
                    remoteState(path = "/remote", serializer = Root.serializer()) {
                        Root(
                            ping = { "pong" },
                            hang = { awaitCancellation() },
                        )
                    }
                }
            }
        }
        val probe = createServer(0).start()
        val port = probe.engine.resolvedConnectors().first().port
        probe.stop()
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        val connection = Job(coroutineContext[Job])
        val server = createServer(port)
        try {
            withTimeout(10.seconds) {
                val state = client.connectRemoteState(
                    scope = CoroutineScope(coroutineContext + connection),
                    url = "ws://localhost:$port/remote",
                    serializer = Root.serializer(),
                    reconnectDelay = { 1.hours },
                )
                val disconnected = state.filterIsInstance<ConnectionState.Disconnected>().first()
                server.start()
                disconnected.reconnectNow()
                val connected = state.filterIsInstance<ConnectionState.Connected<Root>>().first()
                assertEquals("pong", connected.root.ping())
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }
}
