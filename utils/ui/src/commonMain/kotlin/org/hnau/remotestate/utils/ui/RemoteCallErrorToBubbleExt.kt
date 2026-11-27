package org.hnau.remotestate.utils.ui

import org.hnau.commons.app.projector.uikit.bubble.Bubble
import org.hnau.remotestate.utils.core.RemoteCallError
import org.hnau.remotestate.utils.core.fold

inline fun <E> RemoteCallError<E>.toBubble(
    callErrorMessage: String,
    extractDomainErrorMessage: (E) -> String,
): Bubble = this
    .fold(
        ifCall = { callErrorMessage },
        ifDomain = extractDomainErrorMessage,
    )
    .let(::Bubble)

fun RemoteCallError<Nothing>.toBubble(
    callErrorMessage: String,
): Bubble = toBubble(
    callErrorMessage = callErrorMessage,
    extractDomainErrorMessage = { "" },
)
