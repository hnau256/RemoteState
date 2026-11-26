package org.hnau.remotestate.server

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import org.hnau.remotestate.common.CallError
import org.hnau.remotestate.common.RemoteCall
import org.hnau.remotestate.common.RemoteServerMessage
import org.hnau.remotestate.common.SnapshotRemoteState
import org.hnau.remotestate.common.encodeRemoteValue

internal class RemoteServerSession private constructor(
    private val scope: CoroutineScope,
    private val sendMessage: (RemoteServerMessage) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val root: RemoteServerNode<*, *>,
) {

    fun handle(
        call: RemoteCall,
    ) {
        val function = root.resolve(call.path) ?: run {
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
            val encoded = encodeRemoteValue(serializer, root)
            sendMessage(RemoteServerMessage.Init(encoded.element))
            val node = RemoteServerNode(
                scope = scope,
                patcher = SnapshotRemoteState.Patcher(serializer),
                state = MutableStateFlow(root),
                initial = root,
                known = encoded.states,
                send = { path, patch -> sendMessage(RemoteServerMessage.Patch(path = path, patch = patch)) },
            )
            return RemoteServerSession(
                scope = scope,
                sendMessage = sendMessage,
                onError = onError,
                root = node,
            )
        }
    }
}
