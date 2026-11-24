package org.hnau.remotestate

import kotlinx.serialization.json.Json

val RemoteJson: Json = Json {
    ignoreUnknownKeys = true
}
