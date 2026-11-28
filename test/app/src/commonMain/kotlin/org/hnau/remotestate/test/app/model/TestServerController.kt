package org.hnau.remotestate.test.app.model

import kotlinx.coroutines.flow.StateFlow

interface TestServerController {

    val isRunning: StateFlow<Boolean>

    fun start()

    fun stop()
}
