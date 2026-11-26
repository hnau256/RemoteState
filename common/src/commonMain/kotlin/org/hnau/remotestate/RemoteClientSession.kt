@file:OptIn(ExperimentalAtomicApi::class)

package org.hnau.remotestate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

class RemoteClientSession<T>(
    private val scope: CoroutineScope,
    private val rootSerializer: KSerializer<T>,
    private val send: (RemoteCall) -> Unit,
) {

    private val callsMutex = Mutex()

    private val pendingCalls: MutableMap<CallId, CompletableDeferred<JsonElement>> = HashMap()

    private var closedCause: Throwable? = null

    private val lastCallId: AtomicLong = AtomicLong(0)

    val root: CompletableDeferred<T> = CompletableDeferred()

    private var rootNode: RemoteClientNode<T, T>? = null

    suspend fun handle(
        message: RemoteServerMessage,
    ) {
        message.fold(
            ifInit = { value ->
                check(rootNode == null) { "Root was already received" }
                val node = RemoteClientNode(
                    scope = scope,
                    patcher = SnapshotRemoteState.Patcher(rootSerializer),
                    initial = value,
                    call = ::call,
                )
                rootNode = node
                root.complete(node.state.value)
            },
            ifPatch = { path, patch ->
                val node = rootNode ?: error("Patch for $path was received before root")
                node.updates.trySend(path to patch)
            },
            ifCallResult = { callId, result ->
                completeCall(callId) {
                    result.fold(
                        ifLeft = { error -> completeExceptionally(RemoteCallException(error)) },
                        ifRight = { value -> complete(value) },
                    )
                }
            },
        )
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
        val callId = CallId(lastCallId.incrementAndFetch())
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
}
