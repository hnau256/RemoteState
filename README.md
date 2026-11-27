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
| `common` | `RemoteState`, `RemoteFunction`, wire protocol. Depend on it from the module with your state classes.             |
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
    val tasks: RemoteState<Map<String, TaskState>>,
    val addTask: RemoteFunction<String, Unit>,
    val logout: RemoteFunction<Unit, Unit>,
)

@Serializable
data class AnonymousState(
    val login: RemoteFunction<Pair<String, String>, Boolean>,
)

@Serializable
data class RootState(
    val loginState: RemoteState<@Serializable(with = EitherSerializer::class) Either<AnonymousState, LoggedState>>,
)
```

- `RemoteState<T>` wraps a `StateFlow<T>`. Only what changed in its value is sent to the client.
- `RemoteFunction<A, R>` is a `suspend (A) -> R`. Use `Pair`/`Triple` for several arguments and `Unit` for none.
  `RemoteFunction<Unit, R>` can be called without arguments: `state.logout()`.
- Any other field is regular serializable data.
- `RemoteState` and `RemoteFunction` can be placed in objects, maps and sealed classes, but not inside a
  `List`: list indices are not stable addresses. For a collection of interactive items use a `Map` with
  stable keys: items are addressed by key, and the order of the map is kept.

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
                TaskModel(title, remove = { titles.update { it - title } }).state
            }
            .mapState(scope) { tasks -> tasks.associateBy(TaskState::title) }
            .toRemoteState(),
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
logged.tasks.state.collect { tasks -> render(tasks.values) }
```

Calls throw:

- `RemoteCallException` with `CallError.Outdated` if there is no function at its path anymore
  (see below);
- `RemoteCallException` with `CallError.Internal` if it threw on the server;
- the disconnection cause if the connection is lost.

## How it works

Every `RemoteState` is a node. A node encodes its value to JSON. Functions become `{}` and are registered by
their path inside the value. Nested `RemoteState`s become `{}` too and get their own nodes, so the JSON of a
node holds only its own data.

When the value changes, the node encodes it again, compares the new JSON with the previous one and sends
only the difference:

- unchanged parts are skipped;
- object members are added, removed and changed by key, and moved items are placed next to their
  neighbours (only the items that left their relative order are moved);
- a string that got longer at the end is sent as its suffix, so streamed text costs only the new part;
- an array that got longer at the end is sent as its new items.

A nested state with the same instance at the same path keeps its node. A new instance gets a new node,
and its JSON is sent together with the update of its parent.

The client mirrors this. A node applies a patch to its JSON, sharing all untouched parts with the previous
JSON, and decodes the value again. Every part whose JSON is the same instance as before is taken from the
previous value, so unchanged objects stay the same instances and the UI does not redraw them.

A call goes to whatever function is at its path at the moment the server receives it. If there is none,
the call fails with `CallError.Outdated`. For example, removing a task twice fails the second time, and a
call on a logged state after logout fails because `loginState/right/...` no longer exists.

### Protocol

Messages are JSON over the WebSocket route. Paths are strings of segments separated by `/`.
Inside a segment `/` is written as `~/` and `~` as `~~`. The root is the empty string.

Client to server, the only message is a call:

```json
{"callId": 3, "path": "loginState/right/addTask", "argument": "Buy milk"}
```

Server to client, an update of the state at `path` and results of calls:

```json
{"type": "update", "path": "", "patch": [], "states": {"": {"loginState": {}}, "loginState": {"left": {"login": {}}}}}
{"type": "update", "path": "loginState", "patch": [{"type": "remove", "path": "left"}, {"type": "set", "path": "right", "value": {"tasks": {}, "addTask": {}, "logout": {}}}], "states": {"right/tasks": {}}}
{"type": "update", "path": "loginState/right/tasks", "patch": [{"type": "set", "path": "Buy milk", "value": {"title": "Buy milk", "remove": {}}}]}
{"type": "update", "path": "chat/messages/42/text", "patch": [{"type": "append", "path": "", "text": " world"}]}
{"type": "call_result", "callId": 3, "result": {"right": {}}}
{"type": "call_result", "callId": 4, "result": {"left": "outdated"}}
```

`patch` is a list of operations on the JSON of the state: `set`, `remove`, `append` (to a string), `push`
(to an array) and `place` (move an object member after another one or to the start). `states` holds the JSON
of nested states created by this update, by their paths relative to `path`. The first message creates
the root.

Sealed classes are encoded as `{"type": ..., "value": ...}`, so `value` appears in paths through them.

A call result and the update it causes are not ordered relative to each other. Wait for the expected
state rather than reading it right after a call.

## Notes and limitations

- **Every change re-encodes and compares the value of its state.** The network gets only the difference,
  but the work is linear in the size of the value. Split large states into nested `RemoteState`s, then only
  the changed one is processed.
- **Keep unchanged items as the same objects** on the server (for example with `mapListReusable`), so
  nested states inside them keep their nodes.
- **Calls go to the current address.** If a key is reused, a call made from an old value reaches the new
  element with the same key.
- **A change of map order alone is not sent.** Maps with the same entries are equal, so `StateFlow` does not
  emit a map that only changed its order. The new order is sent with the next change of the map.
- **Object keys must not be empty**, because every key is a path segment.
- **Function arguments and results** are plain data encoded with regular JSON. They cannot contain
  `RemoteState` or `RemoteFunction`.
- **Every connection gets its own model tree.** `createRoot` is called for each connection.
