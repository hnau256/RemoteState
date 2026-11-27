package org.hnau.remotestate.utils.ui

import androidx.compose.runtime.Immutable

@Immutable
data class ConnectionStateLocalization(
    val title: String,
    val connecting: String,
    val disconnected: String,
    val nextAttemptAt: String,
)
