@file:OptIn(ExperimentalSerializationApi::class)

package org.hnau.remotestate

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule

internal interface RemoteTreeEncodeContext {

    fun encodeState(
        path: RemotePath,
        patcher: RemoteStatePatcher<*, *, *>,
        state: RemoteState<*>,
    ): JsonElement

    fun encodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<*, *>,
        function: RemoteFunction<*, *>,
    ): JsonElement
}

internal fun <T> RemoteTreeEncodeContext.encodeTree(
    serializer: SerializationStrategy<T>,
    value: T,
): JsonElement {
    var result: JsonElement? = null
    RootEncoder(
        context = this,
        onResult = { result = it },
    ).encodeSerializableValue(serializer, value)
    return result ?: error("Nothing was encoded")
}

private abstract class TreeEncoder(
    protected val context: RemoteTreeEncodeContext,
) : AbstractEncoder() {

    override val serializersModule: SerializersModule = RemoteJson.serializersModule

    protected abstract fun elementPath(): RemotePath

    protected abstract fun put(
        element: JsonElement,
    )

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        when (serializer) {
            is RemoteStatePatcher<*, *, *> -> put(
                context.encodeState(
                    path = elementPath(),
                    patcher = serializer,
                    state = value as RemoteState<*>,
                )
            )

            is RemoteFunctionSerializer<*, *> -> put(
                context.encodeFunction(
                    path = elementPath(),
                    serializer = serializer,
                    function = value as RemoteFunction<*, *>,
                )
            )

            else -> serializer.serialize(this, value)
        }
    }

    override fun encodeNull() = put(JsonNull)

    override fun encodeBoolean(value: Boolean) = put(JsonPrimitive(value))

    override fun encodeByte(value: Byte) = put(JsonPrimitive(value))

    override fun encodeShort(value: Short) = put(JsonPrimitive(value))

    override fun encodeInt(value: Int) = put(JsonPrimitive(value))

    override fun encodeLong(value: Long) = put(JsonPrimitive(value))

    override fun encodeFloat(value: Float) = put(JsonPrimitive(value))

    override fun encodeDouble(value: Double) = put(JsonPrimitive(value))

    override fun encodeChar(value: Char) = put(JsonPrimitive(value.toString()))

    override fun encodeString(value: String) = put(JsonPrimitive(value))

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) = put(JsonPrimitive(enumDescriptor.getElementName(index)))

    override fun beginStructure(
        descriptor: SerialDescriptor,
    ): CompositeEncoder = when (descriptor.kind) {
        StructureKind.LIST -> ListEncoder(context, ::elementPath, ::put)
        StructureKind.MAP -> MapEncoder(context, ::elementPath, ::put)
        else -> ObjectEncoder(context, ::elementPath, ::put)
    }
}

private class RootEncoder(
    context: RemoteTreeEncodeContext,
    private val onResult: (JsonElement) -> Unit,
) : TreeEncoder(context) {

    override fun elementPath(): RemotePath = RemotePath.root

    override fun put(
        element: JsonElement,
    ) {
        onResult(element)
    }
}

private class ObjectEncoder(
    context: RemoteTreeEncodeContext,
    private val path: () -> RemotePath,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context) {

    private val content: MutableMap<String, JsonElement> = LinkedHashMap()

    private var key: String? = null

    private fun key(): String = key ?: error("No element is being encoded")

    override fun elementPath(): RemotePath = path() / RemotePathSegment(key())

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        key = descriptor.getElementName(index)
        return true
    }

    override fun put(
        element: JsonElement,
    ) {
        content[key()] = element
    }

    override fun endStructure(
        descriptor: SerialDescriptor,
    ) {
        onEnd(JsonObject(content))
    }
}

private class ListEncoder(
    context: RemoteTreeEncodeContext,
    private val path: () -> RemotePath,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context) {

    private val content: MutableList<JsonElement> = mutableListOf()

    override fun elementPath(): RemotePath = path() / RemotePathSegment(content.size.toString())

    override fun put(
        element: JsonElement,
    ) {
        content += element
    }

    override fun endStructure(
        descriptor: SerialDescriptor,
    ) {
        onEnd(JsonArray(content))
    }
}

private class MapEncoder(
    context: RemoteTreeEncodeContext,
    private val path: () -> RemotePath,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context) {

    private val content: MutableMap<String, JsonElement> = LinkedHashMap()

    private var encodingKey: Boolean = true

    private var key: String? = null

    private fun key(): String = key ?: error("No map entry is being encoded")

    override fun elementPath(): RemotePath {
        check(!encodingKey) { "Map keys must be primitive" }
        return path() / RemotePathSegment(key())
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        encodingKey = index % 2 == 0
        return true
    }

    override fun put(
        element: JsonElement,
    ) {
        if (encodingKey) {
            key = (element as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
                ?.content
                ?: error("Map keys must be primitive")
        } else {
            content[key()] = element
        }
    }

    override fun endStructure(
        descriptor: SerialDescriptor,
    ) {
        onEnd(JsonObject(content))
    }
}
