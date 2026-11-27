package org.hnau.remotestate.utils.core

import org.hnau.commons.gen.fold.annotations.Fold
import org.hnau.remotestate.common.CallError

@Fold
sealed interface RemoteCallError<out E> {

    data class Call(
        val error: CallError,
    ) : RemoteCallError<Nothing>

    data class Domain<out E>(
        val error: E,
    ) : RemoteCallError<E>
}
