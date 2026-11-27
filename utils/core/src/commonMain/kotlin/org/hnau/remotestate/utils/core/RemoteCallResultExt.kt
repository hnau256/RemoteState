package org.hnau.remotestate.utils.core

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.right
import org.hnau.remotestate.common.CallError

inline fun <O, T, E> Either<CallError, O>.toRemoteCallResult(
    extractDomainError: (O) -> Either<E, T>,
): Either<RemoteCallError<E>, T> = this
    .mapLeft { callError -> RemoteCallError.Call(callError) }
    .flatMap { result ->
        result
            .let(extractDomainError)
            .mapLeft { domainError -> RemoteCallError.Domain(domainError) }
    }

fun <O> Either<CallError, O>.toRemoteCallResult(): Either<RemoteCallError<Nothing>, O> =
    toRemoteCallResult { response -> response.right() }