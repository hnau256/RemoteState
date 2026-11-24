package org.hnau.remotestate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass

open class RemoteContextualSerializer<T : Any>(
    private val kClass: KClass<*>,
    private val typeArgumentsSerializers: List<KSerializer<*>>,
    override val descriptor: SerialDescriptor,
) : KSerializer<T> {

    @Suppress("UNCHECKED_CAST")
    private fun SerializersModule.resolve(): KSerializer<T> =
        getContextual(kClass, typeArgumentsSerializers) as KSerializer<T>?
            ?: error("${kClass.simpleName} can be serialized only inside remote session")

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        encoder.serializersModule.resolve().serialize(encoder, value)
    }

    override fun deserialize(
        decoder: Decoder,
    ): T = decoder.serializersModule.resolve().deserialize(decoder)
}
