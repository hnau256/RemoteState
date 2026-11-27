package org.hnau.remotestate.server

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.common.CallError
import org.hnau.remotestate.common.RemoteCall
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteServerMessage

internal class RemoteServerSession private constructor(
    private val scope: CoroutineScope,
    private val sendMessage: (RemoteServerMessage) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val root: RemoteHandler,
) {

    fun handle(
        call: RemoteCall,
    ) {
        val function = root(call.path) ?: run {
            sendMessage(
                RemoteServerMessage.CallResult(
                    callId = call.callId,
                    result = CallError.Outdated.left(),
                )
            )
            return
        }
        scope.launch {
            val result = catchingResult {
                function(call.argument)
            }.fold(
                onSuccess = { it.right() },
                onFailure = { error ->
                    onError(error)
                    CallError.Internal.left()
                },
            )
            sendMessage(
                RemoteServerMessage.CallResult(
                    callId = call.callId,
                    result = result,
                )
            )
        }
    }

    companion object {

        fun <T> start(
            scope: CoroutineScope,
            sendMessage: (RemoteServerMessage) -> Unit,
            onError: (Throwable) -> Unit,
            serializer: KSerializer<T>,
            root: T,
        ): RemoteServerSession {
            val served = serveRemoteState(
                scope = scope,
                serializer = serializer,
                state = MutableStateFlow(root),
                send = { path, value ->
                    sendMessage(
                        value
                            ?.let { RemoteServerMessage.Update(path = path, value = it) }
                            ?: RemoteServerMessage.Remove(path = path)
                    )
                },
            )
            sendMessage(RemoteServerMessage.Update(path = RemotePath.root, value = served.initial))
            return RemoteServerSession(
                scope = scope,
                sendMessage = sendMessage,
                onError = onError,
                root = served.handler,
            )
        }
    }
}
