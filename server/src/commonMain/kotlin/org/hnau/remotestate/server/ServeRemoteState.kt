package org.hnau.remotestate.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.hnau.commons.kotlin.coroutines.flow.state.OnceCache
import org.hnau.commons.kotlin.coroutines.flow.state.mapWithScope
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.RemoteFunctionSerializer
import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import org.hnau.remotestate.RemoteState
import org.hnau.remotestate.RemoteStateSerializer
import org.hnau.remotestate.RemoteTree
import org.hnau.remotestate.find

internal typealias RemoteCallable = suspend (argument: JsonElement) -> JsonElement

internal typealias RemoteHandler = (path: RemotePath) -> RemoteCallable?

internal class ServedState(
    val initial: JsonElement,
    val handler: RemoteHandler,
)

private class Content(
    val tree: RemoteTree<RemoteHandler>,
    val element: JsonElement,
)

internal fun <T> serveRemoteState(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    state: StateFlow<T>,
    sendUpdate: (path: RemotePath, value: JsonElement) -> Unit,
): ServedState {
    val content = state.mapWithScope(scope) { contentScope, value ->
        encodeContent(contentScope, serializer, value, sendUpdate)
    }
    val initialContent = content.value
    val onceCache = OnceCache(initialContent, Unit)
    scope.launch {
        content
            .filter { onceCache.popValueIfKeyIsSameAsInitial(it) == null }
            .collect { sendUpdate(RemotePath.root, it.element) }
    }
    val handler: RemoteHandler = { path ->
        content
            .value
            .tree
            .find(path)
            ?.let { (child, rest) -> child(rest) }
    }
    return ServedState(
        initial = initialContent.element,
        handler = handler,
    )
}

private fun <T> encodeContent(
    contentScope: CoroutineScope,
    serializer: KSerializer<T>,
    value: T,
    sendUpdate: (path: RemotePath, value: JsonElement) -> Unit,
): Content {
    val handlers = HashMap<RemotePath, RemoteHandler>()
    val context = object : RemoteTreeEncodeContext {

        override fun <S> encodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<S>,
            state: RemoteState<S>,
        ): JsonElement {
            val served = serveRemoteState(
                scope = contentScope,
                serializer = serializer.valueSerializer,
                state = state.state,
                sendUpdate = { childPath, childValue -> sendUpdate(path + childPath, childValue) },
            )
            handlers[path] = served.handler
            return served.initial
        }

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
    return Content(
        tree = RemoteTree.build(handlers),
        element = element,
    )
}
