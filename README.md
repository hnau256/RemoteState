# RemoteState

A Kotlin Multiplatform library for server-driven state over WebSocket.

The server owns the models. It exposes a tree of plain `@Serializable` state classes that contain
observable values (`RemoteState`) and callable actions (`RemoteFunction`). The client receives a mirror
of that tree: values arrive as `StateFlow`s that update when the server changes them, and calling a
function on the client invokes the corresponding lambda on the server.

Neither side writes protocol code. Everything is built on top of the `kotlinx.serialization` plugin.

## Modules

| Module   | Contents                                                                                                                |
|----------|-------------------------------------------------------------------------------------------------------------------------|
| `common` | `RemoteState`, `RemoteFunction`, ids, wire protocol. Depend on it from the module with your state classes.              |
| `server` | `Application.remoteStateModule(...)`: a Ktor WebSocket endpoint that serves the state tree.                             |
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
    val tasks: RemoteState<List<TaskState>>,
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

- `RemoteState<T>` wraps a `StateFlow<T>`. Every time the value changes, the whole new value is sent to the client.
- `RemoteFunction<A, R>` is a `suspend (A) -> R`. Use `Pair`/`Triple` for several arguments and `Unit` for none.
  `RemoteFunction<Unit, R>` can be called without arguments: `state.logout()`.
- Any other field is regular serializable data.

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
            .toRemoteState(),
        addTask = { title -> titles.update { it + title } },
        logout = { logout() },
    )
}

embeddedServer(CIO, port = 8080) {
    remoteStateModule(RootState.serializer()) { scope, sessionId ->
        RootModel(
            scope = scope,
            skeleton = skeletons.getOrPut(sessionId) { RootSkeleton() },
        ).state
    }
}.start()
```

`createRoot` is called once per connection with:

- `scope`, which lives as long as the connection;
- `sessionId`, sent by the client when it connects.

Use `sessionId` to restore the root model's skeleton, so that a reconnecting client gets the same
state back (for example, it stays logged in). Storing skeletons is up to the application.

All work of one connection runs on a single-threaded dispatcher.

## Client

```kotlin
val client = HttpClient(CIO) { install(WebSockets) }

val connection: StateFlow<RemoteConnectionState<RootState>> = client.connectRemoteState(
    scope = scope,
    baseUrl = "ws://localhost:8080",
    sessionId = storedSessionId,
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

Generate the `SessionId` once with `SessionId.new()` and persist it on the device. It is the only
thing the client needs to store.

Using the mirrored state:

```kotlin
val anonymous = root.loginState.state.value.leftOrNull()!!
anonymous.login("user" to "password")

val logged = root.loginState.state.first { it.isRight() }.getOrNull()!!
logged.addTask("Buy milk")
logged.tasks.state.collect { tasks -> render(tasks) }
```

Calls throw:

- `RemoteCallException` with `CallError.Outdated` if the function no longer exists on the server
  (see below);
- `RemoteCallException` with `CallError.Internal` if it threw on the server;
- the disconnection cause if the connection is lost.

## How it works

Encoding happens through `kotlinx.serialization` with per-session serializers registered as contextual:

- When the server serializes a `RemoteState`, it assigns it a fresh `StateId`, writes `{"id", "value"}`
  and subscribes to its `StateFlow`.
- When the server serializes a `RemoteFunction`, it assigns it a fresh `FunctionId`, writes the id and
  registers the lambda under it.
- On every change the server encodes the new value, sends it as an `update`, and then disposes
  everything that was registered while encoding the previous value: functions are unregistered and
  nested subscriptions are cancelled.
- The client mirrors this: it decodes `RemoteState` into a `MutableStateFlow` keyed by its id and
  turns `RemoteFunction` ids into lambdas that send `call` messages.

As a consequence, a function is only callable while the value it came from is current. A call made
from a stale value fails with `CallError.Outdated` instead of acting on something that is gone.

### Protocol

Messages are JSON over a WebSocket at `/remote`.

Client to server:

```json
{"type": "hello", "sessionId": "5f0c6a9e-8d3b-4c1a-9f2e-7b6d4e3a2c10"}
{"type": "call", "callId": 3, "function": 17, "argument": "Buy milk"}
```

`hello` must be the first message. Otherwise the server closes the socket with `VIOLATED_POLICY`.

Server to client:

```json
{"type": "update", "stateId": 2, "value": [{"title": "Buy milk", "remove": 18}]}
{"type": "call_result", "callId": 3, "result": {"right": {}}}
{"type": "call_result", "callId": 4, "result": {"left": "outdated"}}
```

State, function and call ids are per-connection counters, so they are plain JSON numbers. Only
`SessionId` is a UUID string. The root is delivered as an `update` with `StateId.root` (`0`).

A call result and the update it causes are not ordered relative to each other. Wait for the expected
state rather than reading it right after a call.

## Notes and limitations

- **Treat `SessionId` as a bearer secret.** Whoever knows it gets the session. Do not log it.
- **Updates send the whole value.** Patch-based updates are not supported yet.
- **Several connections with the same `SessionId`** each get their own model tree. If those trees share
  a skeleton, they may overwrite it.
