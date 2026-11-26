package org.hnau.remotestate

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.hnau.commons.kotlin.mapper.Mapper
import org.hnau.commons.kotlin.mapper.plus
import org.hnau.commons.kotlin.mapper.stringToStringsBySeparator
import org.hnau.commons.kotlin.serialization.MappingKSerializer
import kotlin.jvm.JvmInline

@Serializable(with = RemotePath.Serializer::class)
@JvmInline
value class RemotePath(
    val segments: List<String>,
) {

    init {
        require(segments.none(String::isEmpty)) { "Remote path segments must not be empty: $segments" }
    }

    operator fun div(
        segment: String,
    ): RemotePath = RemotePath(
        segments = segments + segment,
    )

    fun isStrictlyInside(
        parent: RemotePath,
    ): Boolean = segments.size > parent.segments.size &&
            segments.subList(0, parent.segments.size) == parent.segments

    override fun toString(): String = stringMapper.reverse(this)

    object Serializer : MappingKSerializer<String, RemotePath>(
        base = String.serializer(),
        mapper = stringMapper,
    )

    companion object {

        val root: RemotePath = RemotePath(
            segments = emptyList(),
        )

        val stringMapper: Mapper<String, RemotePath> = Mapper.stringToStringsBySeparator(
            separator = '/',
            escape = '~',
        ) + Mapper(::RemotePath, RemotePath::segments)
    }
}
