package org.hnau.remotestate.server

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.serialization.EitherSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.client.RemoteConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.toRemoteState
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

class PlainDataTest {

    @Serializable
    enum class Color { Red, Green }

    @Serializable
    @JvmInline
    value class Title(val value: String)

    @Serializable
    sealed interface Shape {

        @Serializable
        data class Circle(val radius: Double) : Shape

        @Serializable
        data object Empty : Shape
    }

    @Serializable
    data class Data(
        val int: Int,
        val long: Long,
        val double: Double,
        val boolean: Boolean,
        val char: Char,
        val string: String,
        val color: Color,
        val title: Title,
        val nullable: String?,
        val absent: String?,
        val matrix: List<List<Int>>,
        val byNumber: Map<Int, String>,
        val shapes: List<Shape>,
        val either: @Serializable(with = EitherSerializer::class) Either<String, Int>,
        val unit: Unit,
    )

    @Serializable
    data class Root(
        val data: RemoteState<Data>,
    )

    private val first = Data(
        int = 1,
        long = Long.MAX_VALUE,
        double = 0.5,
        boolean = true,
        char = 'x',
        string = "a\"b",
        color = Color.Green,
        title = Title("title"),
        nullable = "value",
        absent = null,
        matrix = listOf(listOf(1, 2), emptyList()),
        byNumber = mapOf(1 to "one", 2 to "two"),
        shapes = listOf(Shape.Circle(1.5), Shape.Empty),
        either = 3.right(),
        unit = Unit,
    )

    private val second = first.copy(
        int = 2,
        either = "left".left(),
        shapes = emptyList(),
    )

    @Test
    fun plainDataSurvivesRoundTrip(): Unit = runBlocking {
        val data = MutableStateFlow(first)
        val server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                remoteState(path = "/remote", serializer = Root.serializer()) { Root(data.toRemoteState()) }
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
                assertEquals(first, root.data.state.value)
                data.value = second
                assertEquals(second, root.data.state.first { it != first })
            }
        } finally {
            connection.cancel()
            client.close()
            server.stop()
        }
    }
}
