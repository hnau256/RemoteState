package org.hnau.remotestate.client

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.hnau.commons.gen.fold.annotations.Fold
import org.hnau.remotestate.common.CallError
import org.hnau.remotestate.common.RemoteFunction

@Fold
sealed interface ClientCallError {

    data object Disconnected : ClientCallError

    data class Server(
        val error: CallError,
    ) : ClientCallError
}

class RemoteCallException(
    val error: ClientCallError,
) : Exception("Remote call failed: $error")

suspend fun <A, R> RemoteFunction<A, R>.invokeCatching(
    argument: A,
): Either<ClientCallError, R> = try {
    invoke(argument).right()
} catch (exception: RemoteCallException) {
    exception.error.left()
}

suspend fun <R> RemoteFunction<Unit, R>.invokeCatching(): Either<ClientCallError, R> = invokeCatching(Unit)
