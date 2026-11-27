package org.hnau.remotestate.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JsonPatchTest {

    private fun roundTrip(
        old: JsonElement,
        new: JsonElement,
    ): List<JsonOperation> {
        val patch = diffJson(old, new)
        assertEquals(new.toString(), old.applyPatch(patch).toString())
        return patch
    }

    private fun objectOf(
        vararg keys: String,
    ): JsonObject = JsonObject(keys.associateWith { JsonPrimitive(it) })

    @Test
    fun appendedTextIsSentAsSuffix() {
        val patch = roundTrip(
            buildJsonObject { put("text", "Hello") },
            buildJsonObject { put("text", "Hello, world") },
        )
        assertEquals(listOf(JsonOperation.Append(RemotePath.root / RemotePathSegment("text"), ", world")), patch)
    }

    @Test
    fun insertedMemberIsPlacedWithoutMovingOthers() {
        val patch = roundTrip(objectOf("a", "b", "c"), objectOf("a", "x", "b", "c"))
        val x = RemotePath.root / RemotePathSegment("x")
        assertEquals(
            listOf(JsonOperation.Set(x, JsonPrimitive("x")), JsonOperation.Place(x, after = "a")),
            patch,
        )
    }

    @Test
    fun movedMemberIsPlacedAlone() {
        val patch = roundTrip(objectOf("a", "b", "c", "d"), objectOf("d", "a", "b", "c"))
        assertEquals(listOf(JsonOperation.Place(RemotePath.root / RemotePathSegment("d"), after = null)), patch)
    }

    @Test
    fun untouchedSubtreesKeepTheirInstances() {
        val untouched = objectOf("x", "y")
        val old = JsonObject(mapOf("a" to untouched, "b" to JsonPrimitive(1)))
        val new = JsonObject(mapOf("a" to objectOf("x", "y"), "b" to JsonPrimitive(2)))
        val patched = old.applyPatch(roundTrip(old, new)) as JsonObject
        assertSame(untouched, patched["a"])
    }

    private fun Random.element(
        depth: Int,
    ): JsonElement = when (if (depth == 0) nextInt(3) else nextInt(5)) {
        0 -> JsonPrimitive(nextInt(3))
        1 -> JsonPrimitive("t".repeat(nextInt(4)))
        2 -> JsonPrimitive(nextBoolean())
        3 -> JsonArray((0 until nextInt(4)).map { element(depth - 1) })
        else -> JsonObject((0 until nextInt(5)).associate { "k${nextInt(6)}" to element(depth - 1) })
    }

    private fun Random.mutate(
        element: JsonElement,
        depth: Int,
    ): JsonElement = when {
        nextInt(6) == 0 -> element(depth)
        element is JsonObject -> JsonObject(
            element.entries
                .filter { nextInt(5) != 0 }
                .map { (key, value) -> key to mutate(value, depth - 1) }
                .plus((0 until nextInt(2)).map { "n${nextInt(6)}" to element(depth - 1) })
                .shuffled(this)
                .toMap(),
        )

        element is JsonArray -> JsonArray(element.map { mutate(it, depth - 1) } + (0 until nextInt(2)).map { element(depth - 1) })
        element is JsonPrimitive && element.isString && nextBoolean() -> JsonPrimitive(element.content + "s")
        else -> element
    }

    @Test
    fun randomChangesRoundTrip() {
        val random = Random(42)
        repeat(3000) {
            val old = random.element(depth = 3)
            roundTrip(old, random.mutate(old, depth = 3))
        }
    }
}
