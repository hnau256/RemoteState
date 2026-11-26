package org.hnau.remotestate

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class CallId private constructor(
    private val id: Long,
) {

    companion object {

        fun createFactory(): IdFactory<CallId> = IdFactory(::CallId)
    }
}
