package org.hnau.remotestate.test.server

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.hnau.remotestate.server.remoteState
import org.hnau.remotestate.test.common.TestRootState

fun createTestServer(
    model: TestServerModel,
    port: Int,
): EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
    install(WebSockets)
    routing {
        remoteState(
            path = TestRootState.path,
            serializer = TestRootState.serializer(),
            onError = Throwable::printStackTrace,
        ) { model.state }
    }
}
