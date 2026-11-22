package dev.wadackel.caretto.ui

import android.view.KeyEvent
import androidx.annotation.StringRes
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.EditorOperation
import dev.wadackel.caretto.core.GlobalAction
import dev.wadackel.caretto.core.MappedAction
import dev.wadackel.caretto.core.KeyChord

/**
 * The parts of a chord, in the order they are written: modifiers first, then the key.
 *
 * All four modifiers together are shown as one Hyper, which is both what the key that sends them
 * is called and the only way a chord using it fits on a line beside what it does.
 */
fun chordParts(chord: KeyChord): List<String> = buildList {
    if (chord.ctrl && chord.alt && chord.shift && chord.meta) {
        add("Hyper")
    } else {
        if (chord.ctrl) add("Ctrl")
        if (chord.alt) add("Alt")
        if (chord.shift) add("Shift")
        if (chord.meta) add("Meta")
    }
    add(KeyEvent.keyCodeToString(chord.keyCode).removePrefix("KEYCODE_"))
}

fun describe(chord: KeyChord): String = chordParts(chord).joinToString("+")

@StringRes
fun operationLabel(action: MappedAction): Int = when (action) {
    EditorOperation.MoveBackward -> R.string.op_move_backward
    EditorOperation.MoveForward -> R.string.op_move_forward
    EditorOperation.MoveUp -> R.string.op_move_up
    EditorOperation.MoveDown -> R.string.op_move_down
    EditorOperation.MoveWordBackward -> R.string.op_move_word_backward
    EditorOperation.MoveWordForward -> R.string.op_move_word_forward
    EditorOperation.MoveLineStart -> R.string.op_move_line_start
    EditorOperation.MoveLineEnd -> R.string.op_move_line_end
    EditorOperation.DeleteBackward -> R.string.op_delete_backward
    EditorOperation.DeleteForward -> R.string.op_delete_forward
    EditorOperation.DeleteWordBackward -> R.string.op_delete_word_backward
    EditorOperation.DeleteWordForward -> R.string.op_delete_word_forward
    EditorOperation.DeleteToLineEnd -> R.string.op_delete_to_line_end
    EditorOperation.Copy -> R.string.op_copy
    EditorOperation.Cut -> R.string.op_cut
    EditorOperation.Paste -> R.string.op_paste
    EditorOperation.SelectAll -> R.string.op_select_all
    EditorOperation.Undo -> R.string.op_undo
    EditorOperation.Redo -> R.string.op_redo
    GlobalAction.Home -> R.string.op_home
    GlobalAction.Recents -> R.string.op_recents
    GlobalAction.Back -> R.string.op_back
    GlobalAction.AllApps -> R.string.op_all_apps
}

/** The groups the key map is shown in, in the order they appear. */
enum class OperationGroup(@param:StringRes val label: Int) {
    Move(R.string.group_move),
    Delete(R.string.group_delete),
    Edit(R.string.group_edit),
    System(R.string.group_system),
}

fun operationGroup(action: MappedAction): OperationGroup = when (action) {
    EditorOperation.MoveBackward, EditorOperation.MoveForward, EditorOperation.MoveUp,
    EditorOperation.MoveDown, EditorOperation.MoveWordBackward, EditorOperation.MoveWordForward,
    EditorOperation.MoveLineStart, EditorOperation.MoveLineEnd,
    -> OperationGroup.Move

    EditorOperation.DeleteBackward, EditorOperation.DeleteForward, EditorOperation.DeleteWordBackward,
    EditorOperation.DeleteWordForward, EditorOperation.DeleteToLineEnd,
    -> OperationGroup.Delete

    EditorOperation.Copy, EditorOperation.Cut, EditorOperation.Paste, EditorOperation.SelectAll,
    EditorOperation.Undo, EditorOperation.Redo,
    -> OperationGroup.Edit

    is GlobalAction -> OperationGroup.System
}
