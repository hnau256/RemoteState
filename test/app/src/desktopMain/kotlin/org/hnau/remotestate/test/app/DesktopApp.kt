package org.hnau.remotestate.test.app

import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import org.hnau.commons.app.projector.app.launchDesktopApplication
import org.hnau.remotestate.test.common.TestRootState

fun main() {
    val serverController = EmbeddedTestServerController(
        port = TestRootState.port,
    )
    serverController.start()
    launchDesktopApplication(
        title = "RemoteState test",
        windowWidth = 480.dp,
        windowHeight = 720.dp,
        rootModelFactory = createTestRootModelFactory(
            httpClient = HttpClient(CIO) {
                install(WebSockets)
            },
            serverUrl = "ws://localhost:${TestRootState.port}",
            serverController = serverController,
        ),
        rootProjectorFactory = TestRootProjectorFactory,
    )
}
