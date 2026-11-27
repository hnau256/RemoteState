package org.hnau.remotestate.client

import org.hnau.commons.gen.fold.annotations.Fold
import kotlin.time.Instant

@Fold
sealed interface ConnectionState<out T> {

    data object Connecting : ConnectionState<Nothing>

    data class Connected<out T>(
        val root: T,
    ) : ConnectionState<T>

    data class Disconnected(
        val cause: Throwable,
        val nextAttemptAt: Instant,
    ) : ConnectionState<Nothing>
}
