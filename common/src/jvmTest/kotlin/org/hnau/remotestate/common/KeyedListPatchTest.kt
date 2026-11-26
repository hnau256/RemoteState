package org.hnau.remotestate.common

import org.hnau.commons.kotlin.KeyValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyedListPatchTest {

    private fun list(
        vararg keys: String,
    ): List<KeyValue<String, String>> = keys.map { KeyValue(it, it) }

    private fun assertRoundTrip(
        old: List<KeyValue<String, String>>,
        new: List<KeyValue<String, String>>,
    ): KeyedListRemoteState.Patch<String, String> {
        val patch = KeyedListRemoteState.diff(old, new)
        assertEquals(new, KeyedListRemoteState.apply(old, patch))
        return patch
    }

    @Test
    fun appendPlacesOnlyNewItem() {
        val patch = assertRoundTrip(list("a", "b"), list("a", "b", "c"))
        assertEquals(listOf(KeyedListRemoteState.Placement("c", after = "b")), patch.placed)
        assertEquals(mapOf("c" to "c"), patch.values)
    }

    @Test
    fun insertInTheMiddlePlacesOnlyNewItem() {
        val patch = assertRoundTrip(list("a", "b", "c", "d"), list("a", "b", "x", "c", "d"))
        assertEquals(listOf(KeyedListRemoteState.Placement("x", after = "b")), patch.placed)
    }

    @Test
    fun movingOneItemPlacesOnlyIt() {
        val patch = assertRoundTrip(list("a", "b", "c", "d", "e"), list("d", "a", "b", "c", "e"))
        assertEquals(listOf(KeyedListRemoteState.Placement("d", after = null)), patch.placed)
        assertEquals(emptyMap(), patch.values)
    }

    @Test
    fun changedValueIsSentWithoutPlacement() {
        val patch = assertRoundTrip(list("a", "b"), listOf(KeyValue("a", "a"), KeyValue("b", "changed")))
        assertEquals(emptyList(), patch.placed)
        assertEquals(mapOf("b" to "changed"), patch.values)
    }

    @Test
    fun randomChangesRoundTrip() {
        val random = Random(42)
        repeat(2000) {
            val old = (0 until random.nextInt(0, 20))
                .map { "k$it" }
                .shuffled(random)
                .map { KeyValue(it, "v${random.nextInt(3)}") }
            val new = (old.filter { random.nextInt(4) != 0 } + (0 until random.nextInt(0, 5)).map { KeyValue("n$it", "n") })
                .shuffled(random)
                .map { item -> if (random.nextInt(5) == 0) KeyValue(item.key, "changed") else item }
            assertRoundTrip(old, new)
        }
    }
}
