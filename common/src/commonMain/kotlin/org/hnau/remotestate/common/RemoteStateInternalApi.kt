package org.hnau.remotestate.common

@RequiresOptIn(
    message = "This is an internal API of RemoteState library. It may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
annotation class RemoteStateInternalApi
