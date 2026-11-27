package org.hnau.remotestate.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteTreeEncoderTest {

    @Serializable
    data class Item(
        val state: RemoteState<Int>,
        val action: RemoteFunction<Unit, Unit>,
    )

    private class RecordingContext : RemoteTreeEncodeContext {

        val paths: MutableList<String> = mutableListOf()

        override fun encodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<*>,
            state: RemoteState<*>,
        ): JsonElement {
            paths += path.toString()
            return JsonObject(emptyMap())
        }

        override fun encodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<*, *>,
            function: RemoteFunction<*, *>,
        ): JsonElement {
            paths += path.toString()
            return JsonObject(emptyMap())
        }
    }

    private fun item(): Item = Item(
        state = MutableStateFlow(0).toRemoteState(),
        action = {},
    )

    private fun <T> paths(
        serializer: KSerializer<T>,
        value: T,
    ): List<String> = RecordingContext()
        .also { it.encodeTree(serializer, value) }
        .paths

    @Test
    fun remoteValuesInsideMapGetKeyPaths() {
        assertEquals(
            listOf("a/state", "a/action", "b/state", "b/action"),
            paths(MapSerializer(String.serializer(), Item.serializer()), mapOf("a" to item(), "b" to item())),
        )
    }

    @Test
    fun remoteValuesInsideListAreRejected() {
        assertFailsWith<IllegalStateException> {
            paths(ListSerializer(Item.serializer()), listOf(item()))
        }
    }

    @Test
    fun emptyMapKeysAreAllowedInPlainData() {
        assertEquals(
            emptyList(),
            paths(MapSerializer(String.serializer(), Int.serializer()), mapOf("" to 1)),
        )
    }
}
