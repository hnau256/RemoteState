package org.hnau.remotestate.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.RemoteClientMessage
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemoteRoute
import org.hnau.remotestate.RemoteServerMessage

suspend fun <T> HttpClient.connectRemoteState(
    scope: CoroutineScope,
    baseUrl: String,
    serializer: KSerializer<T>,
): T {
    val socket = webSocketSession(urlString = baseUrl.trimEnd('/') + RemoteRoute)
    val messages = Channel<RemoteClientMessage>(Channel.UNLIMITED)
    val session = RemoteClientSession(
        rootSerializer = serializer,
        send = { messages.trySend(it) },
    )
    val connection = scope.launch {
        var cause: Throwable = RemoteConnectionClosedException()
        try {
            coroutineScope {
                val writer = launch {
                    for (message in messages) {
                        socket.outgoing.send(
                            Frame.Text(
                                RemoteJson.encodeToString(
                                    serializer = RemoteClientMessage.serializer(),
                                    value = message,
                                )
                            )
                        )
                    }
                }
                for (frame in socket.incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    session.handle(
                        RemoteJson.decodeFromString(
                            deserializer = RemoteServerMessage.serializer(),
                            string = text,
                        )
                    )
                }
                writer.cancel()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            cause = error
        } finally {
            withContext(NonCancellable) {
                messages.close()
                session.close(cause)
                socket.close()
            }
        }
    }
    try {
        return session.root.await()
    } catch (error: Throwable) {
        connection.cancel()
        throw error
    }
}
