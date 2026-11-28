package org.hnau.remotestate.test.server

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.hnau.commons.kotlin.coroutines.flow.state.mapListReusable
import org.hnau.commons.kotlin.coroutines.flow.state.mapState
import org.hnau.commons.kotlin.it
import org.hnau.remotestate.common.toRemoteMap
import org.hnau.remotestate.common.toRemoteState
import org.hnau.remotestate.test.common.AddNoteError
import org.hnau.remotestate.test.common.CallsState
import org.hnau.remotestate.test.common.CounterState
import org.hnau.remotestate.test.common.NoteState
import org.hnau.remotestate.test.common.NotesState
import org.hnau.remotestate.test.common.TestRootState
import kotlin.time.Duration.Companion.seconds

class TestServerModel(
    scope: CoroutineScope,
) {

    private data class Note(
        val id: Long,
        val title: String,
    )

    private val counter: MutableStateFlow<Int> = MutableStateFlow(0)

    private val notes: MutableStateFlow<List<Note>> = MutableStateFlow(emptyList())

    val state: TestRootState = TestRootState(
        counter = CounterState(
            value = counter.toRemoteState(),
            increment = { counter.update { value -> value + 1 } },
            decrement = { counter.update { value -> value - 1 } },
        ),
        notes = NotesState(
            notes = notes
                .mapListReusable(
                    scope = scope,
                    extractKey = ::it,
                ) { _, note ->
                    note.id to NoteState(
                        title = note.title,
                        remove = { notes.update { current -> current - note } },
                    )
                }
                .mapState(scope) { idsWithNotes -> idsWithNotes.toMap() }
                .toRemoteMap(),
            add = ::addNote,
        ),
        calls = CallsState(
            slow = { delay(5.seconds) },
            fail = { error("Test failure") },
        ),
    )

    private fun addNote(
        title: String,
    ): Either<AddNoteError, Unit> = either {
        val trimmed = title.trim()
        ensure(trimmed.isNotEmpty()) { AddNoteError.Blank }
        ensure(notes.value.none { note -> note.title == trimmed }) { AddNoteError.Duplicate }
        notes.update { current ->
            current + Note(
                id = current.maxOfOrNull(Note::id)?.inc() ?: 0,
                title = trimmed,
            )
        }
    }
}
