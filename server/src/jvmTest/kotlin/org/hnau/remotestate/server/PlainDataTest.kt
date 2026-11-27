package org.hnau.remotestate.server

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.serialization.EitherSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.toRemoteState
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun plainDataSurvivesRoundTrip() {
        val data = MutableStateFlow(first)
        withRemoteState(
            serializer = Root.serializer(),
            createRoot = { Root(data.toRemoteState()) },
        ) { root ->
            assertEquals(first, root.data.state.value)
            data.value = second
            assertEquals(second, root.data.state.first { it != first })
        }
    }
}
