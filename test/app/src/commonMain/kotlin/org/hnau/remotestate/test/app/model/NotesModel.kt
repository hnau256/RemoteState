package org.hnau.remotestate.test.app.model

import arrow.core.None
import arrow.core.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.hnau.commons.app.model.input.InputModel
import org.hnau.commons.app.model.input.InputSkeleton
import org.hnau.commons.app.model.input.InputType
import org.hnau.commons.app.model.input.factory.createModel
import org.hnau.commons.app.model.input.factory.toInputModelFactory
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.CancelOrInProgress
import org.hnau.commons.kotlin.coroutines.actionOrInProgressIfExecuting
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.commons.kotlin.coroutines.flow.state.mutable.toMutableStateFlowAsInitial
import org.hnau.commons.kotlin.it
import org.hnau.remotestate.client.invokeCatching
import org.hnau.remotestate.test.common.AddNoteError
import org.hnau.remotestate.test.common.NotesState
import org.hnau.remotestate.utils.core.RemoteCallError
import org.hnau.remotestate.utils.core.toRemoteCallResult

class NotesModel(
    scope: CoroutineScope,
    state: NotesState,
    private val skeleton: Skeleton,
    onError: (RemoteCallError<AddNoteError>) -> Unit,
) {

    @Serializable
    data class Skeleton(
        val title: InputSkeleton<String, String> = InputSkeleton(
            initialValue = None,
            state = "".toMutableStateFlowAsInitial(),
        ),
    )

    val title: InputModel<String, String, Nothing, InputType.Edit> = InputType
        .Edit
        .toInputModelFactory()
        .createModel(
            scope = scope,
            skeleton = skeleton.title,
        )

    val notes: StateFlow<List<NoteModel>> = state
        .notes
        .state
        .mapState(scope) { notes ->
            notes
                .entries
                .sortedBy { (id) -> id }
                .map { (id, note) -> id to note }
        }
        .mapListReusable(
            scope = scope,
            extractKey = { (id) -> id },
        ) { scope, (_, note) ->
            NoteModel(
                scope = scope,
                state = note,
                onError = onError,
            )
        }

    val add: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state
                .add
                .invokeCatching(title.valueOrError.value.merge())
                .toRemoteCallResult(::it)
                .fold(
                    ifLeft = onError,
                    ifRight = { skeleton.title.state.value = "" },
                )
        }
}
