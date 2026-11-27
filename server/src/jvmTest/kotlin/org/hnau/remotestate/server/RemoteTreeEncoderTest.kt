package org.hnau.remotestate.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteFunctionSerializer
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStateSerializer
import org.hnau.remotestate.common.toRemoteState
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

        override fun <T> encodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<T>,
            state: RemoteState<T>,
        ): JsonElement {
            paths += path.toString()
            return JsonPrimitive("state")
        }

        override fun <A, R> encodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<A, R>,
            function: RemoteFunction<A, R>,
        ): JsonElement {
            paths += path.toString()
            return JsonObject(emptyMap())
        }
    }

    private fun item(): Item = Item(
        state = MutableStateFlow(0).toRemoteState(),
        action = {},
    )

    @Test
    fun remoteValuesInsideMapGetKeyPaths() {
        val context = RecordingContext()
        context.encodeTree(
            serializer = MapSerializer(String.serializer(), Item.serializer()),
            value = mapOf("a" to item(), "b" to item()),
        )
        assertEquals(
            listOf("a/state", "a/action", "b/state", "b/action"),
            context.paths,
        )
    }

    @Test
    fun remoteValuesInsideListAreRejected() {
        assertFailsWith<IllegalStateException> {
            RecordingContext().encodeTree(
                serializer = ListSerializer(Item.serializer()),
                value = listOf(item()),
            )
        }
    }
}
