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
class DecodedTree(
    val json: JsonElement,
    val value: Any?,
    val children: Map<String, DecodedTree>,
    val attachment: Any?,
)

@RemoteStateInternalApi
class DecodedState(
    val state: RemoteState<*>,
    val attachment: Any?,
)

@RemoteStateInternalApi
interface RemoteTreeDecodeContext {

    fun decodeState(
        path: RemotePath,
        serializer: RemoteStateSerializer<*>,
        previous: DecodedTree?,
    ): DecodedState

    fun decodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<*, *>,
    ): RemoteFunction<*, *>
}

@RemoteStateInternalApi
fun <T> RemoteTreeDecodeContext.decodeTree(
    deserializer: DeserializationStrategy<T>,
    element: JsonElement,
    previous: DecodedTree?,
): DecodedTree {
    var result: DecodedTree? = null
    RootDecoder(
        context = this,
        element = element,
        previousElement = previous,
        onDecoded = { result = it },
    ).decodeSerializableValue(deserializer)
    return result ?: error("Nothing was decoded")
}

private abstract class TreeDecoder(
    protected val context: RemoteTreeDecodeContext,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule = RemoteJson.serializersModule

    protected abstract val element: JsonElement

    protected abstract val elementPath: List<String>?

    protected abstract val previousElement: DecodedTree?

    protected abstract fun onElementDecoded(
        tree: DecodedTree,
    )

    private var structureChildren: Map<String, DecodedTree> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T {
        val element = element
        val previous = previousElement
        val tree = previous
            ?.takeIf { it.json === element }
            ?: decodeElement(element, previous, deserializer)
        onElementDecoded(tree)
        return tree.value as T
    }

    private fun decodeElement(
        element: JsonElement,
        previous: DecodedTree?,
        deserializer: DeserializationStrategy<*>,
    ): DecodedTree = when (deserializer) {
        is RemoteStateSerializer<*> -> context
            .decodeState(remotePath(), deserializer, previous)
            .let { decoded -> DecodedTree(element, decoded.state, emptyMap(), decoded.attachment) }

        is RemoteFunctionSerializer<*, *> -> DecodedTree(
            json = element,
            value = context.decodeFunction(remotePath(), deserializer),
            children = emptyMap(),
            attachment = null,
        )

        else -> {
            structureChildren = emptyMap()
            val value = deserializer.deserialize(this)
            DecodedTree(element, value, structureChildren, null)
        }
    }

    private fun remotePath(): RemotePath = RemotePath(
        segments = (elementPath ?: error("RemoteState and RemoteFunction can not be map keys"))
            .map(::RemotePathSegment),
    )

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
    ): CompositeDecoder {
        val structurePath = elementPath ?: error("Map keys must be primitive")
        val previousChildren = previousElement?.children.orEmpty()
        val onEnd: (Map<String, DecodedTree>) -> Unit = { children -> structureChildren = children }
        return when (descriptor.kind) {
            StructureKind.LIST -> ListDecoder(context, structurePath, expect<JsonArray>(), previousChildren, onEnd)
            StructureKind.MAP -> MapDecoder(context, structurePath, expect<JsonObject>(), previousChildren, onEnd)
            else -> ObjectDecoder(context, structurePath, expect<JsonObject>(), descriptor, previousChildren, onEnd)
        }
    }

    private inline fun <reified E : JsonElement> expect(): E = element as? E
        ?: error("Expected ${E::class.simpleName}, got $element")
}

private class RootDecoder(
    context: RemoteTreeDecodeContext,
    override val element: JsonElement,
    override val previousElement: DecodedTree?,
    private val onDecoded: (DecodedTree) -> Unit,
) : TreeDecoder(context) {

    override val elementPath: List<String> = emptyList()

    override fun onElementDecoded(
        tree: DecodedTree,
    ) {
        onDecoded(tree)
    }

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = error("Root decoder has no elements")
}

private abstract class StructureDecoder(
    context: RemoteTreeDecodeContext,
    private val path: List<String>,
    private val previousChildren: Map<String, DecodedTree>,
    private val onEnd: (Map<String, DecodedTree>) -> Unit,
) : TreeDecoder(context) {

    private val children: MutableMap<String, DecodedTree> = LinkedHashMap()

    protected abstract val elementKey: String?

    override val elementPath: List<String>?
        get() = elementKey?.let { key -> path + key }

    override val previousElement: DecodedTree?
        get() = elementKey?.let(previousChildren::get)

    override fun onElementDecoded(
        tree: DecodedTree,
    ) {
        elementKey?.let { key -> children[key] = tree }
    }

    override fun endStructure(
        descriptor: SerialDescriptor,
    ) {
        onEnd(children)
    }
}

private class ObjectDecoder(
    context: RemoteTreeDecodeContext,
    path: List<String>,
    private val content: JsonObject,
    private val descriptor: SerialDescriptor,
    previousChildren: Map<String, DecodedTree>,
    onEnd: (Map<String, DecodedTree>) -> Unit,
) : StructureDecoder(context, path, previousChildren, onEnd) {

    private var index: Int = -1

    override val elementKey: String
        get() = descriptor.getElementName(index)

    override val element: JsonElement
        get() = content.getValue(elementKey)

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int {
        while (++index < this.descriptor.elementsCount) {
            if (elementKey in content) {
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }
}

private class ListDecoder(
    context: RemoteTreeDecodeContext,
    path: List<String>,
    private val content: JsonArray,
    previousChildren: Map<String, DecodedTree>,
    onEnd: (Map<String, DecodedTree>) -> Unit,
) : StructureDecoder(context, path, previousChildren, onEnd) {

    private var index: Int = -1

    override val elementKey: String
        get() = index.toString()

    override val element: JsonElement
        get() = content[index]

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++index < content.size) index else CompositeDecoder.DECODE_DONE
}

private class MapDecoder(
    context: RemoteTreeDecodeContext,
    path: List<String>,
    content: JsonObject,
    previousChildren: Map<String, DecodedTree>,
    onEnd: (Map<String, DecodedTree>) -> Unit,
) : StructureDecoder(context, path, previousChildren, onEnd) {

    private val entries: List<Map.Entry<String, JsonElement>> = content.entries.toList()

    private var position: Int = -1

    private val entry: Map.Entry<String, JsonElement>
        get() = entries[position / 2]

    private val decodingKey: Boolean
        get() = position % 2 == 0

    override val elementKey: String?
        get() = if (decodingKey) null else entry.key

    override val element: JsonElement
        get() = if (decodingKey) JsonPrimitive(entry.key) else entry.value

    override fun decodeElementIndex(
        descriptor: SerialDescriptor,
    ): Int = if (++position < entries.size * 2) position else CompositeDecoder.DECODE_DONE
}
