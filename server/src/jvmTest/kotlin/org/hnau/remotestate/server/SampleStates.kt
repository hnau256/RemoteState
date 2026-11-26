package org.hnau.remotestate.server

import arrow.core.Either
import kotlinx.serialization.Serializable
import arrow.core.serialization.EitherSerializer
import org.hnau.remotestate.RemoteFunction
import org.hnau.remotestate.KeyedListRemoteState
import org.hnau.remotestate.SnapshotRemoteState

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

