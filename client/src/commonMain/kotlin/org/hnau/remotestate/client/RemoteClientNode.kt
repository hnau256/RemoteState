package org.hnau.remotestate.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.hnau.commons.kotlin.coroutines.createChild
import org.hnau.remotestate.common.DecodedState
import org.hnau.remotestate.common.DecodedTree
import org.hnau.remotestate.common.JsonOperation
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteFunctionSerializer
import org.hnau.remotestate.common.RemoteJson
import org.hnau.remotestate.common.RemotePath
import org.hnau.remotestate.common.RemoteState
import org.hnau.remotestate.common.RemoteStateSerializer
import org.hnau.remotestate.common.RemoteTreeDecodeContext
import org.hnau.remotestate.common.applyPatch
import org.hnau.remotestate.common.decodeTree
import org.hnau.remotestate.common.touch

internal typealias RemoteCaller = suspend (path: RemotePath, argument: JsonElement) -> JsonElement

internal class RemoteClientNode<T>(
    private val scope: CoroutineScope,
    private val serializer: KSerializer<T>,
    initial: JsonElement,
    states: Map<RemotePath, JsonElement>,
    private val call: RemoteCaller,
) {

    private var tree: DecodedTree = decode(initial, states, previous = null)

    private val mutableState: MutableStateFlow<T> = MutableStateFlow(currentValue())

    val state: StateFlow<T> = mutableState.asStateFlow()

    fun update(
        path: RemotePath,
        patch: List<JsonOperation>,
        states: Map<RemotePath, JsonElement>,
    ) {
        if (path != RemotePath.root) {
            tree
                .findChild(path)
                ?.let { (child, rest) -> child.update(rest, patch, states) }
            return
        }
        val previous = tree
        val json = states.keys.fold(previous.json.applyPatch(patch)) { json, statePath -> json.touch(statePath) }
        tree = decode(json, states, previous)
        cancelRemoved(previous, tree)
        mutableState.value = currentValue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentValue(): T = tree.value as T

    private fun decode(
        json: JsonElement,
        states: Map<RemotePath, JsonElement>,
        previous: DecodedTree?,
    ): DecodedTree {
        val context = object : RemoteTreeDecodeContext {

            override fun decodeState(
                path: RemotePath,
                serializer: RemoteStateSerializer<*>,
                previous: DecodedTree?,
            ): DecodedState = when (val childJson = states[path]) {
                null -> previous
                    ?.let { DecodedState(it.value as RemoteState<*>, it.attachment) }
                    ?: error("No state was received for $path")

                else -> createChild(path, serializer, childJson, states)
            }

            override fun decodeFunction(
                path: RemotePath,
                serializer: RemoteFunctionSerializer<*, *>,
            ): RemoteFunction<*, *> = createFunction(path, serializer)
        }
        return context.decodeTree(serializer, json, previous)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createChild(
        path: RemotePath,
        serializer: RemoteStateSerializer<*>,
        json: JsonElement,
        states: Map<RemotePath, JsonElement>,
    ): DecodedState {
        val node = RemoteClientNode(
            scope = scope.createChild(),
            serializer = serializer.valueSerializer as KSerializer<Any?>,
            initial = json,
            states = states
                .filterKeys { it != path && it.startsWith(path) }
                .mapKeys { (childPath) -> childPath.removePrefix(path) },
            call = { childPath, argument -> call(path + childPath, argument) },
        )
        return DecodedState(
            state = RemoteState(node.state),
            attachment = node,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun createFunction(
        path: RemotePath,
        serializer: RemoteFunctionSerializer<*, *>,
    ): RemoteFunction<*, *> {
        serializer as RemoteFunctionSerializer<Any?, Any?>
        return RemoteFunction<Any?, Any?> { argument ->
            RemoteJson.decodeFromJsonElement(
                deserializer = serializer.resultSerializer,
                element = call(path, RemoteJson.encodeToJsonElement(serializer.argumentSerializer, argument)),
            )
        }
    }

    private fun cancel() {
        scope.cancel()
    }

    private companion object {

        fun DecodedTree.findChild(
            path: RemotePath,
        ): Pair<RemoteClientNode<*>, RemotePath>? = when (val node = attachment) {
            is RemoteClientNode<*> -> node to path
            else -> path
                .split()
                ?.let { (head, tail) -> children[head.value]?.findChild(tail) }
        }

        fun cancelRemoved(
            previous: DecodedTree,
            current: DecodedTree?,
        ) {
            if (previous === current) {
                return
            }
            (previous.attachment as? RemoteClientNode<*>)
                ?.takeIf { it !== current?.attachment }
                ?.cancel()
            previous.children.forEach { (key, child) -> cancelRemoved(child, current?.children?.get(key)) }
        }
    }
}
