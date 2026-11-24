package org.hnau.remotestate.server

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import org.hnau.remotestate.CallError
import org.hnau.remotestate.FunctionId
import org.hnau.remotestate.RemoteClientMessage
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionDescriptor
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemoteServerMessage
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.remoteStateDescriptor
import org.hnau.remotestate.RemoteStateIdElement
import org.hnau.remotestate.RemoteStateValueElement
import org.hnau.remotestate.StateId

internal class RemoteServerSession(
    private val scope: CoroutineScope,
    private val sendMessage: (RemoteServerMessage) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private class Owner {

        val functionIds: MutableList<FunctionId> = mutableListOf()

        val subscriptions: MutableList<Subscription> = mutableListOf()
    }

    private class Subscription(
        val id: StateId,
        var children: Owner,
    ) {

        var job: Job? = null

        var disposed: Boolean = false
    }

    private val functions: MutableMap<FunctionId, suspend (JsonElement) -> JsonElement> = HashMap()

    private var currentOwner: Owner? = null

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

    fun <T> start(
        serializer: KSerializer<T>,
        root: T,
    ) {
        sendMessage(
            RemoteServerMessage.Update(
                stateId = StateId.root,
                value = encode(Owner(), serializer, root),
            )
        )
    }

    fun handle(
        message: RemoteClientMessage,
    ) {
        when (message) {
            is RemoteClientMessage.Call -> handleCall(message)
        }
    }

    private fun handleCall(
        call: RemoteClientMessage.Call,
    ) {
        val function = functions[call.function] ?: run {
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

    private fun <T> encode(
        owner: Owner,
        serializer: KSerializer<T>,
        value: T,
    ): JsonElement = withOwner(owner) {
        json.encodeToJsonElement(serializer, value)
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

    private fun owner(): Owner = currentOwner ?: error("Remote value is encoded outside of session")

    private fun dispose(
        owner: Owner,
    ) {
        owner.functionIds.forEach(functions::remove)
        owner.subscriptions.forEach { subscription ->
            subscription.disposed = true
            subscription.job?.cancel()
            dispose(subscription.children)
        }
    }

    private fun <T> subscribe(
        state: RemoteState<T>,
        valueSerializer: KSerializer<T>,
        initialValue: T,
    ): Subscription {
        val subscription = Subscription(
            id = StateId.new(),
            children = Owner(),
        )
        owner().subscriptions += subscription
        var lastValue = initialValue
        subscription.job = scope.launch {
            state.state.collect { newValue ->
                if (subscription.disposed || newValue == lastValue) {
                    return@collect
                }
                lastValue = newValue
                val newChildren = Owner()
                sendMessage(
                    RemoteServerMessage.Update(
                        stateId = subscription.id,
                        value = encode(newChildren, valueSerializer, newValue),
                    )
                )
                val oldChildren = subscription.children
                subscription.children = newChildren
                dispose(oldChildren)
            }
        }
        return subscription
    }

    private inner class StateSerializer<T>(
        private val valueSerializer: KSerializer<T>,
    ) : KSerializer<RemoteState<T>> {

        override val descriptor: SerialDescriptor = remoteStateDescriptor(valueSerializer.descriptor)

        private val idIndex: Int = descriptor.getElementIndex(RemoteStateIdElement)

        private val valueIndex: Int = descriptor.getElementIndex(RemoteStateValueElement)

        override fun serialize(
            encoder: Encoder,
            value: RemoteState<T>,
        ) {
            val current = value.state.value
            val subscription = subscribe(value, valueSerializer, current)
            encoder.encodeStructure(descriptor) {
                encodeSerializableElement(descriptor, idIndex, StateId.serializer(), subscription.id)
                withOwner(subscription.children) {
                    encodeSerializableElement(descriptor, valueIndex, valueSerializer, current)
                }
            }
        }

        override fun deserialize(
            decoder: Decoder,
        ): RemoteState<T> = error("Server does not decode remote states")
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
            val id = FunctionId.new()
            functions[id] = { argument ->
                json.encodeToJsonElement(
                    serializer = resultSerializer,
                    value = value(json.decodeFromJsonElement(argumentSerializer, argument)),
                )
            }
            owner().functionIds += id
            encoder.encodeSerializableValue(FunctionId.serializer(), id)
        }

        override fun deserialize(
            decoder: Decoder,
        ): RemoteFunction<A, R> = error("Server does not decode remote functions")
    }
}
