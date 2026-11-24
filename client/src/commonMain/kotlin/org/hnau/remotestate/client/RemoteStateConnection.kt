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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.RemoteClientMessage
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemoteRoute
import org.hnau.remotestate.RemoteServerMessage
import org.hnau.remotestate.SessionId
import kotlin.time.Clock
import kotlin.time.Duration

fun <T> HttpClient.connectRemoteState(
    scope: CoroutineScope,
    baseUrl: String,
    sessionId: SessionId,
    serializer: KSerializer<T>,
    reconnectDelay: (failedAttempts: Int) -> Duration = exponentialReconnectDelay(),
): StateFlow<RemoteConnectionState<T>> {
    val connectionState = MutableStateFlow<RemoteConnectionState<T>>(RemoteConnectionState.Connecting)
    scope.launch {
        var failedAttempts = 0
        while (true) {
            connectionState.value = RemoteConnectionState.Connecting
            val cause = runConnection(
                url = baseUrl.trimEnd('/') + RemoteRoute,
                sessionId = sessionId,
                serializer = serializer,
                onRoot = { root ->
                    failedAttempts = 0
                    connectionState.value = RemoteConnectionState.Connected(root)
                },
            )
            failedAttempts++
            val delay = reconnectDelay(failedAttempts)
            connectionState.value = RemoteConnectionState.Disconnected(
                cause = cause,
                nextAttemptAt = Clock.System.now() + delay,
            )
            delay(delay)
        }
    }
    return connectionState.asStateFlow()
}

private suspend fun <T> HttpClient.runConnection(
    url: String,
    sessionId: SessionId,
    serializer: KSerializer<T>,
    onRoot: (T) -> Unit,
): Throwable {
    val socket = try {
        webSocketSession(urlString = url)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        return error
    }
    val messages = Channel<RemoteClientMessage>(Channel.UNLIMITED)
    messages.trySend(RemoteClientMessage.Hello(sessionId))
    val session = RemoteClientSession(
        rootSerializer = serializer,
        send = { messages.trySend(it) },
    )
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
            val rootWaiter = launch {
                onRoot(session.root.await())
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
            rootWaiter.cancel()
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
    return cause
}
