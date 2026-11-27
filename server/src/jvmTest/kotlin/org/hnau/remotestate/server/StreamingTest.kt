package org.hnau.remotestate.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.toRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingTest {

    @Serializable
    data class Message(
        val tag: Int,
        val text: RemoteState<String>,
    )

    @Serializable
    data class Root(
        val message: RemoteState<Message>,
    )

    private val count = 3000

    @Test
    fun nestedTextStaysConsistentWhileParentChanges() {
        val text = MutableStateFlow("")
        val message = MutableStateFlow(Message(tag = 0, text = text.toRemoteState()))
        withRemoteState(
            serializer = Root.serializer(),
            createRoot = { Root(message.toRemoteState()) },
        ) { root ->
            launch(Dispatchers.Default) {
                repeat(count) { index ->
                    message.update { it.copy(tag = it.tag + 1) }
                    text.update { it + "$index," }
                }
            }.join()
            val expected = text.value
            val received = root.message.state.first { it.tag == count }
            assertEquals(expected, received.text.state.first { it.length >= expected.length })
        }
    }
}
