package org.hnau.remotestate.common

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RemoteMapSerializer::class)
class RemoteMap<K, out V>(
    val state: StateFlow<Map<K, V>>,
)

fun <K, V> StateFlow<Map<K, V>>.toRemoteMap(): RemoteMap<K, V> = RemoteMap(
    state = this,
)

class RemoteMapSerializer<K, V>(
    val keySerializer: KSerializer<K>,
    val valueSerializer: KSerializer<V>,
) : KSerializer<RemoteMap<K, V>> {

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        serialName = "RemoteMap<${keySerializer.descriptor.serialName},${valueSerializer.descriptor.serialName}>",
        original = MapSerializer(keySerializer, valueSerializer).descriptor,
    )

    override fun serialize(
        encoder: Encoder,
        value: RemoteMap<K, V>,
    ) {
        throw outsideOfSessionException()
    }

    override fun deserialize(
        decoder: Decoder,
    ): RemoteMap<K, V> = throw outsideOfSessionException()
}
