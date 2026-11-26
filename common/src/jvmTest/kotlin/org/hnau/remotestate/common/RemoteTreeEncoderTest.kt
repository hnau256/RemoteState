package org.hnau.remotestate.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.hnau.commons.kotlin.KeyValue
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteTreeEncoderTest {

    @Serializable
    data class Item(
        val state: SnapshotRemoteState<Int>,
        val action: RemoteFunction<Unit, Unit>,
    )

    private fun item(): Item = Item(
        state = MutableStateFlow(0).toSnapshotRemoteState(),
        action = {},
    )

    private fun <T> paths(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): List<String> = walkTree(serializer, value).let { walk ->
        (walk.states.keys + walk.functions.keys).map(RemotePath::toString)
    }

    @Test
    fun remoteValuesInsideMapGetKeyPaths() {
        assertEquals(
            listOf("a/state", "b/state", "a/action", "b/action"),
            paths(MapSerializer(String.serializer(), Item.serializer()), mapOf("a" to item(), "b" to item())),
        )
    }

    @Test
    fun remoteValuesInsideListGetIndexPaths() {
        assertEquals(
            listOf("0/state", "1/state", "0/action", "1/action"),
            paths(ListSerializer(Item.serializer()), listOf(item(), item())),
        )
    }

    @Test
    fun keyedListItemsGetKeyPaths() {
        val list = MutableStateFlow(listOf(KeyValue("a", item()), KeyValue("b", item())))
        val walk = walkTree(
            KeyedListRemoteState.Patcher(String.serializer(), Item.serializer()).valueSerializer,
            list.value,
        )
        assertEquals(listOf("a/state", "b/state"), walk.states.keys.map(RemotePath::toString))
    }

    @Test
    fun emptyMapKeysAreAllowedInPlainData() {
        assertEquals(
            emptyList(),
            paths(MapSerializer(String.serializer(), Int.serializer()), mapOf("" to 1)),
        )
    }
}
