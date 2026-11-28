package org.hnau.remotestate.test.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.CancelOrInProgress
import org.hnau.commons.kotlin.coroutines.actionOrInProgressIfExecuting
import org.hnau.remotestate.client.invokeCatching
import org.hnau.remotestate.test.common.NoteState
import org.hnau.remotestate.utils.core.RemoteCallError
import org.hnau.remotestate.utils.core.toRemoteCallResult

class NoteModel(
    scope: CoroutineScope,
    state: NoteState,
    onError: (RemoteCallError<Nothing>) -> Unit,
) {

    val title: String = state.title

    val remove: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state.remove.invokeCatching().toRemoteCallResult().onLeft(onError)
        }
}
