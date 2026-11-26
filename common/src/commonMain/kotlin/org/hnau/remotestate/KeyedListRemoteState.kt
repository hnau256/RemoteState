package org.hnau.remotestate

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.hnau.commons.kotlin.KeyValue

@Serializable(with = KeyedListRemoteState.Patcher::class)
class KeyedListRemoteState<K, V>(
    state: StateFlow<List<KeyValue<K, V>>>,
) : RemoteState<List<KeyValue<K, V>>>(state) {

    @Serializable
    data class Placement<K>(
        val key: K,
        val after: K? = null,
    )

    @Serializable
    data class Patch<K, V>(
        val removed: List<K> = emptyList(),
        val placed: List<Placement<K>> = emptyList(),
        val values: Map<K, V> = emptyMap(),
    )

    class Patcher<K, V>(
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
    ) : RemoteStatePatcher<KeyedListRemoteState<K, V>, List<KeyValue<K, V>>, Patch<K, V>>(
        valueSerializer = KeyValueListSerializer(keySerializer, valueSerializer),
        patchSerializer = Patch.serializer(keySerializer, valueSerializer),
        diff = ::diff,
        apply = ::apply,
        create = ::KeyedListRemoteState,
    )

    companion object {

        internal fun <K, V> diff(
            old: List<KeyValue<K, V>>,
            new: List<KeyValue<K, V>>,
        ): Patch<K, V> {
            val newKeys = new.map(KeyValue<K, V>::key)
            val newIndices = newKeys.withIndex().associate { (index, key) -> key to index }
            require(newIndices.size == newKeys.size) { "Keys of KeyedListRemoteState must be unique: $newKeys" }
            val oldValues = old.associate { (key, value) -> key to value }
            val kept = old.map(KeyValue<K, V>::key).filter { it in newIndices }
            val stable = longestIncreasingSubsequence(kept.map(newIndices::getValue))
                .map(kept::get)
                .toSet()
            return Patch(
                removed = old.map(KeyValue<K, V>::key).filter { it !in newIndices },
                placed = newKeys
                    .withIndex()
                    .filter { (_, key) -> key !in stable }
                    .map { (index, key) -> Placement(key = key, after = newKeys.getOrNull(index - 1)) },
                values = new
                    .filter { (key, value) -> !oldValues.containsKey(key) || oldValues.getValue(key) != value }
                    .associate { (key, value) -> key to value },
            )
        }

        internal fun <K, V> apply(
            value: List<KeyValue<K, V>>,
            patch: Patch<K, V>,
        ): List<KeyValue<K, V>> {
            val values = LinkedHashMap<K, V>()
            value.forEach { (key, value) -> values[key] = value }
            patch.removed.forEach(values::remove)
            values.putAll(patch.values)
            val placedKeys = patch.placed.map(Placement<K>::key).toSet()
            val order = value
                .map(KeyValue<K, V>::key)
                .filterTo(ArrayList()) { it in values && it !in placedKeys }
            patch.placed.forEach { (key, after) ->
                val index = after?.let { order.indexOf(it) + 1 } ?: 0
                order.add(index, key)
            }
            return order.map { key -> KeyValue(key, values.getValue(key)) }
        }

        private fun longestIncreasingSubsequence(
            values: List<Int>,
        ): List<Int> {
            val tails = ArrayList<Int>()
            val previous = IntArray(values.size) { -1 }
            values.forEachIndexed { index, value ->
                var low = 0
                var high = tails.size
                while (low < high) {
                    val middle = (low + high) / 2
                    if (values[tails[middle]] < value) low = middle + 1 else high = middle
                }
                if (low > 0) {
                    previous[index] = tails[low - 1]
                }
                if (low == tails.size) tails.add(index) else tails[low] = index
            }
            return generateSequence(tails.lastOrNull()) { index -> previous[index].takeIf { it >= 0 } }
                .toList()
                .asReversed()
        }
    }
}

fun <K, V> StateFlow<List<KeyValue<K, V>>>.toKeyedListRemoteState(): KeyedListRemoteState<K, V> =
    KeyedListRemoteState(
        state = this,
    )

internal class KeyValueListSerializer<K, V>(
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) : KSerializer<List<KeyValue<K, V>>> {

    private val mapSerializer = MapSerializer(keySerializer, valueSerializer)

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        serialName = "KeyValueList<${keySerializer.descriptor.serialName},${valueSerializer.descriptor.serialName}>",
        original = mapSerializer.descriptor,
    )

    override fun serialize(
        encoder: Encoder,
        value: List<KeyValue<K, V>>,
    ) {
        val map = LinkedHashMap<K, V>()
        value.forEach { (key, value) -> map[key] = value }
        encoder.encodeSerializableValue(mapSerializer, map)
    }

    override fun deserialize(
        decoder: Decoder,
    ): List<KeyValue<K, V>> = decoder
        .decodeSerializableValue(mapSerializer)
        .map { (key, value) -> KeyValue(key, value) }
}
