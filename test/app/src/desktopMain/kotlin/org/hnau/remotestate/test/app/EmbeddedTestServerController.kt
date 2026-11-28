package org.hnau.remotestate.test.app

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.remotestate.test.app.model.TestServerController
import org.hnau.remotestate.test.server.TestServerModel
import org.hnau.remotestate.test.server.createTestServer

class EmbeddedTestServerController(
    private val port: Int,
) : TestServerController {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val model: TestServerModel = TestServerModel(
        scope = scope,
    )

    private val server: MutableStateFlow<EmbeddedServer<*, *>?> = MutableStateFlow(null)

    override val isRunning: StateFlow<Boolean> = server.mapState(scope) { serverOrNull -> serverOrNull != null }

    @Synchronized
    override fun start() {
        server.value = server.value ?: createTestServer(
            model = model,
            port = port,
        ).start(wait = false)
    }

    @Synchronized
    override fun stop() {
        server.value?.stop(
            gracePeriodMillis = 0,
            timeoutMillis = 1000,
        )
        server.value = null
    }
}
