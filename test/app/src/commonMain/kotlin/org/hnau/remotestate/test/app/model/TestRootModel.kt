package org.hnau.remotestate.test.app.model

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.hnau.commons.app.model.goback.GoBackHandler
import org.hnau.commons.app.model.goback.NeverGoBackHandler
import org.hnau.commons.gen.pipe.annotations.Pipe
import org.hnau.commons.kotlin.coroutines.flow.state.mapWithScope
import org.hnau.commons.kotlin.foldBoolean
import org.hnau.commons.kotlin.getOrInit
import org.hnau.commons.kotlin.toAccessor
import org.hnau.remotestate.client.ConnectionState
import org.hnau.remotestate.client.connectRemoteState
import org.hnau.remotestate.client.map
import org.hnau.remotestate.test.common.TestRootState

class TestRootModel(
    scope: CoroutineScope,
    private val dependencies: Dependencies,
    private val skeleton: Skeleton,
) {

    @Pipe
    interface Dependencies {

        val httpClient: HttpClient

        val serverUrl: String

        val serverController: TestServerController

        companion object
    }

    @Serializable
    data class Skeleton(
        var connected: ConnectedModel.Skeleton? = null,
    )

    val serverIsRunning: StateFlow<Boolean>
        get() = dependencies.serverController.isRunning

    fun switchServer() {
        dependencies.serverController.isRunning.value.foldBoolean(
            ifTrue = dependencies.serverController::stop,
            ifFalse = dependencies.serverController::start,
        )
    }

    val connection: StateFlow<ConnectionState<ConnectedModel>> = dependencies
        .httpClient
        .connectRemoteState(
            scope = scope,
            url = dependencies.serverUrl + TestRootState.path,
            serializer = TestRootState.serializer(),
        )
        .mapWithScope(scope) { scope, connectionState ->
            connectionState.map { root ->
                ConnectedModel(
                    scope = scope,
                    root = root,
                    skeleton = skeleton::connected
                        .toAccessor()
                        .getOrInit { ConnectedModel.Skeleton() },
                )
            }
        }

    val goBackHandler: GoBackHandler = NeverGoBackHandler
}
