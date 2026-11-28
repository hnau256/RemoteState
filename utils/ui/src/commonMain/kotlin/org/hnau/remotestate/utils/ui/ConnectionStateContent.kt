package org.hnau.remotestate.utils.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.hnau.commons.app.projector.fractal.SButton
import org.hnau.commons.app.projector.fractal.SLine
import org.hnau.commons.app.projector.fractal.SPanel
import org.hnau.commons.app.projector.fractal.SText
import org.hnau.commons.app.projector.fractal.context.FContext
import org.hnau.commons.app.projector.fractal.context.LocalFContext
import org.hnau.commons.app.projector.fractal.context.color
import org.hnau.commons.app.projector.fractal.context.contentOverlay
import org.hnau.commons.app.projector.fractal.distance.LocalDistance
import org.hnau.commons.app.projector.fractal.padding.LocalContentPaddingBox
import org.hnau.commons.app.projector.fractal.size.units
import org.hnau.commons.app.projector.fractal.table.STable
import org.hnau.commons.app.projector.fractal.table.STableScope
import org.hnau.commons.app.projector.fractal.utils.Importance
import org.hnau.commons.app.projector.fractal.utils.Mood
import org.hnau.commons.app.projector.uikit.state.StateContent
import org.hnau.commons.app.projector.uikit.transition.TransitionSpec
import org.hnau.commons.app.projector.utils.Orientation
import org.hnau.commons.app.projector.utils.TitleOrIcon
import org.hnau.commons.kotlin.coroutines.ActionOrElse
import org.hnau.commons.kotlin.coroutines.instant
import org.hnau.remotestate.client.ConnectionState
import org.hnau.remotestate.client.fold
import org.hnau.remotestate.client.ordinal
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
                ProgressScreen(
                    contentPadding = contentPadding,
                ) {
                    SText(localization.connecting)
                }
            },
            ifDisconnected = { _, nextAttemptAt, reconnectNow ->
                ProgressScreen(
                    contentPadding = contentPadding,
                    actions = {
                        SCell {
                            SButton(
                                modifier = Modifier.fillMaxWidth(),
                                actionOrElseOrDisabled = ActionOrElse.instant(reconnectNow),
                                titleOrIcon = TitleOrIcon.Title(localization.reconnectNow),
                            )
                        }
                    },
                ) {
                    SText(localization.disconnected)
                    SText("${localization.nextAttemptIn} ${rememberSecondsUntil(nextAttemptAt).formatCountdown()}")
                }
            },
            ifConnected = { root -> connectedContent(root) },
        )
    }
}

@Composable
private fun ProgressScreen(
    contentPadding: PaddingValues,
    actions: @Composable STableScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        STable(
            orientation = Orientation.Vertical,
        ) {
            SCell {
                SPanel {
                    FContext(
                        update = {
                            copy(
                                mood = Mood.Active.default,
                            )
                        }
                    ) {
                        SLine(
                            orientation = Orientation.Vertical,
                            acrossOrientation = Alignment.CenterHorizontally,
                            separation = LocalDistance.current.units.padding.along.small,
                        ) {
                            LocalContentPaddingBox {
                                FContext(
                                    update = { contentOverlay() },
                                ) {
                                    CircularProgressIndicator(
                                        color = LocalFContext.current.color,
                                    )
                                }
                            }
                            content()
                        }
                    }
                }
            }
            actions()
        }
    }
}

@Composable
private fun rememberSecondsUntil(
    instant: Instant,
): Long {
    val seconds by produceState(
        initialValue = secondsUntil(instant),
        key1 = instant,
    ) {
        while (value > 0) {
            delay(countdownTick)
            value = secondsUntil(instant)
        }
    }
    return seconds
}

private val countdownTick: Duration = 100.milliseconds

private fun secondsUntil(
    instant: Instant,
): Long = (instant - Clock.System.now())
    .coerceAtLeast(Duration.ZERO)
    .let { remaining -> (remaining + 1.seconds - 1.milliseconds).inWholeSeconds }

private fun Long.formatCountdown(): String =
    listOf(this / 60, this % 60).joinToString(":") { part ->
        part.toString().padStart(2, '0')
    }
