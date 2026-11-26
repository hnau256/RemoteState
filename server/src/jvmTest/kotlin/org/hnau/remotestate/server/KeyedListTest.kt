package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.hnau.commons.kotlin.KeyValue
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.remotestate.common.KeyedListRemoteState
import org.hnau.remotestate.client.RemoteCallException
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.SnapshotRemoteState
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.common.invoke
import org.hnau.remotestate.common.toKeyedListRemoteState
import org.hnau.remotestate.common.toSnapshotRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

class KeyedListTest {

    @Serializable
    data class Item(
        val counter: SnapshotRemoteState<Int>,
        val increment: RemoteFunction<Unit, Unit>,
    )

    @Serializable
    data class Root(
        val items: KeyedListRemoteState<String, Item>,
        val add: RemoteFunction<String, Unit>,
        val remove: RemoteFunction<String, Unit>,
        val reverse: RemoteFunction<Unit, Unit>,
    )

    private class ItemModel {

        private val counter: MutableStateFlow<Int> = MutableStateFlow(0)

        val state: Item = Item(
            counter = counter.toSnapshotRemoteState(),
            increment = { counter.update { it + 1 } },
        )
    }

    private class RootModel(
        scope: CoroutineScope,
    ) {

        private val keys: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

        val state: Root = Root(
            items = keys
                .mapListReusable(
                    scope = scope,
                    extractKey = { it },
                ) { _, key -> KeyValue(key, ItemModel().state) }
                .toKeyedListRemoteState(),
            add = { key -> keys.update { it + key } },
            remove = { key -> keys.update { it - key } },
            reverse = { keys.update { it.reversed() } },
        )
    }

    @Test
    fun nestedStatesSurviveSiblingChanges(): Unit = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                remoteState(path = "/remote", serializer = Root.serializer()) { scope -> RootModel(scope).state }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        val connection = Job(coroutineContext[Job])
        try {
            withTimeout(10.seconds) {
                val root = client
                    .connectRemoteState(
                        scope = CoroutineScope(coroutineContext + connection),
                        url = "ws://localhost:$port/remote",
                        serializer = Root.serializer(),
                    )
                    .filterIsInstance<RemoteConnectionState.Connected<Root>>()
                    .first()
                    .root

                root.add("a")
                root.add("b")
                val a = root.items.state.first { it.size == 2 }.first { it.key == "a" }.value
                a.increment()
                a.increment()
                a.counter.state.first { it == 2 }

                root.reverse()
                val reversed = root.items.state.first { items -> items.map { it.key } == listOf("b", "a") }
                assertSame(a, reversed.first { it.key == "a" }.value)
                a.increment()
                a.counter.state.first { it == 3 }

                root.add("c")
                root.items.state.first { it.size == 3 }
                a.increment()
                a.counter.state.first { it == 4 }

                root.remove("a")
                val remaining = root.items.state.first { it.size == 2 }
                assertEquals(listOf("b", "c"), remaining.map { it.key })
                assertFailsWith<RemoteCallException> { a.increment() }
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }
}
