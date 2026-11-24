package org.hnau.remotestate.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemoteClientMessage
import org.hnau.remotestate.RemoteRoute
import org.hnau.remotestate.RemoteServerMessage

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Application.remoteStateModule(
    serializer: KSerializer<T>,
    onError: (Throwable) -> Unit = {},
    createRoot: (CoroutineScope) -> T,
) {
    install(WebSockets)
    routing {
        webSocket(RemoteRoute) {
            val messages = Channel<RemoteServerMessage>(Channel.UNLIMITED)
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val sessionScope = CoroutineScope(
                SupervisorJob(coroutineContext[Job]) + dispatcher
            )
            val writer = launch {
                for (message in messages) {
                    outgoing.send(
                        Frame.Text(
                            RemoteJson.encodeToString(
                                serializer = RemoteServerMessage.serializer(),
                                value = message,
                            )
                        )
                    )
                }
            }
            try {
                val session = withContext(dispatcher) {
                    RemoteServerSession(
                        scope = sessionScope,
                        sendMessage = { messages.trySend(it) },
                        onError = onError,
                    ).also { session ->
                        session.start(
                            serializer = serializer,
                            root = createRoot(sessionScope),
                        )
                    }
                }
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val message = catchingResult {
                        RemoteJson.decodeFromString(
                            deserializer = RemoteClientMessage.serializer(),
                            string = text,
                        )
                    }
                        .onFailure(onError)
                        .getOrNull()
                        ?: continue
                    withContext(dispatcher) {
                        session.handle(message)
                    }
                }
            } finally {
                sessionScope.cancel()
                messages.close()
                writer.join()
            }
        }
    }
}
