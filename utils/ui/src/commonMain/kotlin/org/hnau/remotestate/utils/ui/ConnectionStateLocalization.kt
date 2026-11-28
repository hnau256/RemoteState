package org.hnau.remotestate.utils.ui

import androidx.compose.runtime.Immutable

@Immutable
data class ConnectionStateLocalization(
    val connecting: String,
    val disconnected: String,
    val nextAttemptIn: String,
    val reconnectNow: String,
)
