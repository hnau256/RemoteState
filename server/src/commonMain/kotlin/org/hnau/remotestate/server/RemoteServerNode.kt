package org.hnau.remotestate.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.hnau.commons.kotlin.coroutines.createChild
import org.hnau.remotestate.common.JsonOperation
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteFunctionSerializer
import org.hnau.remotestate.common.RemoteJson
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStateSerializer
import org.hnau.remotestate.common.RemoteTree
import org.hnau.remotestate.common.RemoteTreeEncodeContext
import org.hnau.remotestate.common.diffJson
import org.hnau.remotestate.common.encodeTree
import org.hnau.remotestate.common.find

internal typealias RemoteCallable = suspend (argument: JsonElement) -> JsonElement

private typealias RemoteHandler = (rest: RemotePath) -> RemoteCallable?

internal typealias RemoteUpdateSender = (
    path: RemotePath,
    patch: List<JsonOperation>,
    states: Map<RemotePath, JsonElement>,
) -> Unit

internal class RemoteServerNode<T>(
    private val scope: CoroutineScope,
    private val serializer: KSerializer<T>,
    state: StateFlow<T>,
    private val send: RemoteUpdateSender,
) {

    private class Child(
        val state: RemoteState<*>,
        val node: RemoteServerNode<*>,
    )

    private class Content(
        val json: JsonElement,
        val children: Map<RemotePath, Child>,
        val handlers: RemoteTree<RemoteHandler>,
    )

    private class Encoded(
        val content: Content,
        val created: Map<RemotePath, JsonElement>,
    )

    private var content: Content

    val states: Map<RemotePath, JsonElement>

    init {
        val initialValue = state.value
        val encoded = encode(initialValue, emptyMap())
        content = encoded.content
        states = mapOf(RemotePath.root to encoded.content.json) + encoded.created
        scope.launch {
            var lastValue = initialValue
            state.collect { value ->
                if (value == lastValue) {
                    return@collect
                }
                lastValue = value
                update(value)
            }
        }
    }

    fun resolve(
        path: RemotePath,
    ): RemoteCallable? = content
        .handlers
        .find(path)
        ?.let { (handler, rest) -> handler(rest) }

    private fun update(
        value: T,
    ) {
        val previous = content
        val encoded = encode(value, previous.children)
        content = encoded.content
        val alive = encoded.content.children.values.map(Child::node).toSet()
        previous.children.values
            .map(Child::node)
            .filter { it !in alive }
            .forEach { it.scope.cancel() }
        val patch = diffJson(previous.json, encoded.content.json)
        if (patch.isNotEmpty() || encoded.created.isNotEmpty()) {
            send(RemotePath.root, patch, encoded.created)
        }
    }

    private fun encode(
        value: T,
        previous: Map<RemotePath, Child>,
    ): Encoded {
        val children = LinkedHashMap<RemotePath, Child>()
        val created = LinkedHashMap<RemotePath, JsonElement>()
        val handlers = LinkedHashMap<RemotePath, RemoteHandler>()
        val context = object : RemoteTreeEncodeContext {

            override fun encodeState(
                path: RemotePath,
                serializer: RemoteStateSerializer<*>,
                state: RemoteState<*>,
            ): JsonElement {
                val child = previous[path]
                    ?.takeIf { it.state === state }
                    ?: createChild(path, serializer, state).also { child ->
                        child.node.states.forEach { (childPath, json) -> created[path + childPath] = json }
                    }
                children[path] = child
                handlers[path] = child.node::resolve
                return JsonObject(emptyMap())
            }

            override fun encodeFunction(
                path: RemotePath,
                serializer: RemoteFunctionSerializer<*, *>,
                function: RemoteFunction<*, *>,
            ): JsonElement {
                handlers[path] = createHandler(serializer, function)
                return JsonObject(emptyMap())
            }
        }
        val json = context.encodeTree(serializer, value)
        return Encoded(
            content = Content(
                json = json,
                children = children,
                handlers = RemoteTree.build(handlers),
            ),
            created = created,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun createChild(
        path: RemotePath,
        serializer: RemoteStateSerializer<*>,
        state: RemoteState<*>,
    ): Child = Child(
        state = state,
        node = RemoteServerNode(
            scope = scope.createChild(),
            serializer = serializer.valueSerializer as KSerializer<Any?>,
            state = state.state,
            send = { childPath, patch, states -> send(path + childPath, patch, states) },
        ),
    )
}

@Suppress("UNCHECKED_CAST")
private fun createHandler(
    serializer: RemoteFunctionSerializer<*, *>,
    function: RemoteFunction<*, *>,
): RemoteHandler {
    serializer as RemoteFunctionSerializer<Any?, Any?>
    function as RemoteFunction<Any?, Any?>
    val callable: RemoteCallable = { argument ->
        RemoteJson.encodeToJsonElement(
            serializer = serializer.resultSerializer,
            value = function(RemoteJson.decodeFromJsonElement(serializer.argumentSerializer, argument)),
        )
    }
    return { rest -> callable.takeIf { rest == RemotePath.root } }
}
