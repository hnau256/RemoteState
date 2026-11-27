package org.hnau.remotestate.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.remotestate.client.RemoteCallException
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.invoke
import org.hnau.remotestate.common.toRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MapStateTest {

    @Serializable
    data class Item(
        val title: String,
        val counter: RemoteState<Int>,
        val increment: RemoteFunction<Unit, Unit>,
    )

    @Serializable
    data class Root(
        val items: RemoteState<Map<String, Item>>,
        val add: RemoteFunction<String, Unit>,
        val addFirst: RemoteFunction<String, Unit>,
        val remove: RemoteFunction<String, Unit>,
    )

    private class RootModel(
        scope: CoroutineScope,
    ) {

        private val keys: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

        val state: Root = Root(
            items = keys
                .mapListReusable(
                    scope = scope,
                    extractKey = { it },
                ) { _, key ->
                    val counter = MutableStateFlow(0)
                    Item(
                        title = key,
                        counter = counter.toRemoteState(),
                        increment = { counter.update { it + 1 } },
                    )
                }
                .mapState(scope) { items -> items.associateBy(Item::title) }
                .toRemoteState(),
            add = { key -> keys.update { it + key } },
            addFirst = { key -> keys.update { listOf(key) + it } },
            remove = { key -> keys.update { it - key } },
        )
    }

    @Test
    fun unchangedItemsKeepTheirInstances() = withRemoteState(
        serializer = Root.serializer(),
        createRoot = { scope -> RootModel(scope).state },
    ) { root ->
        root.add("a")
        root.add("b")
        val a = root.items.state.first { it.size == 2 }.getValue("a")
        a.increment()
        a.increment()
        a.counter.state.first { it == 2 }

        root.add("c")
        assertSame(a, root.items.state.first { it.size == 3 }.getValue("a"))

        root.addFirst("x")
        val withFirst = root.items.state.first { it.size == 4 }
        assertEquals(listOf("x", "a", "b", "c"), withFirst.keys.toList())
        assertSame(a, withFirst.getValue("a"))
        a.increment()
        a.counter.state.first { it == 3 }

        root.remove("a")
        assertEquals(listOf("x", "b", "c"), root.items.state.first { it.size == 3 }.keys.toList())
        assertFailsWith<RemoteCallException> { a.increment() }
    }
}
