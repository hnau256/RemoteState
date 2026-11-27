package org.hnau.remotestate.common

import kotlinx.serialization.json.Json

val RemoteJson: Json = Json {
    ignoreUnknownKeys = true
}
