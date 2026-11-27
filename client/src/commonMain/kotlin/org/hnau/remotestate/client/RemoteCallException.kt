package org.hnau.remotestate.client

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.hnau.remotestate.common.CallError
import org.hnau.remotestate.common.RemoteFunction

class RemoteCallException(
    val error: CallError,
) : Exception("Remote call failed: $error")

suspend fun <A, R> RemoteFunction<A, R>.invokeCatching(
    argument: A,
): Either<CallError, R> = try {
    invoke(argument).right()
} catch (exception: RemoteCallException) {
    exception.error.left()
}

suspend fun <R> RemoteFunction<Unit, R>.invokeCatching(): Either<CallError, R> = invokeCatching(Unit)
