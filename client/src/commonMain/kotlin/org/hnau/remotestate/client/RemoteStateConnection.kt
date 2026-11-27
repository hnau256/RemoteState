package org.hnau.remotestate.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.common.RemoteCall
import org.hnau.remotestate.common.RemoteJson
import org.hnau.remotestate.common.RemoteServerMessage
import kotlin.time.Clock
import kotlin.time.Duration

fun <T> HttpClient.connectRemoteState(
    scope: CoroutineScope,
    url: String,
    serializer: KSerializer<T>,
    reconnectDelay: (failedAttempts: Int) -> Duration = exponentialReconnectDelay(),
): StateFlow<RemoteConnectionState<T>> {
    val connectionState = MutableStateFlow<RemoteConnectionState<T>>(RemoteConnectionState.Connecting)
    scope.launch {
        var failedAttempts = 0
        while (true) {
            connectionState.value = RemoteConnectionState.Connecting
            val cause = runConnection(
                scope = this,
                url = url,
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
    scope: CoroutineScope,
    url: String,
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
    val messages = Channel<RemoteCall>(Channel.UNLIMITED)
    val connectionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext.job))
    val session = RemoteClientSession(
        scope = connectionScope,
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
                                serializer = RemoteCall.serializer(),
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
            connectionScope.cancel()
            session.close(cause)
            socket.close()
        }
    }
    return cause
}
