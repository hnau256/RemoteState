# RemoteState

A Kotlin Multiplatform library for server-driven state over WebSocket.

The server owns the models. It exposes a tree of plain `@Serializable` state classes that contain
observable values (`RemoteState`) and callable actions (`RemoteFunction`). The client receives a mirror
of that tree: values arrive as `StateFlow`s that update when the server changes them, and calling a
function on the client invokes the corresponding lambda on the server.

Neither side writes protocol code. Everything is built on top of the `kotlinx.serialization` plugin.
Every remote value is addressed by its path in the tree, for example `loginState/right/tasks/milk/remove`.

## Modules

| Module   | Contents                                                                                                                |
|----------|-------------------------------------------------------------------------------------------------------------------------|
| `common` | Remote states, `RemoteFunction`, wire protocol and transport-agnostic sessions. Depend on it from the module with your state classes. |
| `server` | `Route.remoteState(...)`: a Ktor WebSocket route that serves the state tree.                                          |
| `client` | `HttpClient.connectRemoteState(...)`: connects, reconnects and exposes the mirrored tree as a `StateFlow`.              |

All modules are multiplatform.

## Defining state

State classes live in shared code and are ordinary `@Serializable` classes:

```kotlin
@Serializable
data class TaskState(
    val title: String,
    val remove: RemoteFunction<Unit, Unit>,
)

@Serializable
data class LoggedState(
    val tasks: KeyedListRemoteState<String, TaskState>,
    val addTask: RemoteFunction<String, Unit>,
    val logout: RemoteFunction<Unit, Unit>,
)

@Serializable
data class AnonymousState(
    val login: RemoteFunction<Pair<String, String>, Boolean>,
)

@Serializable
data class RootState(
    val loginState: SnapshotRemoteState<@Serializable(with = EitherSerializer::class) Either<AnonymousState, LoggedState>>,
)
```

- A remote state wraps a `StateFlow<T>`. Its type says how changes are sent:
  - `SnapshotRemoteState<T>` sends the whole new value every time;
  - `KeyedListRemoteState<K, V>` wraps `StateFlow<List<KeyValue<K, V>>>` and sends only what changed: removed
    keys, added and changed values, and positions of added and moved items. Moves are minimal: the items that
    keep their relative order (the longest increasing subsequence of positions) stay in place. Items are
    addressed by key, so keys must be unique primitives.
- `RemoteFunction<A, R>` is a `suspend (A) -> R`. Use `Pair`/`Triple` for several arguments and `Unit` for none.
  `RemoteFunction<Unit, R>` can be called without arguments: `state.logout()`.
- Any other field is regular serializable data.
- Remote states and functions can be placed anywhere: in objects, maps, lists, sealed classes and inside
  values and patches of other remote states.

### Custom states

A new kind of state is a subclass of `RemoteState` with a `RemoteStatePatcher` as its serializer. The patcher
only describes how to compute a patch and how to apply it:

```kotlin
@Serializable(with = StreamingTextRemoteState.Patcher::class)
class StreamingTextRemoteState(state: StateFlow<String>) : RemoteState<String>(state) {

    object Patcher : RemoteStatePatcher<StreamingTextRemoteState, String, TextPatch>(
        valueSerializer = String.serializer(),
        patchSerializer = TextPatch.serializer(),
        diff = { old, new -> if (new.startsWith(old)) TextPatch.Append(new.removePrefix(old)) else TextPatch.Replace(new) },
        apply = { text, patch -> patch.fold(ifAppend = { text + it }, ifReplace = { it }) },
        create = ::StreamingTextRemoteState,
    )
}
```

`apply(value, diff(value, new))` must be equal to `new`. Generic states are supported as well: a patcher
class with a constructor that takes `KSerializer`s of the type arguments, like `SnapshotRemoteState.Patcher`.

## Server

Build the state from your models and serve it:

```kotlin
class TaskModel(title: String, remove: suspend () -> Unit) {
    val state = TaskState(
        title = title,
        remove = { remove() },
    )
}

class LoggedModel(scope: CoroutineScope, logout: suspend () -> Unit) {

    private val titles = MutableStateFlow(emptyList<String>())

    val state = LoggedState(
        tasks = titles
            .mapListReusable(scope, extractKey = { it }) { _, title ->
                KeyValue(title, TaskModel(title, remove = { titles.update { it - title } }).state)
            }
            .toKeyedListRemoteState(),
        addTask = { title -> titles.update { it + title } },
        logout = { logout() },
    )
}

embeddedServer(CIO, port = 8080) {
    install(WebSockets)
    routing {
        remoteState(path = "/remote", serializer = RootState.serializer()) { scope ->
            RootModel(scope = scope).state
        }
    }
}.start()
```

The `WebSockets` plugin is installed by the application, so it can be configured as needed (ping
period, timeouts and so on). The route can be wrapped in any other routing, for example `route("/api")`.

`createRoot` is called once per connection with a `scope` that lives as long as the connection.

The library does not know about users or sessions. Authentication and restoring state after a reconnect
belong to the application and are expressed with the state itself. For example, the root state can
expose `auth: RemoteFunction<SessionToken, Boolean>`, and the client calls it right after connecting,
before showing anything.

All work of one connection runs on a single-threaded dispatcher.

## Client

```kotlin
val client = HttpClient(CIO) { install(WebSockets) }

val connection: StateFlow<RemoteConnectionState<RootState>> = client.connectRemoteState(
    scope = scope,
    url = "ws://localhost:8080/remote",
    serializer = RootState.serializer(),
)
```

`RemoteConnectionState` is one of:

- `Connecting`;
- `Connected(root)`, which holds the mirrored root state;
- `Disconnected(cause, nextAttemptAt)`, which holds the reason and the time of the next reconnect attempt.

The client reconnects automatically. The delay is a function of the number of consecutive failed
attempts and defaults to `exponentialReconnectDelay()`: 1s, doubling, capped at 30s, reset after a
successful connection. Every successful connection produces a new root. The previous root stops
updating, and calls on it fail.

Using the mirrored state:

```kotlin
val anonymous = root.loginState.state.value.leftOrNull()!!
anonymous.login("user" to "password")

val logged = root.loginState.state.first { it.isRight() }.getOrNull()!!
logged.addTask("Buy milk")
logged.tasks.state.collect { tasks -> render(tasks.map { it.value }) }
```

Calls throw:

- `RemoteCallException` with `CallError.Outdated` if there is no function at its path anymore
  (see below);
- `RemoteCallException` with `CallError.Internal` if it threw on the server;
- the disconnection cause if the connection is lost.

## How it works

Every remote state is a node. On the server a node watches its `StateFlow`. When the value changes, it sends
`diff(previous, new)` to the client and then walks the new value to find the remote states and functions
inside it, together with their paths. Paths are always taken from the current value, never from patches.

Nested states are matched by instance. If the same `RemoteState` object is still in the value, its node and
subscription are kept, only its path may change. New objects get new nodes, missing ones are stopped. So
keep unchanged items as the same objects (for example with `mapListReusable`), otherwise their nodes are
recreated on every change.

The client mirrors this. A node applies each patch to its current value, decodes new remote states and
functions from the patch and walks the new value the same way. A function finds its current path when it
is called, and updates of nested states are routed by path to the matching nodes.

A call goes to whatever function is at its path at the moment the server receives it. If there is none,
the call fails with `CallError.Outdated`. A function that is no longer in the client's value fails with
`CallError.Outdated` without a request. For example, removing a task twice fails the second time, and a
call on a logged state after logout fails because `loginState/right/...` no longer exists.

### Protocol

Messages are JSON over the WebSocket route. Paths are strings of segments separated by `/`.
Inside a segment `/` is written as `~/` and `~` as `~~`. The root is the empty string.

Client to server, the only message is a call:

```json
{"callId": 3, "path": "loginState/right/addTask", "argument": "Buy milk"}
```

Server to client:

```json
{"type": "init", "value": {"loginState": {"left": {"login": {}}}}}
{"type": "patch", "path": "loginState", "patch": {"right": {"tasks": {}, "addTask": {}, "logout": {}}}}
{"type": "patch", "path": "loginState/right/tasks", "patch": {"placed": [{"key": "Buy milk"}], "values": {"Buy milk": {"title": "Buy milk", "remove": {}}}}}
{"type": "call_result", "callId": 3, "result": {"right": {}}}
{"type": "call_result", "callId": 4, "result": {"left": "outdated"}}
```

`init` holds the root value and is sent right after the connection is opened. A remote state inside a value
or a patch is written as its current value, a function as `{}`. Items of `KeyedListRemoteState` are written
as an object keyed by item keys, so the key is a path segment. Sealed classes are encoded as
`{"type": ..., "value": ...}`, so `value` appears in paths through them.

A call result and the update it causes are not ordered relative to each other. Wait for the expected
state rather than reading it right after a call.

## Notes and limitations

- **Patches must be correct.** If `apply(old, diff(old, new))` differs from `new`, the client's value
  diverges from the server's, and so do paths.
- **Every change walks the value of its state** on both sides. The network gets only the patch, but the
  walk is linear in the size of the value. Split large states into nested states.
- **Paths inside plain lists are indices.** A call made right before the list changes can reach another
  item. Use `KeyedListRemoteState` or a `Map` for interactive items.
- **One `RemoteState` object can be placed only once** in the tree.
- **Function arguments and results** are plain data encoded with regular JSON. They cannot contain
  `RemoteState` or `RemoteFunction`.
- **Every connection gets its own model tree.** `createRoot` is called for each connection.
