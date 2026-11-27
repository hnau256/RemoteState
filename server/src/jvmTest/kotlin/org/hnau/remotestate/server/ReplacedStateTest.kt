package org.hnau.remotestate.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.toRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ReplacedStateTest {

    @Serializable
    data class Holder(
        val counter: RemoteState<Int>,
    )

    @Serializable
    data class Root(
        val holder: RemoteState<Holder>,
    )

    @Test
    fun newStateInstanceAtTheSamePathIsReceived() {
        val first = MutableStateFlow(1)
        val second = MutableStateFlow(10)
        val holder = MutableStateFlow(Holder(first.toRemoteState()))
        withRemoteState(
            serializer = Root.serializer(),
            createRoot = { Root(holder.toRemoteState()) },
        ) { root ->
            val initial = root.holder.state.value
            assertEquals(1, initial.counter.state.value)

            holder.value = Holder(second.toRemoteState())
            val replaced = root.holder.state.first { it !== initial }
            assertNotSame(initial.counter, replaced.counter)
            assertEquals(10, replaced.counter.state.value)

            second.value = 11
            replaced.counter.state.first { it == 11 }
            first.value = 2
            second.value = 12
            replaced.counter.state.first { it == 12 }
            assertEquals(1, initial.counter.state.value)
        }
    }
}
