package org.hnau.remotestate.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.hnau.commons.gen.fold.annotations.Fold

@Fold
@Serializable
sealed interface JsonOperation {

    @Serializable
    @SerialName("set")
    data class Set(
        val path: RemotePath,
        val value: JsonElement,
    ) : JsonOperation

    @Serializable
    @SerialName("remove")
    data class Remove(
        val path: RemotePath,
    ) : JsonOperation

    @Serializable
    @SerialName("append")
    data class Append(
        val path: RemotePath,
        val text: String,
    ) : JsonOperation

    @Serializable
    @SerialName("push")
    data class Push(
        val path: RemotePath,
        val items: List<JsonElement>,
    ) : JsonOperation

    @Serializable
    @SerialName("place")
    data class Place(
        val path: RemotePath,
        val after: String? = null,
    ) : JsonOperation
}
