@file:OptIn(ExperimentalSerializationApi::class)

package org.hnau.remotestate.client

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import org.hnau.remotestate.RemotePathSegment
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.RemoteStateSerializer

internal interface RemoteTreeDecodeContext {

    fun <T> decodeState(
        path: RemotePath,
        serializer: RemoteStateSerializer<T>,
        element: JsonElement,
    ): RemoteState<T>

    fun <A, R> decodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<A, R>,
    ): RemoteFunction<A, R>
}

internal fun <T> RemoteTreeDecodeContext.decodeTree(
    deserializer: DeserializationStrategy<T>,
    element: JsonElement,
): T = RootDecoder(
    context = this,
    element = element,
).decodeSerializableValue(deserializer)

private abstract class TreeDecoder(
    protected val context: RemoteTreeDecodeContext,
    protected val insideList: Boolean,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule = RemoteJson.serializersModule

    protected abstract val element: JsonElement

    protected abstract val elementPath: RemotePath

    private fun remoteElementPath(): RemotePath = elementPath.also { path ->
        check(!insideList) { "Remote value at $path is inside a list. Use Map instead" }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T = when (deserializer) {
        is RemoteStateSerializer<*> -> context.decodeState(
            path = remoteElementPath(),
            serializer = deserializer,
            element = element,
        ) as T

        is RemoteFunctionSerializer<*, *> -> context.decodeFunction(
            path = remoteElementPath(),
            serializer = deserializer,
        ) as T

        else -> deserializer.deserialize(this)
    }

    private fun content(): String = (element as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.content
        ?: error("Expected primitive at $elementPath, got $element")

    override fun decodeNotNullMark(): Boolean = element !is JsonNull

    override fun decodeNull(): Nothing? = null

    override fun decodeBoolean(): Boolean = content().toBooleanStrict()

    override fun decodeByte(): Byte = content().toByte()

    override fun decodeShort(): Short = content().toShort()

    override fun decodeInt(): Int = content().toInt()

    override fun decodeLong(): Long = content().toLong()

    override fun decodeFloat(): Float = content().toFloat()

    override fun decodeDouble(): Double = content().toDouble()

    override fun decodeChar(): Char = content().single()

    override fun decodeString(): String = content()

    override fun decodeEnum(
        enumDescriptor: SerialDescriptor,
    ): Int = content().let { name ->
        enumDescriptor
            .getElementIndex(name)
            .takeIf { it != CompositeDecoder.UNKNOWN_NAME }
            ?: error("Unknown ${enumDescriptor.serialName} value '$name' at $elementPath")
    }

    override fun beginStructure(
        descriptor: SerialDescriptor,
    ): CompositeDecoder = when (descriptor.kind) {
        StructureKind.LIST -> ListDecoder(context, elementPath, expect<JsonArray>())
        StructureKind.MAP -> MapDecoder(context, elementPath, insideList, expect<JsonObject>())
        else -> ObjectDecoder(context, elementPath, insideList, expect<JsonObject>(), descriptor)
    }

    private inline fun <reified E : JsonElement> expect(): E = element as? E
        ?: error("Expected ${E::class.simpleName} at $elementPath, got $element")
}

private class RootDecoder(
    context: RemoteTreeDecodeContext,
    override val element: JsonElement,
) : TreeDecoder(context, insideList = false) {

    override val elementPath: RemotePath = RemotePath.root

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = error("Root decoder has no elements")
}

private class ObjectDecoder(
    context: RemoteTreeDecodeContext,
    private val path: RemotePath,
    insideList: Boolean,
    private val content: JsonObject,
    private val descriptor: SerialDescriptor,
) : TreeDecoder(context, insideList) {

    private var index: Int = -1

    private val name: String
        get() = descriptor.getElementName(index)

    override val element: JsonElement
        get() = content.getValue(name)

    override val elementPath: RemotePath
        get() = path / RemotePathSegment(name)

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int {
        while (++index < this.descriptor.elementsCount) {
            if (name in content) {
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }
}

private class ListDecoder(
    context: RemoteTreeDecodeContext,
    private val path: RemotePath,
    private val content: JsonArray,
) : TreeDecoder(context, insideList = true) {

    private var index: Int = -1

    override val element: JsonElement
        get() = content[index]

    override val elementPath: RemotePath
        get() = path / RemotePathSegment(index.toString())

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++index < content.size) index else CompositeDecoder.DECODE_DONE
}

private class MapDecoder(
    context: RemoteTreeDecodeContext,
    private val path: RemotePath,
    insideList: Boolean,
    content: JsonObject,
) : TreeDecoder(context, insideList) {

    private val entries: List<Map.Entry<String, JsonElement>> = content.entries.toList()

    private var position: Int = -1

    private val entry: Map.Entry<String, JsonElement>
        get() = entries[position / 2]

    private val decodingKey: Boolean
        get() = position % 2 == 0

    override val element: JsonElement
        get() = if (decodingKey) JsonPrimitive(entry.key) else entry.value

    override val elementPath: RemotePath
        get() {
            check(!decodingKey) { "Map keys at $path must be primitive" }
            return path / RemotePathSegment(entry.key)
        }

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++position < entries.size * 2) position else CompositeDecoder.DECODE_DONE
}
