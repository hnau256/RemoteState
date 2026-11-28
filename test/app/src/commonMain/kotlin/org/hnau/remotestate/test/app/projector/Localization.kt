package org.hnau.remotestate.test.app.projector

import androidx.compose.runtime.Immutable
import org.hnau.remotestate.utils.ui.ConnectionStateLocalization

@Immutable
data class Localization(
    val appName: String = "RemoteState test",
    val connectionState: ConnectionStateLocalization = ConnectionStateLocalization(
        connecting = "Connecting…",
        disconnected = "No connection to the server",
        nextAttemptIn = "Next attempt in",
        reconnectNow = "Reconnect now",
    ),
    val serverIsRunning: String = "Server is running",
    val serverIsStopped: String = "Server is stopped",
    val startServer: String = "Start",
    val stopServer: String = "Stop",
    val counter: String = "Counter (RemoteState)",
    val notes: String = "Notes (RemoteMap)",
    val newNote: String = "New note",
    val addNote: String = "Add",
    val noteIsBlank: String = "Note is blank",
    val noteIsDuplicate: String = "Such note already exists",
    val calls: String = "Calls (RemoteFunction)",
    val slowCall: String = "Slow call (5 seconds)",
    val failingCall: String = "Failing call",
    val callError: String = "Call failed",
) {

    companion object {

        val default: Localization = Localization()
    }
}
