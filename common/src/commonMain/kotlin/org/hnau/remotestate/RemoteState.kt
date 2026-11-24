package org.hnau.remotestate

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor

@Serializable(with = RemoteStateSerializer::class)
class RemoteState<out T>(
    val state: StateFlow<T>,
)

fun <T> StateFlow<T>.toRemoteState(): RemoteState<T> = RemoteState(
    state = this,
)

class RemoteStateSerializer<T>(
    valueSerializer: KSerializer<T>,
) : RemoteContextualSerializer<RemoteState<T>>(
    kClass = RemoteState::class,
    typeArgumentsSerializers = listOf(valueSerializer),
    descriptor = remoteStateDescriptor(valueSerializer.descriptor),
)

fun remoteStateDescriptor(
    valueDescriptor: SerialDescriptor,
): SerialDescriptor = buildClassSerialDescriptor(
    serialName = "RemoteState<${valueDescriptor.serialName}>",
) {
    element(RemoteStateIdElement, StateId.serializer().descriptor)
    element(RemoteStateValueElement, valueDescriptor)
}

const val RemoteStateIdElement: String = "id"

const val RemoteStateValueElement: String = "value"
