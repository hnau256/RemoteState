package org.hnau.remotestate.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hnau.commons.kotlin.coroutines.createChild
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteFunctionSerializer
import org.hnau.remotestate.common.RemoteJson
import org.hnau.remotestate.common.RemoteMap
import org.hnau.remotestate.common.RemoteMapSerializer
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemotePathSegment
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStateSerializer
import org.hnau.remotestate.common.RemoteTree
import org.hnau.remotestate.common.find

internal typealias RemoteCaller = suspend (path: RemotePath, argument: JsonElement) -> JsonElement

internal class RemoteUpdate(
    val path: RemotePath,
    val value: JsonElement?,
)

private class Content<T>(
    val scope: CoroutineScope,
    val tree: RemoteTree<SendChannel<RemoteUpdate>>,
    val value: T,
)

internal fun <T> receiveRemoteState(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    initial: JsonElement,
    updates: ReceiveChannel<RemoteUpdate>,
    call: RemoteCaller,
): StateFlow<T> = receiveEntries(
    scope = scope,
    serializer = serializer,
    initial = mapOf(Unit to initial),
    updates = updates,
    split = { path -> Unit to path },
    entryPath = { RemotePath.root },
    call = call,
).mapState(scope) { entries -> entries.getValue(Unit) }

@Suppress("UNCHECKED_CAST")
internal fun <K, V> receiveRemoteMap(
    scope: CoroutineScope,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
    initial: JsonObject,
    updates: ReceiveChannel<RemoteUpdate>,
    call: RemoteCaller,
): StateFlow<Map<K, V>> {
    val decodeKey = { segment: String -> RemoteJson.decodeFromJsonElement(keySerializer, JsonPrimitive(segment)) }
    val encodeKey = { key: K ->
        RemotePathSegment((RemoteJson.encodeToJsonElement(keySerializer, key) as JsonPrimitive).content)
    }
    return receiveEntries(
        scope = scope,
        serializer = valueSerializer,
        initial = initial.entries.associate { (key, element) -> decodeKey(key) to element },
        updates = updates,
        split = { path -> path.split()?.let { (head, tail) -> decodeKey(head.value) to tail } },
        entryPath = { key -> RemotePath.root / encodeKey(key) },
        call = call,
    ).mapState(scope) { entries ->
        entries.entries
            .sortedWith { first, second -> (first.key as Comparable<Any?>).compareTo(second.key) }
            .associate { (key, value) -> key to value }
    }
}

private fun <K, V> receiveEntries(
    scope: CoroutineScope,
    serializer: KSerializer<V>,
    initial: Map<K, JsonElement>,
    updates: ReceiveChannel<RemoteUpdate>,
    split: (RemotePath) -> Pair<K, RemotePath>?,
    entryPath: (K) -> RemotePath,
    call: RemoteCaller,
): StateFlow<Map<K, V>> {
    val decode = { key: K, element: JsonElement ->
        val path = entryPath(key)
        decodeContent(scope, serializer, element) { childPath, argument -> call(path + childPath, argument) }
    }
    val initialEntries = initial.mapValues { (key, element) -> decode(key, element) }
    return updates
        .consumeAsFlow()
        .runningFold(initialEntries) { entries, update ->
            val (key, rest) = split(update.path) ?: return@runningFold entries
            if (rest != RemotePath.root) {
                entries[key]?.route(rest, update.value)
                return@runningFold entries
            }
            entries[key]?.scope?.cancel()
            update.value
                ?.let { element -> entries + (key to decode(key, element)) }
                ?: (entries - key)
        }
        .map { entries -> entries.mapValues { (_, content) -> content.value } }
        .stateIn(scope, SharingStarted.Eagerly, initialEntries.mapValues { (_, content) -> content.value })
}

private fun Content<*>.route(
    path: RemotePath,
    value: JsonElement?,
) {
    tree
        .find(path)
        ?.let { (child, rest) -> child.trySend(RemoteUpdate(rest, value)) }
}

@Suppress("UNCHECKED_CAST")
private fun <T> decodeContent(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    element: JsonElement,
    call: RemoteCaller,
): Content<T> {
    val contentScope = scope.createChild()
    val children = HashMap<RemotePath, SendChannel<RemoteUpdate>>()
    val childCall = { path: RemotePath ->
        val caller: RemoteCaller = { childPath, argument -> call(path + childPath, argument) }
        caller
    }
    val context = object : RemoteTreeDecodeContext {

        override fun <S> decodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<S>,
            element: JsonElement,
        ): RemoteState<S> {
            val updates = Channel<RemoteUpdate>(Channel.UNLIMITED)
            children[path] = updates
            return RemoteState(
                receiveRemoteState(
                    scope = contentScope,
                    serializer = serializer.valueSerializer,
                    initial = element,
                    updates = updates,
                    call = childCall(path),
                )
            )
        }

        override fun decodeMap(
            path: RemotePath,
            serializer: RemoteMapSerializer<*, *>,
            element: JsonElement,
        ): RemoteMap<*, *> {
            val updates = Channel<RemoteUpdate>(Channel.UNLIMITED)
            children[path] = updates
            val state = receiveRemoteMap(
                scope = contentScope,
                keySerializer = serializer.keySerializer as KSerializer<Any?>,
                valueSerializer = serializer.valueSerializer as KSerializer<Any?>,
                initial = element as? JsonObject ?: error("Expected object for RemoteMap at $path, got $element"),
                updates = updates,
                call = childCall(path),
            )
            return RemoteMap(state as StateFlow<Map<String, Any?>>)
        }

        override fun <A, R> decodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<A, R>,
        ): RemoteFunction<A, R> = RemoteFunction { argument ->
            RemoteJson.decodeFromJsonElement(
                deserializer = serializer.resultSerializer,
                element = call(
                    path,
                    RemoteJson.encodeToJsonElement(serializer.argumentSerializer, argument),
                ),
            )
        }
    }
    val value = context.decodeTree(serializer, element)
    return Content(
        scope = contentScope,
        tree = RemoteTree.build(children),
        value = value,
    )
}
