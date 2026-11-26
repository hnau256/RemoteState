package org.hnau.remotestate.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStatePatcher
import org.hnau.remotestate.common.SnapshotRemoteState
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.common.toSnapshotRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

class NestedPatchesTest {

    @Serializable(with = DeltaRemoteState.Patcher::class)
    class DeltaRemoteState(
        state: StateFlow<Int>,
    ) : RemoteState<Int>(state) {

        object Patcher : RemoteStatePatcher<DeltaRemoteState, Int, Int>(
            valueSerializer = Int.serializer(),
            patchSerializer = Int.serializer(),
            diff = { old, new -> new - old },
            apply = { value, delta -> value + delta },
            create = ::DeltaRemoteState,
        )
    }

    @Serializable
    data class Holder(
        val tag: Int,
        val counter: DeltaRemoteState,
    )

    @Serializable
    data class Root(
        val holder: SnapshotRemoteState<Holder>,
    )

    private val count = 5000

    @Test
    fun patchedChildStaysConsistentWhileParentIsResent(): Unit = runBlocking {
        val counter = MutableStateFlow(0)
        val holder = MutableStateFlow(Holder(tag = 0, counter = DeltaRemoteState(counter)))
        val server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                remoteState(path = "/remote", serializer = Root.serializer()) {
                    Root(holder = holder.toSnapshotRemoteState())
                }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO) { install(WebSockets) }
        val connection = Job(coroutineContext[Job])
        try {
            withTimeout(20.seconds) {
                val root = client
                    .connectRemoteState(
                        scope = CoroutineScope(coroutineContext + connection),
                        url = "ws://localhost:$port/remote",
                        serializer = Root.serializer(),
                    )
                    .filterIsInstance<RemoteConnectionState.Connected<Root>>()
                    .first()
                    .root

                launch(Dispatchers.Default) {
                    repeat(count) {
                        holder.update { it.copy(tag = it.tag + 1) }
                        counter.update { it + 1 }
                    }
                }.join()

                counter.update { it + 1 }
                val received = root.holder.state.first { it.tag == count }
                assertEquals(count + 1, received.counter.state.first { it > count })
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }
}
