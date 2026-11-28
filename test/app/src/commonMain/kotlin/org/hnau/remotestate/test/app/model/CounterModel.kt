package org.hnau.remotestate.test.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.CancelOrInProgress
import org.hnau.commons.kotlin.coroutines.actionOrInProgressIfExecuting
import org.hnau.remotestate.client.invokeCatching
import org.hnau.remotestate.test.common.CounterState
import org.hnau.remotestate.utils.core.RemoteCallError
import org.hnau.remotestate.utils.core.toRemoteCallResult

class CounterModel(
    scope: CoroutineScope,
    state: CounterState,
    onError: (RemoteCallError<Nothing>) -> Unit,
) {

    val value: StateFlow<Int> = state.value.state

    val increment: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state.increment.invokeCatching().toRemoteCallResult().onLeft(onError)
        }

    val decrement: StateFlow<ActionOrElse<Unit, CancelOrInProgress.InProgress>> =
        actionOrInProgressIfExecuting(scope) {
            state.decrement.invokeCatching().toRemoteCallResult().onLeft(onError)
        }
}
