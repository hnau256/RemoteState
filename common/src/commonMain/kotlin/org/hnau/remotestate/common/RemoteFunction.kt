package org.hnau.remotestate.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RemoteFunctionSerializer::class)
fun interface RemoteFunction<in A, out R> {

    suspend operator fun invoke(argument: A): R
}

suspend operator fun <R> RemoteFunction<Unit, R>.invoke(): R = invoke(Unit)

class RemoteFunctionSerializer<A, R>(
    val argumentSerializer: KSerializer<A>,
    val resultSerializer: KSerializer<R>,
) : KSerializer<RemoteFunction<A, R>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        serialName = "RemoteFunction<${argumentSerializer.descriptor.serialName};${resultSerializer.descriptor.serialName}>",
    )

    override fun serialize(
        encoder: Encoder,
        value: RemoteFunction<A, R>,
    ) {
        throw outsideOfSessionException()
    }

    override fun deserialize(
        decoder: Decoder,
    ): RemoteFunction<A, R> = throw outsideOfSessionException()
}
