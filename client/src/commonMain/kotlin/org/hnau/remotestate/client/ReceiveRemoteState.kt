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
import org.hnau.commons.kotlin.coroutines.createChild
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteFunctionSerializer
import org.hnau.remotestate.common.RemoteJson
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStateSerializer
import org.hnau.remotestate.common.RemoteTree
import org.hnau.remotestate.common.find

internal typealias RemoteCaller = suspend (path: RemotePath, argument: JsonElement) -> JsonElement

internal typealias RemoteUpdate = Pair<RemotePath, JsonElement>

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
): StateFlow<T> {
    val initialContent = decodeContent(scope, serializer, initial, call)
    return updates
        .consumeAsFlow()
        .runningFold(initialContent) { content, (path, element) ->
            when (path) {
                RemotePath.root -> {
                    content.scope.cancel()
                    decodeContent(scope, serializer, element, call)
                }

                else -> content.also { it.route(path, element) }
            }
        }
        .map { content -> content.value }
        .stateIn(scope, SharingStarted.Eagerly, initialContent.value)
}

private fun Content<*>.route(
    path: RemotePath,
    element: JsonElement,
) {
    tree
        .find(path)
        ?.let { (child, rest) -> child.trySend(rest to element) }
}

private fun <T> decodeContent(
    scope: CoroutineScope,
    serializer: KSerializer<T>,
    element: JsonElement,
    call: RemoteCaller,
): Content<T> {
    val contentScope = scope.createChild()
    val children = HashMap<RemotePath, SendChannel<RemoteUpdate>>()
    val context = object : RemoteTreeDecodeContext {

        override fun <S> decodeState(
            path: RemotePath,
            serializer: RemoteStateSerializer<S>,
            element: JsonElement,
        ): RemoteState<S> {
            val childUpdates = Channel<RemoteUpdate>(Channel.UNLIMITED)
            children[path] = childUpdates
            val childState = receiveRemoteState(
                scope = contentScope,
                serializer = serializer.valueSerializer,
                initial = element,
                updates = childUpdates,
                call = { childPath, argument -> call(path + childPath, argument) },
            )
            return RemoteState(childState)
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
