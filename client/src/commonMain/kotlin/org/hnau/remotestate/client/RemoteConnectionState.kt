package org.hnau.remotestate.client

import kotlinx.coroutines.CoroutineScope
import org.hnau.commons.gen.fold.annotations.Fold
import kotlin.time.Instant

@Fold
sealed interface RemoteConnectionState<out T> {

    data object Connecting : RemoteConnectionState<Nothing>

    data class Connected<out T>(
        val root: T,
        val scope: CoroutineScope,
    ) : RemoteConnectionState<T>

    data class Disconnected(
        val cause: Throwable,
        val nextAttemptAt: Instant,
    ) : RemoteConnectionState<Nothing>
}
