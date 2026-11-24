package org.hnau.remotestate.client

import kotlin.time.Instant

sealed interface RemoteConnectionState<out T> {

    data object Connecting : RemoteConnectionState<Nothing>

    data class Connected<out T>(
        val root: T,
    ) : RemoteConnectionState<T>

    data class Disconnected(
        val cause: Throwable,
        val nextAttemptAt: Instant,
    ) : RemoteConnectionState<Nothing>
}
