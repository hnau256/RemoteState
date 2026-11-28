@file:UseSerializers(
    EitherSerializer::class,
)

package org.hnau.remotestate.test.common

import arrow.core.Either
import arrow.core.serialization.EitherSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.hnau.commons.gen.fold.annotations.Fold
import org.hnau.remotestate.common.RemoteFunction
import org.hnau.remotestate.common.RemoteMap
import org.hnau.remotestate.common.RemoteState

@Serializable
data class TestRootState(
    val counter: CounterState,
    val notes: NotesState,
    val calls: CallsState,
) {

    companion object {

        const val path: String = "/remote"

        const val port: Int = 42760
    }
}

@Serializable
data class CounterState(
    val value: RemoteState<Int>,
    val increment: RemoteFunction<Unit, Unit>,
    val decrement: RemoteFunction<Unit, Unit>,
)

@Serializable
data class NotesState(
    val notes: RemoteMap<Long, NoteState>,
    val add: RemoteFunction<String, Either<AddNoteError, Unit>>,
)

@Fold
@Serializable
sealed interface AddNoteError {

    @Serializable
    @SerialName("blank")
    data object Blank : AddNoteError

    @Serializable
    @SerialName("duplicate")
    data object Duplicate : AddNoteError
}

@Serializable
data class NoteState(
    val title: String,
    val remove: RemoteFunction<Unit, Unit>,
)

@Serializable
data class CallsState(
    val slow: RemoteFunction<Unit, Unit>,
    val fail: RemoteFunction<Unit, Unit>,
)
