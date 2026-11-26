package org.hnau.remotestate.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.hnau.remotestate.CallError
import org.hnau.remotestate.CallId
import org.hnau.remotestate.IdFactory
import org.hnau.remotestate.RemoteCall
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import org.hnau.remotestate.RemoteServerMessage
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.RemoteStateSerializer

class RemoteCallException(
    val error: CallError,
) : Exception("Remote call failed: $error")

class RemoteConnectionClosedException : Exception("Connection closed")

internal class RemoteClientSession<T>(
    private val rootSerializer: KSerializer<T>,
    private val send: (RemoteCall) -> Unit,
) : RemoteTreeDecodeContext {

    private val callsMutex = Mutex()

    private val pendingCalls: MutableMap<CallId, CompletableDeferred<JsonElement>> = HashMap()

    private var closedCause: Throwable? = null

    private val callIdFactory: IdFactory<CallId> = CallId.createFactory()

    val root: CompletableDeferred<T> = CompletableDeferred()

    private val states: MutableMap<RemotePath, (JsonElement) -> Unit> = hashMapOf(
        RemotePath.root to { element ->
            check(root.complete(decodeTree(RemotePath.root, rootSerializer, element))) {
                "Root is already received"
            }
        }
    )

    suspend fun handle(
        message: RemoteServerMessage,
    ) {
        when (message) {
            is RemoteServerMessage.Update -> states[message.path]?.let { update ->
                disposeInside(message.path)
                update(message.value)
            }

            is RemoteServerMessage.CallResult -> completeCall(message.callId) {
                message.result.fold(
                    ifLeft = { error -> completeExceptionally(RemoteCallException(error)) },
                    ifRight = { value -> complete(value) },
                )
            }
        }
    }

    suspend fun close(
        cause: Throwable,
    ) {
        root.completeExceptionally(cause)
        val calls = callsMutex.withLock {
            if (closedCause == null) {
                closedCause = cause
            }
            pendingCalls.values.toList().also { pendingCalls.clear() }
        }
        calls.forEach { it.completeExceptionally(cause) }
    }

    private suspend fun completeCall(
        callId: CallId,
        block: CompletableDeferred<JsonElement>.() -> Unit,
    ) {
        callsMutex
            .withLock { pendingCalls[callId] }
            ?.block()
    }

    private suspend fun call(
        path: RemotePath,
        argument: JsonElement,
    ): JsonElement {
        val callId = callIdFactory.next()
        val deferred = CompletableDeferred<JsonElement>()
        callsMutex.withLock {
            closedCause?.let { throw it }
            pendingCalls[callId] = deferred
        }
        try {
            send(
                RemoteCall(
                    callId = callId,
                    path = path,
                    argument = argument,
                )
            )
            return deferred.await()
        } finally {
            callsMutex.withLock {
                pendingCalls.remove(callId)
            }
        }
    }

    private fun disposeInside(
        path: RemotePath,
    ) {
        states.keys.removeAll { it.isStrictlyInside(path) }
    }

    override fun <S> decodeState(
        path: RemotePath,
        serializer: RemoteStateSerializer<S>,
        element: JsonElement,
    ): RemoteState<S> {
        val flow = MutableStateFlow(decodeTree(path, serializer.valueSerializer, element))
        states[path] = { newElement ->
            flow.value = decodeTree(path, serializer.valueSerializer, newElement)
        }
        return RemoteState(flow)
    }

    override fun <A, R> decodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<A, R>,
    ): RemoteFunction<A, R> = RemoteFunction { argument ->
        RemoteJson.decodeFromJsonElement(
            deserializer = serializer.resultSerializer,
            element = call(
                path = path,
                argument = RemoteJson.encodeToJsonElement(serializer.argumentSerializer, argument),
            ),
        )
    }
}
