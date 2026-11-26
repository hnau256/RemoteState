package org.hnau.remotestate.server

import io.ktor.server.routing.Route
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
import org.hnau.remotestate.RemoteCall
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemoteServerMessage

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Route.remoteState(
    path: String,
    serializer: KSerializer<T>,
    onError: (Throwable) -> Unit = {},
    createRoot: (scope: CoroutineScope) -> T,
) {
    webSocket(path) {
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
                val remoteCall = catchingResult {
                    RemoteJson.decodeFromString(
                        deserializer = RemoteCall.serializer(),
                        string = text,
                    )
                }
                    .onFailure(onError)
                    .getOrNull()
                    ?: continue
                withContext(dispatcher) {
                    session.handle(remoteCall)
                }
            }
        } finally {
            sessionScope.cancel()
            messages.close()
            writer.join()
        }
    }
}
