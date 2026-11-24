package org.hnau.remotestate

import arrow.core.Either
import arrow.core.serialization.EitherSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val RemoteRoute: String = "/remote"

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
        val stateId: StateId,
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
sealed interface RemoteClientMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val sessionId: SessionId,
    ) : RemoteClientMessage

    @Serializable
    @SerialName("call")
    data class Call(
        val callId: CallId,
        val function: FunctionId,
        val argument: JsonElement,
    ) : RemoteClientMessage
}
