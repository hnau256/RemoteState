package org.hnau.remotestate

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class RemoteStateEntry(
    val patcher: RemoteStatePatcher<*, *, *>,
    val state: RemoteState<*>,
)

internal class RemoteFunctionEntry(
    val serializer: RemoteFunctionSerializer<*, *>,
    val function: RemoteFunction<*, *>,
)

internal class RemoteTreeWalk(
    val states: Map<RemotePath, RemoteStateEntry>,
    val functions: Map<RemotePath, RemoteFunctionEntry>,
)

internal fun <T> walkTree(
    serializer: SerializationStrategy<T>,
    value: T,
): RemoteTreeWalk {
    val states = LinkedHashMap<RemotePath, RemoteStateEntry>()
    val functions = LinkedHashMap<RemotePath, RemoteFunctionEntry>()
    val context = object : RemoteTreeEncodeContext {

        override fun encodeState(
            path: RemotePath,
            patcher: RemoteStatePatcher<*, *, *>,
            state: RemoteState<*>,
        ): JsonElement {
            states[path] = RemoteStateEntry(patcher, state)
            return JsonNull
        }

        override fun encodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<*, *>,
            function: RemoteFunction<*, *>,
        ): JsonElement {
            functions[path] = RemoteFunctionEntry(serializer, function)
            return JsonNull
        }
    }
    context.encodeTree(serializer, value)
    val instances = states.values.map(RemoteStateEntry::state)
    check(instances.toSet().size == instances.size) { "The same RemoteState instance is placed twice" }
    return RemoteTreeWalk(
        states = states,
        functions = functions,
    )
}

internal class EncodedRemoteValue(
    val element: JsonElement,
    val states: Map<RemoteState<*>, Any?>,
)

internal fun <T> encodeRemoteValue(
    serializer: SerializationStrategy<T>,
    value: T,
): EncodedRemoteValue {
    val states = HashMap<RemoteState<*>, Any?>()
    val context = object : RemoteTreeEncodeContext {

        override fun encodeState(
            path: RemotePath,
            patcher: RemoteStatePatcher<*, *, *>,
            state: RemoteState<*>,
        ): JsonElement {
            @Suppress("UNCHECKED_CAST")
            patcher as RemoteStatePatcher<*, Any?, *>
            val stateValue = state.state.value
            states[state] = stateValue
            return encodeTree(patcher.valueSerializer, stateValue)
        }

        override fun encodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<*, *>,
            function: RemoteFunction<*, *>,
        ): JsonElement = JsonObject(emptyMap())
    }
    return EncodedRemoteValue(
        element = context.encodeTree(serializer, value),
        states = states,
    )
}
