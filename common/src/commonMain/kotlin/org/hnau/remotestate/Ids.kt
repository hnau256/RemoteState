package org.hnau.remotestate

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class StateId private constructor(
    private val id: Long,
) {

    companion object {

        val root: StateId = StateId(0)

        fun createFactory(): IdFactory<StateId> = IdFactory(::StateId)
    }
}

@Serializable
@JvmInline
value class FunctionId private constructor(
    private val id: Long,
) {

    companion object {

        fun createFactory(): IdFactory<FunctionId> = IdFactory(::FunctionId)
    }
}

@Serializable
@JvmInline
value class CallId private constructor(
    private val id: Long,
) {

    companion object {

        fun createFactory(): IdFactory<CallId> = IdFactory(::CallId)
    }
}

@Serializable
@JvmInline
value class SessionId(
    val uuid: Uuid,
) {

    companion object {

        fun new(): SessionId = SessionId(Uuid.random())
    }
}
