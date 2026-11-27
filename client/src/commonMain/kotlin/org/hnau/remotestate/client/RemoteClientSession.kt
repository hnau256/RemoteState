@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)

package org.hnau.remotestate.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.hnau.remotestate.common.CallId
import org.hnau.remotestate.common.RemoteCall
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteServerMessage
import org.hnau.remotestate.common.fold
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

class RemoteConnectionClosedException : Exception("Connection closed")

internal class RemoteClientSession<T>(
    private val scope: CoroutineScope,
    private val rootSerializer: KSerializer<T>,
    private val send: (RemoteCall) -> Unit,
) {

    private val callsMutex = Mutex()

    private val pendingCalls: MutableMap<CallId, CompletableDeferred<JsonElement>> = HashMap()

    private val lastCallId: AtomicLong = AtomicLong(0)

    val root: CompletableDeferred<T> = CompletableDeferred()

    private var rootUpdates: SendChannel<RemoteUpdate>? = null

    suspend fun handle(
        message: RemoteServerMessage,
    ) {
        message.fold(
            ifUpdate = { path, value ->
                rootUpdates
                    ?.trySend(RemoteUpdate(path, value))
                    ?: run {
                        check(path == RemotePath.root) { "Expected root, got update for $path" }
                        createRoot(value)
                    }
            },
            ifRemove = { path ->
                val updates = rootUpdates ?: error("Remove of $path was received before root")
                updates.trySend(RemoteUpdate(path, null))
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

    private fun createRoot(
        value: JsonElement,
    ) {
        val updates = Channel<RemoteUpdate>(Channel.UNLIMITED)
        rootUpdates = updates
        val state = receiveRemoteState(
            scope = scope,
            serializer = rootSerializer,
            initial = value,
            updates = updates,
            call = ::call,
        )
        root.complete(state.value)
    }

    fun close(
        cause: Throwable,
    ) {
        root.completeExceptionally(cause)
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
