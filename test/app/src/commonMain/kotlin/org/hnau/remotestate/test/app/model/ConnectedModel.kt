package org.hnau.remotestate.test.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import org.hnau.commons.kotlin.getOrInit
import org.hnau.commons.kotlin.toAccessor
import org.hnau.remotestate.test.common.AddNoteError
import org.hnau.remotestate.test.common.TestRootState
import org.hnau.remotestate.utils.core.RemoteCallError

class ConnectedModel(
    scope: CoroutineScope,
    root: TestRootState,
    skeleton: Skeleton,
) {

    @Serializable
    data class Skeleton(
        var notes: NotesModel.Skeleton? = null,
    )

    private val errorsChannel: Channel<RemoteCallError<AddNoteError>> = Channel(Channel.UNLIMITED)

    val errors: Flow<RemoteCallError<AddNoteError>> = errorsChannel.receiveAsFlow()

    private val onError: (RemoteCallError<AddNoteError>) -> Unit = { error ->
        errorsChannel.trySend(error)
    }

    val counter: CounterModel = CounterModel(
        scope = scope,
        state = root.counter,
        onError = onError,
    )

    val notes: NotesModel = NotesModel(
        scope = scope,
        state = root.notes,
        skeleton = skeleton::notes
            .toAccessor()
            .getOrInit { NotesModel.Skeleton() },
        onError = onError,
    )

    val calls: CallsModel = CallsModel(
        scope = scope,
        state = root.calls,
        onError = onError,
    )
}
