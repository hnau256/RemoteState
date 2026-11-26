package org.hnau.remotestate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.hnau.commons.kotlin.coroutines.createChild
import kotlin.concurrent.Volatile

internal typealias RemoteCaller = suspend (path: RemotePath, argument: JsonElement) -> JsonElement

internal class RemoteClientNode<T, P>(
    private val scope: CoroutineScope,
    private val patcher: RemoteStatePatcher<*, T, P>,
    initial: JsonElement,
    private val call: RemoteCaller,
) {

    private class Registry(
        val children: Map<RemoteState<*>, RemoteClientNode<*, *>>,
        val routes: RemoteTree<RemoteClientNode<*, *>>,
        val childrenPaths: Map<RemoteClientNode<*, *>, RemotePath>,
        val functionsPaths: Map<RemoteFunction<*, *>, RemotePath>,
    )

    private class Decoded<V>(
        val value: V,
        val children: Map<RemoteState<*>, RemoteClientNode<*, *>>,
    )

    val updates: Channel<Pair<RemotePath, JsonElement>> = Channel(Channel.UNLIMITED)

    @Volatile
    private var registry: Registry = Registry(
        children = emptyMap(),
        routes = RemoteTree.Branch(emptyMap()),
        childrenPaths = emptyMap(),
        functionsPaths = emptyMap(),
    )

    private val mutableState: MutableStateFlow<T>

    val state: StateFlow<T>

    init {
        val decoded = decode(patcher.valueSerializer, initial)
        reconcile(decoded.value, decoded.children)
        mutableState = MutableStateFlow(decoded.value)
        state = mutableState.asStateFlow()
        scope.launch {
            for ((path, element) in updates) {
                handle(path, element)
            }
        }
    }

    private fun handle(
        path: RemotePath,
        element: JsonElement,
    ) {
        if (path == RemotePath.root) {
            val decoded = decode(patcher.patchSerializer, element)
            val value = patcher.apply(mutableState.value, decoded.value)
            reconcile(value, decoded.children)
            mutableState.value = value
            return
        }
        registry
            .routes
            .find(path)
            ?.let { (child, rest) -> child.updates.trySend(rest to element) }
    }

    private fun reconcile(
        value: T,
        created: Map<RemoteState<*>, RemoteClientNode<*, *>>,
    ) {
        val walk = walkTree(patcher.valueSerializer, value)
        val previous = registry.children
        val children = walk.states.values.associate { entry ->
            val child = previous[entry.state]
                ?: created[entry.state]
                ?: error("RemoteState at ${walk.states.entries.first { it.value === entry }.key} was not received from server")
            entry.state to child
        }
        val alive = children.values.toSet()
        (previous.values + created.values)
            .filter { it !in alive }
            .forEach(RemoteClientNode<*, *>::cancel)
        registry = Registry(
            children = children,
            routes = RemoteTree.build(walk.states.mapValues { (_, entry) -> children.getValue(entry.state) }),
            childrenPaths = walk.states.entries.associate { (path, entry) -> children.getValue(entry.state) to path },
            functionsPaths = walk.functions.entries.associate { (path, entry) -> entry.function to path },
        )
    }

    private fun cancel() {
        scope.cancel()
    }

    private suspend fun callFrom(
        child: RemoteClientNode<*, *>,
        path: RemotePath,
        argument: JsonElement,
    ): JsonElement = registry
        .childrenPaths[child]
        ?.let { childPath -> call(childPath + path, argument) }
        ?: throw RemoteCallException(CallError.Outdated)

    private fun <V> decode(
        deserializer: DeserializationStrategy<V>,
        element: JsonElement,
    ): Decoded<V> {
        val children = HashMap<RemoteState<*>, RemoteClientNode<*, *>>()
        val context = object : RemoteTreeDecodeContext {

            @Suppress("UNCHECKED_CAST")
            override fun decodeState(
                patcher: RemoteStatePatcher<*, *, *>,
                element: JsonElement,
            ): RemoteState<*> = createChild(
                patcher = patcher as RemoteStatePatcher<*, Any?, Any?>,
                element = element,
            ).also { (state, child) -> children[state] = child }.first

            @Suppress("UNCHECKED_CAST")
            override fun decodeFunction(
                serializer: RemoteFunctionSerializer<*, *>,
            ): RemoteFunction<*, *> = createFunction(
                serializer = serializer as RemoteFunctionSerializer<Any?, Any?>,
            )
        }
        return Decoded(
            value = context.decodeTree(deserializer, element),
            children = children,
        )
    }

    private fun <CT, CP> createChild(
        patcher: RemoteStatePatcher<*, CT, CP>,
        element: JsonElement,
    ): Pair<RemoteState<CT>, RemoteClientNode<CT, CP>> {
        lateinit var child: RemoteClientNode<CT, CP>
        child = RemoteClientNode(
            scope = scope.createChild(),
            patcher = patcher,
            initial = element,
            call = { path, argument -> callFrom(child, path, argument) },
        )
        return patcher.create(child.state) to child
    }

    private fun <A, R> createFunction(
        serializer: RemoteFunctionSerializer<A, R>,
    ): RemoteFunction<A, R> = object : RemoteFunction<A, R> {

        override suspend fun invoke(
            argument: A,
        ): R {
            val path = registry.functionsPaths[this] ?: throw RemoteCallException(CallError.Outdated)
            return RemoteJson.decodeFromJsonElement(
                deserializer = serializer.resultSerializer,
                element = call(path, RemoteJson.encodeToJsonElement(serializer.argumentSerializer, argument)),
            )
        }
    }
}
