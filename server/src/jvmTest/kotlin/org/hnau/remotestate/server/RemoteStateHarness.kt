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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

fun <T> withRemoteState(
    serializer: KSerializer<T>,
    createRoot: (scope: CoroutineScope) -> T,
    block: suspend CoroutineScope.(root: T) -> Unit,
): Unit = runBlocking {
    val server = embeddedServer(CIO, port = 0) {
        install(ServerWebSockets)
        routing {
            remoteState(path = "/remote", serializer = serializer, createRoot = createRoot)
        }
    }.start()
    val port = server.engine.resolvedConnectors().first().port
    val client = HttpClient(ClientCIO) { install(WebSockets) }
    val connection = Job(coroutineContext[Job])
    try {
        withTimeout(20.seconds) {
            val connected = client
                .connectRemoteState(
                    scope = CoroutineScope(coroutineContext + connection),
                    url = "ws://localhost:$port/remote",
                    serializer = serializer,
                )
                .filterIsInstance<RemoteConnectionState.Connected<T>>()
                .first()
            connected.scope.async { block(connected.root) }.await()
        }
    } finally {
        connection.cancel()
        client.close()
        server.stop()
    }
}
