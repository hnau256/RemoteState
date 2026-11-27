package org.hnau.remotestate

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class CallId(
    val id: Long,
)
