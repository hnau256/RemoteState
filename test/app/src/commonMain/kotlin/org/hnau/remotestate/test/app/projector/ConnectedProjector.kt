package org.hnau.remotestate.test.app.projector

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.hnau.commons.app.projector.fractal.SButton
import org.hnau.commons.app.projector.fractal.SPanel
import org.hnau.commons.app.projector.fractal.SScreen
import org.hnau.commons.app.projector.fractal.SText
import org.hnau.commons.app.projector.fractal.input.InputProjector
import org.hnau.commons.app.projector.fractal.input.createInputProjector
import org.hnau.commons.app.projector.fractal.input.type.toInputProjectorPrototype
import org.hnau.commons.app.projector.fractal.table.STableHeader
import org.hnau.commons.app.projector.fractal.table.lazy.SLazyTable
import org.hnau.commons.app.projector.fractal.table.lazy.Subtable
import org.hnau.commons.app.projector.fractal.table.lazy.cell
import org.hnau.commons.app.projector.fractal.table.lazy.cells
import org.hnau.commons.app.projector.fractal.table.lazy.item
import org.hnau.commons.app.projector.uikit.bubble.BubblesShower
import org.hnau.commons.app.projector.uikit.line.weight
import org.hnau.commons.app.projector.utils.Drawable
import org.hnau.commons.app.projector.utils.Orientation
import org.hnau.commons.app.projector.utils.TitleOrIcon
import org.hnau.commons.gen.pipe.annotations.Pipe
import org.hnau.remotestate.test.app.model.ConnectedModel
import org.hnau.remotestate.test.common.fold
import org.hnau.remotestate.utils.ui.toBubble

class ConnectedProjector(
    scope: CoroutineScope,
    private val model: ConnectedModel,
    private val dependencies: Dependencies,
) {

    @Pipe
    interface Dependencies {

        val localization: Localization

        val bubblesShower: BubblesShower
    }

    init {
        model
            .errors
            .onEach { error ->
                dependencies.bubblesShower.showBubble(
                    error.toBubble(
                        callErrorMessage = dependencies.localization.callError,
                    ) { addNoteError ->
                        addNoteError.fold(
                            ifBlank = { dependencies.localization.noteIsBlank },
                            ifDuplicate = { dependencies.localization.noteIsDuplicate },
                        )
                    },
                )
            }
            .launchIn(scope)
    }

    private val newNote: InputProjector = model
        .notes
        .title
        .toInputProjectorPrototype(
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        )
        .createInputProjector(
            scope = scope,
            title = dependencies.localization.newNote,
        )

    @Composable
    fun Content(
        contentPadding: PaddingValues,
    ) {
        val localization = dependencies.localization
        val notes = model.notes.notes.collectAsState().value
        SScreen(
            contentPadding = contentPadding,
            title = { SText(localization.appName) },
        ) {
            SLazyTable(
                orientation = Orientation.Vertical,
            ) {
                item(key = "counter_header") {
                    STableHeader { SText(localization.counter) }
                }
                cell(key = "counter") {
                    Subtable {
                        SCell {
                            SButton(
                                actionOrElseOrDisabled = model.counter.decrement.collectAsState().value,
                                titleOrIcon = TitleOrIcon.Icon(Drawable.Vector(Icons.Default.Remove)),
                            )
                        }
                        SCell(
                            modifier = Modifier.weight(1f),
                        ) {
                            SPanel {
                                SText(model.counter.value.collectAsState().value.toString())
                            }
                        }
                        SCell {
                            SButton(
                                actionOrElseOrDisabled = model.counter.increment.collectAsState().value,
                                titleOrIcon = TitleOrIcon.Icon(Drawable.Vector(Icons.Default.Add)),
                            )
                        }
                    }
                }
                item(key = "notes_header") {
                    STableHeader { SText(localization.notes) }
                }
                cell(key = "new_note") {
                    Subtable {
                        SCell(
                            modifier = Modifier.weight(1f),
                        ) {
                            newNote.Content()
                        }
                        SCell {
                            SButton(
                                actionOrElseOrDisabled = model.notes.add.collectAsState().value,
                                titleOrIcon = TitleOrIcon.Both(
                                    title = localization.addNote,
                                    icon = Drawable.Vector(Icons.Default.Add),
                                ),
                            )
                        }
                    }
                }
                cells(
                    items = notes,
                    key = { note -> "note_${note.title}" },
                ) { note ->
                    Subtable {
                        SCell(
                            modifier = Modifier.weight(1f),
                        ) {
                            SPanel {
                                SText(note.title)
                            }
                        }
                        SCell {
                            SButton(
                                actionOrElseOrDisabled = note.remove.collectAsState().value,
                                titleOrIcon = TitleOrIcon.Icon(Drawable.Vector(Icons.Default.Delete)),
                            )
                        }
                    }
                }
                item(key = "calls_header") {
                    STableHeader { SText(localization.calls) }
                }
                cell(key = "slow") {
                    SButton(
                        actionOrElseOrDisabled = model.calls.slow.collectAsState().value,
                        titleOrIcon = TitleOrIcon.Title(localization.slowCall),
                    )
                }
                cell(key = "fail") {
                    SButton(
                        actionOrElseOrDisabled = model.calls.fail.collectAsState().value,
                        titleOrIcon = TitleOrIcon.Title(localization.failingCall),
                    )
                }
            }
        }
    }
}
