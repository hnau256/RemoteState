@file:OptIn(ExperimentalSerializationApi::class)

package org.hnau.remotestate.server

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
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.RemoteStateSerializer

internal interface RemoteTreeEncodeContext {

    fun <T> encodeState(
        path: RemotePath,
        serializer: RemoteStateSerializer<T>,
        state: RemoteState<T>,
    ): JsonElement

    fun <A, R> encodeFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<A, R>,
        function: RemoteFunction<A, R>,
    ): JsonElement
}

internal fun <T> RemoteTreeEncodeContext.encodeTree(
    path: RemotePath,
    serializer: SerializationStrategy<T>,
    value: T,
): JsonElement {
    var result: JsonElement? = null
    RootEncoder(
        context = this,
        path = path,
        onResult = { result = it },
    ).encodeSerializableValue(serializer, value)
    return result ?: error("Nothing was encoded at $path")
}

private abstract class TreeEncoder(
    protected val context: RemoteTreeEncodeContext,
    protected val insideList: Boolean,
) : AbstractEncoder() {

    override val serializersModule: SerializersModule = RemoteJson.serializersModule

    protected abstract val elementPath: RemotePath

    protected abstract fun put(
        element: JsonElement,
    )

    private fun remoteElementPath(): RemotePath = elementPath.also { path ->
        check(!insideList) { "Remote value at $path is inside a list. Use Map instead" }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        when (serializer) {
            is RemoteStateSerializer<*> -> put(
                context.encodeState(
                    path = remoteElementPath(),
                    serializer = serializer as RemoteStateSerializer<Any?>,
                    state = value as RemoteState<Any?>,
                )
            )

            is RemoteFunctionSerializer<*, *> -> put(
                context.encodeFunction(
                    path = remoteElementPath(),
                    serializer = serializer as RemoteFunctionSerializer<Any?, Any?>,
                    function = value as RemoteFunction<Any?, Any?>,
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
        StructureKind.LIST -> ListEncoder(context, elementPath, ::put)
        StructureKind.MAP -> MapEncoder(context, elementPath, insideList, ::put)
        else -> ObjectEncoder(context, elementPath, insideList, ::put)
    }
}

private class RootEncoder(
    context: RemoteTreeEncodeContext,
    path: RemotePath,
    private val onResult: (JsonElement) -> Unit,
) : TreeEncoder(context, insideList = false) {

    override val elementPath: RemotePath = path

    override fun put(
        element: JsonElement,
    ) {
        onResult(element)
    }
}

private class ObjectEncoder(
    context: RemoteTreeEncodeContext,
    private val path: RemotePath,
    insideList: Boolean,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context, insideList) {

    private val content: MutableMap<String, JsonElement> = LinkedHashMap()

    private var key: String? = null

    private fun key(): String = key ?: error("No element is being encoded at $path")

    override val elementPath: RemotePath
        get() = path / key()

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
    private val path: RemotePath,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context, insideList = true) {

    private val content: MutableList<JsonElement> = mutableListOf()

    override val elementPath: RemotePath
        get() = path / content.size.toString()

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
    private val path: RemotePath,
    insideList: Boolean,
    private val onEnd: (JsonElement) -> Unit,
) : TreeEncoder(context, insideList) {

    private val content: MutableMap<String, JsonElement> = LinkedHashMap()

    private var encodingKey: Boolean = true

    private var key: String? = null

    private fun key(): String = key ?: error("No map entry is being encoded at $path")

    override val elementPath: RemotePath
        get() {
            check(!encodingKey) { "Map keys at $path must be primitive" }
            return path / key()
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
                ?: error("Map keys at $path must be primitive")
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
