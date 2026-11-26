package org.hnau.remotestate

import arrow.core.Either
import arrow.core.serialization.EitherSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class CallError {

    @SerialName("outdated")
    Outdated,

    @SerialName("internal")
    Internal,
}

@Serializable
sealed interface RemoteServerMessage {

    @Serializable
    @SerialName("update")
    data class Update(
        val path: RemotePath,
        val value: JsonElement,
    ) : RemoteServerMessage

    @Serializable
    @SerialName("call_result")
    data class CallResult(
        val callId: CallId,
        val result: @Serializable(with = EitherSerializer::class) Either<CallError, JsonElement>,
    ) : RemoteServerMessage
}

@Serializable
data class RemoteCall(
    val callId: CallId,
    val path: RemotePath,
    val argument: JsonElement,
)
