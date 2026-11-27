package org.hnau.remotestate.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hnau.commons.kotlin.coroutines.createChild
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.commons.kotlin.coroutines.flow.state.runningFoldState
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

internal typealias RemoteCallable = suspend (argument: JsonElement) -> JsonElement

internal typealias RemoteHandler = (path: RemotePath) -> RemoteCallable?

internal typealias RemoteSender = (path: RemotePath, value: JsonElement?) -> Unit

internal class ServedState(
    val initial: JsonElement,
    val handler: RemoteHandler,
)

private class Entry(
    val scope: CoroutineScope,
    val value: Any?,
    val element: JsonElement,
    val handlers: RemoteTree<RemoteHandler>,
)

private class Entries<K>(
    val byKey: Map<K, Entry>,
    val tree: RemoteTree<Entry>,
)

internal fun <T> serveRemoteState(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    state: StateFlow<T>,
    send: RemoteSender,
): ServedState {
    val entries = serveEntries(
        scope = scope,
        serializer = serializer,
        state = state.mapState(scope) { value -> mapOf(Unit to value) },
        entryPath = { RemotePath.root },
        send = send,
    )
    return ServedState(
        initial = entries.value.byKey.getValue(Unit).element,
        handler = entries.handler(),
    )
}

internal fun <K, V> serveRemoteMap(
    scope: CoroutineScope,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
    state: StateFlow<Map<K, V>>,
    send: RemoteSender,
): ServedState {
    val keySegment = { key: K -> encodeKey(keySerializer, key) }
    val entries = serveEntries(
        scope = scope,
        serializer = valueSerializer,
        state = state,
        entryPath = { key -> RemotePath.root / keySegment(key) },
        send = send,
    )
    return ServedState(
        initial = JsonObject(
            entries.value.byKey.entries.associate { (key, entry) -> keySegment(key).value to entry.element }
        ),
        handler = entries.handler(),
    )
}

private fun <K> encodeKey(
    serializer: KSerializer<K>,
    key: K,
): RemotePathSegment = RemotePathSegment(
    value = (RemoteJson.encodeToJsonElement(serializer, key) as? JsonPrimitive)
        ?.content
        ?: error("Keys of RemoteMap must be primitive: $key"),
)

private fun StateFlow<Entries<*>>.handler(): RemoteHandler = { path ->
    value
        .tree
        .find(path)
        ?.let { (entry, rest) -> entry.handlers.resolve(rest) }
}

private fun RemoteTree<RemoteHandler>.resolve(
    path: RemotePath,
): RemoteCallable? = find(path)?.let { (handler, rest) -> handler(rest) }

private fun <K, V> serveEntries(
    scope: CoroutineScope,
    serializer: KSerializer<V>,
    state: StateFlow<Map<K, V>>,
    entryPath: (K) -> RemotePath,
    send: RemoteSender,
): StateFlow<Entries<K>> {
    val encode = { key: K, value: V ->
        val path = entryPath(key)
        encodeEntry(
            scope = scope,
            serializer = serializer,
            value = value,
            send = { childPath, childValue -> send(path + childPath, childValue) },
        )
    }
    return state.runningFoldState(
        scope = scope,
        createInitial = { values ->
            entriesOf(values.mapValues { (key, value) -> encode(key, value) }, entryPath)
        },
        operation = { previous, values ->
            previous.byKey
                .filterKeys { it !in values }
                .forEach { (key, entry) ->
                    entry.scope.cancel()
                    send(entryPath(key), null)
                }
            val byKey = values.mapValues { (key, value) ->
                val old = previous.byKey[key]
                old
                    ?.takeIf { it.value === value || it.value == value }
                    ?: encode(key, value).also { entry ->
                        old?.scope?.cancel()
                        send(entryPath(key), entry.element)
                    }
            }
            entriesOf(byKey, entryPath)
        },
    )
}

private fun <K> entriesOf(
    byKey: Map<K, Entry>,
    entryPath: (K) -> RemotePath,
): Entries<K> = Entries(
    byKey = byKey,
    tree = RemoteTree.build(byKey.entries.associate { (key, entry) -> entryPath(key) to entry }),
)

@Suppress("UNCHECKED_CAST")
private fun <V> encodeEntry(
    scope: CoroutineScope,
    serializer: KSerializer<V>,
    value: V,
    send: RemoteSender,
): Entry {
    val entryScope = scope.createChild()
    val handlers = HashMap<RemotePath, RemoteHandler>()
    val context = object : RemoteTreeEncodeContext {

        override fun <S> encodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<S>,
            state: RemoteState<S>,
        ): JsonElement = serveRemoteState(
            scope = entryScope,
            serializer = serializer.valueSerializer,
            state = state.state,
            send = { childPath, childValue -> send(path + childPath, childValue) },
        ).register(path, handlers)

        override fun encodeMap(
            path: RemotePath,
            serializer: RemoteMapSerializer<*, *>,
            map: RemoteMap<*, *>,
        ): JsonElement = serveRemoteMap(
            scope = entryScope,
            keySerializer = serializer.keySerializer as KSerializer<Any?>,
            valueSerializer = serializer.valueSerializer as KSerializer<Any?>,
            state = map.state as StateFlow<Map<Any?, Any?>>,
            send = { childPath, childValue -> send(path + childPath, childValue) },
        ).register(path, handlers)

        override fun <A, R> encodeFunction(
            path: RemotePath,
            serializer: RemoteFunctionSerializer<A, R>,
            function: RemoteFunction<A, R>,
        ): JsonElement {
            val callable: RemoteCallable = { argument ->
                RemoteJson.encodeToJsonElement(
                    serializer = serializer.resultSerializer,
                    value = function(RemoteJson.decodeFromJsonElement(serializer.argumentSerializer, argument)),
                )
            }
            handlers[path] = { rest -> callable.takeIf { rest == RemotePath.root } }
            return JsonObject(emptyMap())
        }
    }
    val element = context.encodeTree(serializer, value)
    return Entry(
        scope = entryScope,
        value = value,
        element = element,
        handlers = RemoteTree.build(handlers),
    )
}

private fun ServedState.register(
    path: RemotePath,
    handlers: MutableMap<RemotePath, RemoteHandler>,
): JsonElement {
    handlers[path] = handler
    return initial
}
