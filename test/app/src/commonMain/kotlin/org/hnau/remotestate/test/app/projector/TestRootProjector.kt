package org.hnau.remotestate.test.app.projector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.hnau.commons.app.projector.fractal.SButton
import org.hnau.commons.app.projector.fractal.SPanel
import org.hnau.commons.app.projector.fractal.SText
import org.hnau.commons.app.projector.utils.TitleOrIcon
import org.hnau.commons.gen.pipe.annotations.Pipe
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.flow.state.mapWithScope
import org.hnau.commons.kotlin.coroutines.instant
import org.hnau.commons.kotlin.foldBoolean
import org.hnau.remotestate.client.ConnectionState
import org.hnau.remotestate.client.map
import org.hnau.remotestate.test.app.model.TestRootModel
import org.hnau.remotestate.utils.ui.ConnectionStateContent

class TestRootProjector(
    scope: CoroutineScope,
    private val model: TestRootModel,
    private val dependencies: Dependencies,
) {

    @Pipe
    interface Dependencies {

        val localization: Localization

        fun connected(): ConnectedProjector.Dependencies

        companion object
    }

    private val connection: StateFlow<ConnectionState<ConnectedProjector>> = model
        .connection
        .mapWithScope(scope) { scope, connectionState ->
            connectionState.map { connected ->
                ConnectedProjector(
                    scope = scope,
                    model = connected,
                    dependencies = dependencies.connected(),
                )
            }
        }

    @Composable
    fun Content(
        contentPadding: PaddingValues,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.weight(1f),
            ) {
                connection
                    .collectAsState()
                    .value
                    .ConnectionStateContent(
                        contentPadding = contentPadding,
                        localization = dependencies.localization.connectionState,
                    ) { connected ->
                        connected.Content(
                            contentPadding = contentPadding,
                        )
                    }
            }
            ServerPanel()
        }
    }

    @Composable
    private fun ServerPanel() {
        val localization = dependencies.localization
        val isRunning = model.serverIsRunning.collectAsState().value
        SPanel(
            modifier = Modifier.fillMaxWidth(),
        ) {
            SText(
                isRunning.foldBoolean(
                    ifTrue = { localization.serverIsRunning },
                    ifFalse = { localization.serverIsStopped },
                ),
            )
            SButton(
                actionOrElseOrDisabled = ActionOrElse.instant(model::switchServer),
                titleOrIcon = TitleOrIcon.Title(
                    isRunning.foldBoolean(
                        ifTrue = { localization.stopServer },
                        ifFalse = { localization.startServer },
                    ),
                ),
            )
        }
    }
}
