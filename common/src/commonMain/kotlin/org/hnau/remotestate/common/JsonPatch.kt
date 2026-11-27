package org.hnau.remotestate.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RemoteStateInternalApi
fun JsonElement.applyPatch(
    patch: List<JsonOperation>,
): JsonElement = patch.fold(this) { json, operation -> json.apply(operation) }

@RemoteStateInternalApi
fun JsonElement.touch(
    path: RemotePath,
): JsonElement = update(path) { current ->
    when (val element = current.required(path)) {
        is JsonObject -> JsonObject(LinkedHashMap(element))
        is JsonArray -> JsonArray(element.toList())
        is JsonPrimitive -> element
    }
}

private fun JsonElement.apply(
    operation: JsonOperation,
): JsonElement = operation.fold(
    ifSet = { path, value -> update(path) { value } },
    ifRemove = { path -> updateMember(path) { members, key -> members.remove(key) } },
    ifAppend = { path, text -> update(path) { current -> JsonPrimitive(current.required(path).jsonPrimitive.content + text) } },
    ifPush = { path, items -> update(path) { current -> JsonArray(current.required(path).jsonArray + items) } },
    ifPlace = { path, after -> updateMember(path) { members, key -> members.place(key, after) } },
)

private fun JsonElement?.required(
    path: RemotePath,
): JsonElement = this ?: error("Nothing to patch at $path")

private fun JsonElement.updateMember(
    path: RemotePath,
    transform: (members: LinkedHashMap<String, JsonElement>, key: String) -> Unit,
): JsonElement {
    val (parent, key) = path.splitLast() ?: error("Member operation requires a member path")
    return update(parent) { current ->
        JsonObject(LinkedHashMap(current.required(parent).jsonObject).also { transform(it, key.value) })
    }
}

private fun LinkedHashMap<String, JsonElement>.place(
    key: String,
    after: String?,
) {
    val keys = keys.filterTo(ArrayList()) { it != key }
    keys.add(after?.let { keys.indexOf(it) + 1 } ?: 0, key)
    val values = LinkedHashMap(this)
    clear()
    keys.forEach { put(it, values.getValue(it)) }
}

private fun JsonElement?.update(
    path: RemotePath,
    transform: (JsonElement?) -> JsonElement,
): JsonElement {
    val (head, tail) = path.split() ?: return transform(this)
    return when (this) {
        is JsonObject -> JsonObject(
            LinkedHashMap(this).also { members -> members[head.value] = members[head.value].update(tail, transform) }
        )

        is JsonArray -> JsonArray(
            toMutableList().also { items ->
                val index = head.value.toInt()
                items[index] = items[index].update(tail, transform)
            }
        )

        else -> error("Can not patch $path in $this")
    }
}
