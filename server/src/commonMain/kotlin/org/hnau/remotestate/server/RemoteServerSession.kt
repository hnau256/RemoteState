package org.hnau.remotestate.server

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.hnau.remotestate.CallError
import org.hnau.remotestate.RemoteCall
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import org.hnau.remotestate.RemoteServerMessage
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.RemoteStateSerializer

internal class RemoteServerSession(
    private val scope: CoroutineScope,
    private val sendMessage: (RemoteServerMessage) -> Unit,
    private val onError: (Throwable) -> Unit,
) : RemoteTreeEncodeContext {

    private val functions: MutableMap<RemotePath, suspend (JsonElement) -> JsonElement> = HashMap()

    private val subscriptions: MutableMap<RemotePath, Job> = HashMap()

    fun <T> start(
        serializer: KSerializer<T>,
        root: T,
    ) {
        sendMessage(
            RemoteServerMessage.Update(
                path = RemotePath.root,
                value = encodeTree(RemotePath.root, serializer, root),
            )
        )
    }

    fun handle(
        call: RemoteCall,
    ) {
        val function = functions[call.path] ?: run {
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

    private fun disposeInside(
        path: RemotePath,
    ) {
        functions.keys.removeAll { it.isStrictlyInside(path) }
        subscriptions.keys
            .filter { it.isStrictlyInside(path) }
            .forEach { subscriptions.remove(it)?.cancel() }
    }

    override fun <T> encodeState(
        path: RemotePath,
        serializer: RemoteStateSerializer<T>,
        state: RemoteState<T>,
    ): JsonElement {
        val initialValue = state.state.value
        val element = encodeTree(path, serializer.valueSerializer, initialValue)
        var lastValue = initialValue
        subscriptions.put(
            path,
            scope.launch {
                state.state.collect { newValue ->
                    if (newValue == lastValue) {
                        return@collect
                    }
                    lastValue = newValue
                    disposeInside(path)
                    sendMessage(
                        RemoteServerMessage.Update(
                            path = path,
                            value = encodeTree(path, serializer.valueSerializer, newValue),
                        )
                    )
                }
            },
        )?.cancel()
        return element
    }

    override fun <A, R> encodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<A, R>,
        function: RemoteFunction<A, R>,
    ): JsonElement {
        functions[path] = { argument ->
            RemoteJson.encodeToJsonElement(
                serializer = serializer.resultSerializer,
                value = function(RemoteJson.decodeFromJsonElement(serializer.argumentSerializer, argument)),
            )
        }
        return JsonObject(emptyMap())
    }
}
