package org.hnau.remotestate

import arrow.core.Either
import arrow.core.serialization.EitherSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.hnau.commons.gen.fold.annotations.Fold

@Fold
@Serializable
enum class CallError {

    @SerialName("outdated")
    Outdated,

    @SerialName("internal")
    Internal,
}

@Fold
@Serializable
sealed interface RemoteServerMessage {

    @Serializable
    @SerialName("init")
    data class Init(
        val value: JsonElement,
    ) : RemoteServerMessage

    @Serializable
    @SerialName("patch")
    data class Patch(
        val path: RemotePath,
        val patch: JsonElement,
    ) : RemoteServerMessage

    @Serializable
    @SerialName("call_result")
    data class CallResult(
        val callId: CallId,
        val result: @Serializable(with = EitherSerializer::class) Either<CallError, JsonElement>,
    ) : RemoteServerMessage
}

class RemoteCallException(
    val error: CallError,
) : Exception("Remote call failed: $error")

@Serializable
data class RemoteCall(
    val callId: CallId,
    val path: RemotePath,
    val argument: JsonElement,
)
