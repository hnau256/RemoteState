@file:OptIn(ExperimentalSerializationApi::class)

package org.hnau.remotestate.common

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

@RemoteStateInternalApi
interface RemoteTreeDecodeContext {

    fun decodeState(
        patcher: RemoteStatePatcher<*, *, *>,
        element: JsonElement,
    ): RemoteState<*>

    fun decodeFunction(
        serializer: RemoteFunctionSerializer<*, *>,
    ): RemoteFunction<*, *>
}

@RemoteStateInternalApi
fun <T> RemoteTreeDecodeContext.decodeTree(
    deserializer: DeserializationStrategy<T>,
    element: JsonElement,
): T = RootDecoder(
    context = this,
    element = element,
).decodeSerializableValue(deserializer)

private abstract class TreeDecoder(
    protected val context: RemoteTreeDecodeContext,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule = RemoteJson.serializersModule

    protected abstract val element: JsonElement

    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T = when (deserializer) {
        is RemoteStatePatcher<*, *, *> -> context.decodeState(
            patcher = deserializer,
            element = element,
        ) as T

        is RemoteFunctionSerializer<*, *> -> context.decodeFunction(
            serializer = deserializer,
        ) as T

        else -> deserializer.deserialize(this)
    }

    private fun content(): String = (element as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.content
        ?: error("Expected primitive, got $element")

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
            ?: error("Unknown ${enumDescriptor.serialName} value '$name'")
    }

    override fun beginStructure(
        descriptor: SerialDescriptor,
    ): CompositeDecoder = when (descriptor.kind) {
        StructureKind.LIST -> ListDecoder(context, expect<JsonArray>())
        StructureKind.MAP -> MapDecoder(context, expect<JsonObject>())
        else -> ObjectDecoder(context, expect<JsonObject>(), descriptor)
    }

    private inline fun <reified E : JsonElement> expect(): E = element as? E
        ?: error("Expected ${E::class.simpleName}, got $element")
}

private class RootDecoder(
    context: RemoteTreeDecodeContext,
    override val element: JsonElement,
) : TreeDecoder(context) {

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = error("Root decoder has no elements")
}

private class ObjectDecoder(
    context: RemoteTreeDecodeContext,
    private val content: JsonObject,
    private val descriptor: SerialDescriptor,
) : TreeDecoder(context) {

    private var index: Int = -1

    private val name: String
        get() = descriptor.getElementName(index)

    override val element: JsonElement
        get() = content.getValue(name)

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
    private val content: JsonArray,
) : TreeDecoder(context) {

    private var index: Int = -1

    override val element: JsonElement
        get() = content[index]

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++index < content.size) index else CompositeDecoder.DECODE_DONE
}

private class MapDecoder(
    context: RemoteTreeDecodeContext,
    content: JsonObject,
) : TreeDecoder(context) {

    private val entries: List<Map.Entry<String, JsonElement>> = content.entries.toList()

    private var position: Int = -1

    private val entry: Map.Entry<String, JsonElement>
        get() = entries[position / 2]

    override val element: JsonElement
        get() = if (position % 2 == 0) JsonPrimitive(entry.key) else entry.value

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++position < entries.size * 2) position else CompositeDecoder.DECODE_DONE
}
