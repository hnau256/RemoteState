package org.hnau.remotestate.common

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable(with = SnapshotRemoteState.Patcher::class)
class SnapshotRemoteState<out T>(
    state: StateFlow<T>,
) : RemoteState<T>(state) {

    class Patcher<T>(
        valueSerializer: KSerializer<T>,
    ) : RemoteStatePatcher<SnapshotRemoteState<T>, T, T>(
        valueSerializer = valueSerializer,
        patchSerializer = valueSerializer,
        diff = { _, new -> new },
        apply = { _, patch -> patch },
        create = ::SnapshotRemoteState,
    )
}

fun <T> StateFlow<T>.toSnapshotRemoteState(): SnapshotRemoteState<T> = SnapshotRemoteState(
    state = this,
)
