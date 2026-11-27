package org.hnau.remotestate.common

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RemoteStateSerializer::class)
class RemoteState<out T>(
    val state: StateFlow<T>,
)

fun <T> StateFlow<T>.toRemoteState(): RemoteState<T> = RemoteState(
    state = this,
)

class RemoteStateSerializer<T>(
    val valueSerializer: KSerializer<T>,
) : KSerializer<RemoteState<T>> {

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        serialName = "RemoteState<${valueSerializer.descriptor.serialName}>",
        original = valueSerializer.descriptor,
    )

    override fun serialize(
        encoder: Encoder,
        value: RemoteState<T>,
    ) {
        throw outsideOfSessionException()
    }

    override fun deserialize(
        decoder: Decoder,
    ): RemoteState<T> = throw outsideOfSessionException()
}

internal fun outsideOfSessionException(): IllegalStateException =
    IllegalStateException("RemoteState and RemoteFunction can be serialized only inside remote session")
