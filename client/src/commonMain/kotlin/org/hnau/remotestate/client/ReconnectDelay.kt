package org.hnau.remotestate.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun exponentialReconnectDelay(
    initial: Duration = 1.seconds,
    max: Duration = 30.seconds,
    factor: Double = 2.0,
): (failedAttempts: Int) -> Duration = { failedAttempts ->
    var delay = initial
    repeat(failedAttempts - 1) {
        delay = (delay * factor).coerceAtMost(max)
    }
    delay.coerceAtMost(max)
}
