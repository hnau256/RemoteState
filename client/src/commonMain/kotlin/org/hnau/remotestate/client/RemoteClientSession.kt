package org.hnau.remotestate.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.CallError
import org.hnau.remotestate.CallId
import org.hnau.remotestate.FunctionId
import org.hnau.remotestate.IdFactory
import org.hnau.remotestate.RemoteClientMessage
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionDescriptor
import org.hnau.remotestate.RemoteServerMessage
import org.hnau.remotestate.RemoteStateIdElement
import org.hnau.remotestate.RemoteStateValueElement
import org.hnau.remotestate.StateId
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.remoteStateDescriptor

class RemoteCallException(
    val error: CallError,
) : Exception("Remote call failed: $error")

class RemoteConnectionClosedException : Exception("Connection closed")

internal class RemoteClientSession<T>(
    private val rootSerializer: KSerializer<T>,
    private val send: (RemoteClientMessage) -> Unit,
) {


    private class Owner {

        val stateIds: MutableList<StateId> = mutableListOf()
    }

    private interface Entry {

        fun update(
            element: JsonElement,
        )

        fun dispose()
    }

    private inner class StateEntry<S>(
        private val valueSerializer: KSerializer<S>,
        private val flow: MutableStateFlow<S>,
        private var children: Owner,
    ) : Entry {

        override fun update(
            element: JsonElement,
        ) {
            val newChildren = Owner()
            flow.value = decode(newChildren, valueSerializer, element)
            val oldChildren = children
            children = newChildren
            dispose(oldChildren)
        }

        override fun dispose() {
            dispose(children)
        }
    }

    private inner class RootEntry : Entry {

        override fun update(
            element: JsonElement,
        ) {
            check(root.complete(decode(Owner(), rootSerializer, element))) {
                "Root is already received"
            }
        }

        override fun dispose() {}
    }

    private var currentOwner: Owner? = null

    private val callsMutex = Mutex()

    private val pendingCalls: MutableMap<CallId, CompletableDeferred<JsonElement>> = HashMap()

    private var closedCause: Throwable? = null

    private val callIdFactory: IdFactory<CallId> = CallId.createFactory()

    private val json: Json = Json(RemoteJson) {
        serializersModule = SerializersModule {
            contextual(RemoteState::class) { typeArguments ->
                StateSerializer(typeArguments[0])
            }
            contextual(RemoteFunction::class) { typeArguments ->
                FunctionSerializer(typeArguments[0], typeArguments[1])
            }
        }
    }

    val root: CompletableDeferred<T> = CompletableDeferred()

    private val states: MutableMap<StateId, Entry> = hashMapOf(StateId.root to RootEntry())

    suspend fun handle(
        message: RemoteServerMessage,
    ) {
        when (message) {
            is RemoteServerMessage.Update -> states[message.stateId]?.update(message.value)

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
        function: FunctionId,
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
                RemoteClientMessage.Call(
                    callId = callId,
                    function = function,
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

    private fun <S> decode(
        owner: Owner,
        serializer: KSerializer<S>,
        element: JsonElement,
    ): S = withOwner(owner) {
        json.decodeFromJsonElement(serializer, element)
    }

    private inline fun <R> withOwner(
        owner: Owner,
        block: () -> R,
    ): R {
        val previous = currentOwner
        currentOwner = owner
        try {
            return block()
        } finally {
            currentOwner = previous
        }
    }

    private fun owner(): Owner = currentOwner ?: error("Remote value is decoded outside of session")

    private fun dispose(
        owner: Owner,
    ) {
        owner.stateIds.forEach { id ->
            states.remove(id)?.dispose()
        }
    }

    private inner class StateSerializer<S>(
        private val valueSerializer: KSerializer<S>,
    ) : KSerializer<RemoteState<S>> {

        override val descriptor: SerialDescriptor = remoteStateDescriptor(valueSerializer.descriptor)

        private val idIndex: Int = descriptor.getElementIndex(RemoteStateIdElement)

        private val valueIndex: Int = descriptor.getElementIndex(RemoteStateValueElement)

        override fun serialize(
            encoder: Encoder,
            value: RemoteState<S>,
        ) {
            error("Client does not encode remote states")
        }

        override fun deserialize(
            decoder: Decoder,
        ): RemoteState<S> {
            val owner = owner()
            val children = Owner()
            var id: StateId? = null
            var value: Result<S>? = null
            decoder.decodeStructure(descriptor) {
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        idIndex -> id = decodeSerializableElement(descriptor, idIndex, StateId.serializer())
                        valueIndex -> value = Result.success(
                            withOwner(children) {
                                decodeSerializableElement(descriptor, valueIndex, valueSerializer)
                            }
                        )

                        else -> error("Unexpected RemoteState element index: $index")
                    }
                }
            }
            val stateId = id ?: error("RemoteState has no id")
            val initialValue: Result<S> = value ?: error("RemoteState '$stateId' has no value")
            val flow = MutableStateFlow(initialValue.getOrThrow())
            states[stateId] = StateEntry(
                valueSerializer = valueSerializer,
                flow = flow,
                children = children,
            )
            owner.stateIds += stateId
            return RemoteState(flow)
        }
    }

    private inner class FunctionSerializer<A, R>(
        private val argumentSerializer: KSerializer<A>,
        private val resultSerializer: KSerializer<R>,
    ) : KSerializer<RemoteFunction<A, R>> {

        override val descriptor: SerialDescriptor = RemoteFunctionDescriptor

        override fun serialize(
            encoder: Encoder,
            value: RemoteFunction<A, R>,
        ) {
            error("Client does not encode remote functions")
        }

        override fun deserialize(
            decoder: Decoder,
        ): RemoteFunction<A, R> {
            val id = decoder.decodeSerializableValue(FunctionId.serializer())
            return { argument ->
                val result = call(
                    function = id,
                    argument = json.encodeToJsonElement(argumentSerializer, argument),
                )
                json.decodeFromJsonElement(resultSerializer, result)
            }
        }
    }
}
