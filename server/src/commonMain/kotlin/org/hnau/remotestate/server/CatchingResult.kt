package org.hnau.remotestate.server

import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> catchingResult(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Throwable) {
    Result.failure(exception)
}
