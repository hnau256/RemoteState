package org.hnau.remotestate.test.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.CancelOrInProgress
import org.hnau.commons.kotlin.coroutines.actionOrInProgressIfExecuting
import org.hnau.remotestate.client.invokeCatching
import org.hnau.remotestate.test.common.CallsState
import org.hnau.remotestate.utils.core.RemoteCallError
import org.hnau.remotestate.utils.core.toRemoteCallResult

class CallsModel(
    scope: CoroutineScope,
    state: CallsState,
    onError: (RemoteCallError<Nothing>) -> Unit,
) {

    val slow: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state.slow.invokeCatching().toRemoteCallResult().onLeft(onError)
        }

    val fail: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state.fail.invokeCatching().toRemoteCallResult().onLeft(onError)
        }
}
