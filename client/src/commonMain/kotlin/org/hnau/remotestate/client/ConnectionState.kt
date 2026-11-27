package org.hnau.remotestate.client

import org.hnau.commons.gen.fold.annotations.Fold
import org.hnau.commons.kotlin.it
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

inline fun <I, O> ConnectionState<I>.map(
    transform: (I) -> O,
): ConnectionState<O> = foldRaw(
    ifConnecting = ::it,
    ifDisconnected = ::it,
    ifConnected = { connected ->
        connected
            .root
            .let(transform)
            .let { transformed ->
                ConnectionState.Connected(
                    root = transformed,
                )
            }
    }
)
