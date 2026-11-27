package org.hnau.remotestate.utils.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.hnau.commons.app.projector.fractal.SPanel
import org.hnau.commons.app.projector.fractal.SScreen
import org.hnau.commons.app.projector.fractal.SText
import org.hnau.commons.app.projector.fractal.table.lazy.SLazyTable
import org.hnau.commons.app.projector.fractal.table.lazy.cell
import org.hnau.commons.app.projector.uikit.state.StateContent
import org.hnau.commons.app.projector.uikit.transition.TransitionSpec
import org.hnau.commons.app.projector.utils.Orientation
import org.hnau.remotestate.client.ConnectionState
import org.hnau.remotestate.client.fold
import org.hnau.remotestate.client.ordinal
import kotlin.time.Instant

@Composable
fun <T> ConnectionState<T>.ConnectionStateContent(
    contentPadding: PaddingValues,
    localization: ConnectionStateLocalization,
    modifier: Modifier = Modifier,
    connectedContent: @Composable (T) -> Unit,
) {
    StateContent(
        modifier = modifier,
        label = "ConnectionState",
        contentKey = ConnectionState<T>::ordinal,
        transitionSpec = TransitionSpec.rememberCrossfade(),
    ) { state ->
        state.fold(
            ifConnecting = {
                MessageScreen(
                    contentPadding = contentPadding,
                    title = localization.title,
                    lines = listOf(localization.connecting),
                )
            },
            ifDisconnected = { _, nextAttemptAt ->
                MessageScreen(
                    contentPadding = contentPadding,
                    title = localization.title,
                    lines = listOf(
                        localization.disconnected,
                        "${localization.nextAttemptAt} ${nextAttemptAt.formatTime()}",
                    ),
                )
            },
            ifConnected = { root -> connectedContent(root) },
        )
    }
}

@Composable
private fun MessageScreen(
    contentPadding: PaddingValues,
    title: String,
    lines: List<String>,
) {
    SScreen(
        contentPadding = contentPadding,
        title = { SText(title) },
    ) {
        SLazyTable(
            orientation = Orientation.Vertical,
        ) {
            cell {
                SPanel(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    lines.forEach { line -> SText(line) }
                }
            }
        }
    }
}

private fun Instant.formatTime(): String = toLocalDateTime(TimeZone.currentSystemDefault())
    .time
    .let { time ->
        listOf(time.hour, time.minute, time.second).joinToString(":") { part ->
            part.toString().padStart(2, '0')
        }
    }
