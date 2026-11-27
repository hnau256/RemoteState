package org.hnau.remotestate.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RemoteStateInternalApi
fun diffJson(
    old: JsonElement,
    new: JsonElement,
): List<JsonOperation> = buildList { diff(RemotePath.root, old, new) }

private fun MutableList<JsonOperation>.diff(
    path: RemotePath,
    old: JsonElement,
    new: JsonElement,
) {
    when {
        old === new -> Unit
        old is JsonObject && new is JsonObject -> diffObjects(path, old, new)
        old is JsonArray && new is JsonArray && new.size >= old.size -> diffArrays(path, old, new)
        old.isPrefixOf(new) -> add(
            JsonOperation.Append(
                path = path,
                text = (new as JsonPrimitive).content.substring((old as JsonPrimitive).content.length),
            )
        )

        old == new -> Unit
        else -> add(JsonOperation.Set(path, new))
    }
}

private fun JsonElement.isPrefixOf(
    other: JsonElement,
): Boolean = this is JsonPrimitive && isString &&
        other is JsonPrimitive && other.isString &&
        other.content.length > content.length &&
        other.content.startsWith(content)

private fun MutableList<JsonOperation>.diffArrays(
    path: RemotePath,
    old: JsonArray,
    new: JsonArray,
) {
    old.indices.forEach { index ->
        diff(path / RemotePathSegment(index.toString()), old[index], new[index])
    }
    if (new.size > old.size) {
        add(JsonOperation.Push(path, new.subList(old.size, new.size)))
    }
}

private fun MutableList<JsonOperation>.diffObjects(
    path: RemotePath,
    old: JsonObject,
    new: JsonObject,
) {
    old.keys
        .filter { it !in new }
        .forEach { key -> add(JsonOperation.Remove(path / RemotePathSegment(key))) }
    new.forEach { (key, value) ->
        when (val oldValue = old[key]) {
            null -> add(JsonOperation.Set(path / RemotePathSegment(key), value))
            else -> diff(path / RemotePathSegment(key), oldValue, value)
        }
    }
    val newKeys = new.keys.toList()
    val kept = old.keys.filter { it in new }
    val expected = kept + newKeys.filter { it !in old }
    if (newKeys == expected) {
        return
    }
    val newIndices = newKeys.withIndex().associate { (index, key) -> key to index }
    val stable = longestIncreasingSubsequence(kept.map(newIndices::getValue))
        .map(kept::get)
        .toSet()
    newKeys.forEachIndexed { index, key ->
        if (key !in stable) {
            add(JsonOperation.Place(path / RemotePathSegment(key), after = newKeys.getOrNull(index - 1)))
        }
    }
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
