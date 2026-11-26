package org.hnau.remotestate.client

import org.hnau.commons.gen.fold.annotations.Fold
import kotlin.time.Instant

@Fold
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
