package org.hnau.remotestate

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

abstract class RemoteState<out T>(
    val state: StateFlow<T>,
)

abstract class RemoteStatePatcher<S : RemoteState<T>, T, P>(
    val valueSerializer: KSerializer<T>,
    val patchSerializer: KSerializer<P>,
    val diff: (old: T, new: T) -> P,
    val apply: (value: T, patch: P) -> T,
    val create: (state: StateFlow<T>) -> S,
) : KSerializer<S> {

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        serialName = "${this::class.simpleName ?: "RemoteState"}<${valueSerializer.descriptor.serialName}>",
        original = valueSerializer.descriptor,
    )

    override fun serialize(
        encoder: Encoder,
        value: S,
    ) {
        throw outsideOfSessionException()
    }

    override fun deserialize(
        decoder: Decoder,
    ): S = throw outsideOfSessionException()
}

internal fun outsideOfSessionException(): IllegalStateException =
    IllegalStateException("RemoteState and RemoteFunction can be serialized only inside remote session")
