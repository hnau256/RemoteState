package org.hnau.remotestate.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.hnau.remotestate.client.RemoteCallException
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteMap
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.invoke
import org.hnau.remotestate.common.toRemoteMap
import org.hnau.remotestate.common.toRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RemoteMapTest {

    @Serializable
    data class Item(
        val title: String,
        val counter: RemoteState<Int>,
        val increment: RemoteFunction<Unit, Unit>,
    )

    @Serializable
    data class Root(
        val items: RemoteMap<Double, Item>,
        val put: RemoteFunction<Pair<Double, String>, Unit>,
        val rename: RemoteFunction<Pair<Double, String>, Unit>,
        val remove: RemoteFunction<Double, Unit>,
    )

    private class RootModel {

        private val items = MutableStateFlow(emptyMap<Double, Item>())

        private fun item(
            title: String,
        ): Item {
            val counter = MutableStateFlow(0)
            return Item(
                title = title,
                counter = counter.toRemoteState(),
                increment = { counter.update { it + 1 } },
            )
        }

        val state: Root = Root(
            items = items.toRemoteMap(),
            put = { (key, title) -> items.update { it + (key to item(title)) } },
            rename = { (key, title) -> items.update { it + (key to it.getValue(key).copy(title = title)) } },
            remove = { key -> items.update { it - key } },
        )
    }

    @Test
    fun onlyChangedEntriesAreReplaced() = withRemoteState(
        serializer = Root.serializer(),
        createRoot = { RootModel().state },
    ) { root ->
        root.put(1.0 to "a")
        root.put(2.0 to "c")
        val initial = root.items.state.first { it.size == 2 }
        val a = initial.getValue(1.0)
        a.increment()
        a.counter.state.first { it == 1 }

        root.put(1.5 to "b")
        val inserted = root.items.state.first { it.size == 3 }
        assertEquals(setOf("a", "b", "c"), inserted.values.map(Item::title).toSet())
        assertSame(a, inserted.getValue(1.0))
        assertSame(initial.getValue(2.0), inserted.getValue(2.0))

        root.remove(1.5)
        val removed = root.items.state.first { it.size == 2 }
        assertSame(a, removed.getValue(1.0))
        a.increment()
        a.counter.state.first { it == 2 }

        root.rename(2.0 to "z")
        val renamed = root.items.state.first { it.getValue(2.0).title == "z" }
        assertSame(a, renamed.getValue(1.0))

        val b = inserted.getValue(1.5)
        assertFailsWith<RemoteCallException> { b.increment() }
    }
}
