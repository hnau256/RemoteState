package org.hnau.remotestate

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class StateId(
    val uuid: Uuid,
) {

    companion object {

        val root: StateId = StateId(Uuid.NIL)

        fun new(): StateId = StateId(Uuid.random())
    }
}

@Serializable
@JvmInline
value class FunctionId(
    val uuid: Uuid,
) {

    companion object {

        fun new(): FunctionId = FunctionId(Uuid.random())
    }
}

@Serializable
@JvmInline
value class CallId(
    val uuid: Uuid,
) {

    companion object {

        fun new(): CallId = CallId(Uuid.random())
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
