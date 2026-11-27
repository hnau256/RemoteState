package org.hnau.remotestate.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.hnau.commons.kotlin.mapper.Mapper
import org.hnau.commons.kotlin.mapper.plus
import org.hnau.commons.kotlin.mapper.stringToStringsBySeparator
import org.hnau.commons.kotlin.serialization.MappingKSerializer
import kotlin.jvm.JvmInline

@JvmInline
value class RemotePathSegment(
    val value: String,
) {

    init {
        require(value.isNotEmpty()) { "Remote path segment must not be empty" }
    }

    override fun toString(): String = value
}

@Serializable(with = RemotePath.Serializer::class)
@JvmInline
value class RemotePath(
    val segments: List<RemotePathSegment>,
) {

    operator fun div(
        segment: RemotePathSegment,
    ): RemotePath = RemotePath(
        segments = segments + segment,
    )

    operator fun plus(
        other: RemotePath,
    ): RemotePath = RemotePath(
        segments = segments + other.segments,
    )

    fun split(): Pair<RemotePathSegment, RemotePath>? = segments
        .firstOrNull()
        ?.let { head -> head to RemotePath(segments.drop(1)) }

    fun splitLast(): Pair<RemotePath, RemotePathSegment>? = segments
        .lastOrNull()
        ?.let { last -> RemotePath(segments.dropLast(1)) to last }

    fun startsWith(
        prefix: RemotePath,
    ): Boolean = segments.size >= prefix.segments.size &&
            segments.subList(0, prefix.segments.size) == prefix.segments

    fun removePrefix(
        prefix: RemotePath,
    ): RemotePath = RemotePath(segments.drop(prefix.segments.size))

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
        ) + Mapper(
            direct = { strings -> RemotePath(strings.map(::RemotePathSegment)) },
            reverse = { path -> path.segments.map(RemotePathSegment::value) },
        )
    }
}
