package org.hnau.remotestate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import org.hnau.commons.kotlin.coroutines.createChild

internal typealias RemoteCallable = suspend (argument: JsonElement) -> JsonElement

private typealias RemoteHandler = (rest: RemotePath) -> RemoteCallable?

internal class RemoteServerNode<T, P>(
    private val scope: CoroutineScope,
    private val patcher: RemoteStatePatcher<*, T, P>,
    state: StateFlow<T>,
    initial: T,
    known: Map<RemoteState<*>, Any?>,
    private val send: (path: RemotePath, patch: JsonElement) -> Unit,
) {

    private var base: T = initial

    private var children: Map<RemoteState<*>, RemoteServerNode<*, *>> = emptyMap()

    private var paths: Map<RemoteState<*>, RemotePath> = emptyMap()

    private var handlers: RemoteTree<RemoteHandler> = RemoteTree.Branch(emptyMap())

    init {
        reconcile(initial, known)
        scope.launch {
            state.collect { value ->
                if (value == base) {
                    return@collect
                }
                val encoded = encodeRemoteValue(patcher.patchSerializer, patcher.diff(base, value))
                base = value
                send(RemotePath.root, encoded.element)
                reconcile(value, encoded.states)
            }
        }
    }

    fun resolve(
        path: RemotePath,
    ): RemoteCallable? = handlers
        .find(path)
        ?.let { (handler, rest) -> handler(rest) }

    private fun reset(
        value: T,
        known: Map<RemoteState<*>, Any?>,
    ) {
        base = value
        reconcile(value, known)
    }

    private fun cancel() {
        scope.cancel()
    }

    private fun reconcile(
        value: T,
        known: Map<RemoteState<*>, Any?>,
    ) {
        val walk = walkTree(patcher.valueSerializer, value)
        val newChildren = walk.states.values.associate { entry ->
            val child = children[entry.state]
                ?.also { child -> child.resetIfKnown(entry.state, known) }
                ?: createChild(entry, known)
            entry.state to child
        }
        children.forEach { (state, child) ->
            if (state !in newChildren) {
                child.cancel()
            }
        }
        children = newChildren
        paths = walk.states.entries.associate { (path, entry) -> entry.state to path }
        handlers = RemoteTree.build<RemoteHandler>(
            walk.functions.mapValues { (_, entry) -> entry.toHandler() } +
                    walk.states.mapValues { (_, entry) -> newChildren.getValue(entry.state).let { child -> child::resolve } },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetIfKnown(
        state: RemoteState<*>,
        known: Map<RemoteState<*>, Any?>,
    ) {
        if (known.containsKey(state)) {
            reset(known[state] as T, known)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createChild(
        entry: RemoteStateEntry,
        known: Map<RemoteState<*>, Any?>,
    ): RemoteServerNode<*, *> = createChild(
        patcher = entry.patcher as RemoteStatePatcher<*, Any?, Any?>,
        state = entry.state as RemoteState<Any?>,
        known = known,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <CT, CP> createChild(
        patcher: RemoteStatePatcher<*, CT, CP>,
        state: RemoteState<CT>,
        known: Map<RemoteState<*>, Any?>,
    ): RemoteServerNode<CT, CP> = RemoteServerNode(
        scope = scope.createChild(),
        patcher = patcher,
        state = state.state,
        initial = if (known.containsKey(state)) known[state] as CT else state.state.value,
        known = known,
        send = { path, patch -> paths[state]?.let { statePath -> send(statePath + path, patch) } },
    )
}

@Suppress("UNCHECKED_CAST")
private fun RemoteFunctionEntry.toHandler(): RemoteHandler {
    val serializer = serializer as RemoteFunctionSerializer<Any?, Any?>
    val function = function as RemoteFunction<Any?, Any?>
    val callable: RemoteCallable = { argument ->
        RemoteJson.encodeToJsonElement(
            serializer = serializer.resultSerializer,
            value = function(RemoteJson.decodeFromJsonElement(serializer.argumentSerializer, argument)),
        )
    }
    return { rest -> callable.takeIf { rest == RemotePath.root } }
}
