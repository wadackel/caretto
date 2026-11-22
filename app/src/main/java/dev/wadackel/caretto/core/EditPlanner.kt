package dev.wadackel.caretto.core

import android.view.KeyEvent

/**
 * Turns an [EditorOperation.Text] plus the surrounding text into a concrete editing action.
 *
 * Kept free of Android IPC so the boundary, selection and line-edge arithmetic can be exercised
 * without a device.
 */
object EditPlanner {

    // Editors mostly test META_CTRL_ON, but some compare the side-specific bit instead.
    private val WORD_SCOPE_MODIFIER = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    /**
     * @param selectToDelete rewrite forward deletions as a selection followed by an empty commit,
     *   for editors that ignore the after half of deleteSurroundingText.
     */
    fun plan(
        operation: EditorOperation.Text,
        snapshot: EditSnapshot,
        selectToDelete: Boolean = false,
    ): EditPlan = planOutsideComposition(operation, snapshot, selectToDelete)

    private fun planOutsideComposition(
        operation: EditorOperation.Text,
        snapshot: EditSnapshot,
        selectToDelete: Boolean,
    ): EditPlan = when (operation) {
        EditorOperation.MoveForward -> move(snapshot, forward = true)
        EditorOperation.MoveBackward -> move(snapshot, forward = false)
        EditorOperation.MoveWordForward -> moveWord(snapshot, forward = true)
        EditorOperation.MoveWordBackward -> moveWord(snapshot, forward = false)
        EditorOperation.DeleteWordForward -> deleteWord(snapshot, backward = false, selectToDelete)
        EditorOperation.DeleteWordBackward -> deleteWord(snapshot, backward = true, selectToDelete = false)
        EditorOperation.DeleteForward -> delete(snapshot, backward = false, selectToDelete)
        EditorOperation.DeleteBackward -> delete(snapshot, backward = true, selectToDelete = false)
        EditorOperation.MoveLineStart -> lineEdge(snapshot, toStart = true)
        EditorOperation.MoveLineEnd -> lineEdge(snapshot, toStart = false)
        EditorOperation.DeleteToLineEnd -> killToLineEnd(snapshot, selectToDelete)
        EditorOperation.MoveUp, EditorOperation.MoveDown -> withoutText(operation)
    }

    /**
     * Plan for an editor that will not hand over its surrounding text.
     *
     * Terminal emulators and other custom InputConnections are within their rights to return
     * nothing from getSurroundingText(); a key event is the only remaining way to express the
     * operation, and it happens to be what those editors expect anyway.
     */
    fun withoutText(operation: EditorOperation.Text): EditPlan {
        // Killing to the end of a line has no single-key equivalent, and composing one out of
        // Shift+End then Delete would depend on an editor that already withholds its text.
        if (operation == EditorOperation.DeleteToLineEnd) {
            return EditPlan.Unsupported("no key event kills to the line end")
        }
        val keyCode = when (operation) {
            EditorOperation.MoveForward -> KeyEvent.KEYCODE_DPAD_RIGHT
            EditorOperation.MoveBackward -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorOperation.DeleteBackward -> KeyEvent.KEYCODE_DEL
            EditorOperation.DeleteForward -> KeyEvent.KEYCODE_FORWARD_DEL
            EditorOperation.MoveLineStart -> KeyEvent.KEYCODE_MOVE_HOME
            EditorOperation.MoveLineEnd -> KeyEvent.KEYCODE_MOVE_END
            EditorOperation.MoveUp -> KeyEvent.KEYCODE_DPAD_UP
            EditorOperation.MoveDown -> KeyEvent.KEYCODE_DPAD_DOWN
            EditorOperation.MoveWordForward -> KeyEvent.KEYCODE_DPAD_RIGHT
            EditorOperation.MoveWordBackward -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorOperation.DeleteWordForward -> KeyEvent.KEYCODE_FORWARD_DEL
            EditorOperation.DeleteWordBackward -> KeyEvent.KEYCODE_DEL
            EditorOperation.DeleteToLineEnd -> error("handled above")
        }
        val reason = if (operation.requiresKeyEventFallback) "visual movement" else "no surrounding text"
        // Editors read a bare arrow or delete as a character step; Ctrl is what makes it a word.
        val metaState = if (operation.isWordScoped) WORD_SCOPE_MODIFIER else 0
        return EditPlan.KeyEventFallback(keyCode, reason, metaState)
    }

    private fun move(snapshot: EditSnapshot, forward: Boolean): EditPlan {
        if (snapshot.hasSelection) {
            val collapsed = if (forward) snapshot.absoluteEnd else snapshot.absoluteStart
            return EditPlan.SetSelection(collapsed, "collapse selection")
        }
        val step = if (forward) {
            TextBoundary.nextClusterLength(snapshot.textAfter)
        } else {
            TextBoundary.previousClusterLength(snapshot.textBefore)
        }
        if (step == 0) return EditPlan.Noop("already at edge")
        val target = if (forward) snapshot.absoluteEnd + step else snapshot.absoluteStart - step
        return EditPlan.SetSelection(target, "move ${if (forward) "forward" else "backward"} $step")
    }

    private fun moveWord(snapshot: EditSnapshot, forward: Boolean): EditPlan {
        if (snapshot.hasSelection) {
            val collapsed = if (forward) snapshot.absoluteEnd else snapshot.absoluteStart
            return EditPlan.SetSelection(collapsed, "collapse selection")
        }
        val step = wordStep(snapshot, forward) ?: return EditPlan.Unsupported("word exceeds window")
        if (step == 0) return EditPlan.Noop("already at edge")
        val target = if (forward) snapshot.absoluteEnd + step else snapshot.absoluteStart - step
        return EditPlan.SetSelection(target, "move ${if (forward) "forward" else "backward"} $step by word")
    }

    private fun deleteWord(snapshot: EditSnapshot, backward: Boolean, selectToDelete: Boolean): EditPlan {
        if (snapshot.hasSelection) return removeSelection(snapshot)
        val step = wordStep(snapshot, !backward) ?: return EditPlan.Unsupported("word exceeds window")
        if (step == 0) return EditPlan.Noop("nothing to delete")
        return if (backward) {
            EditPlan.DeleteSurrounding(step, 0, snapshot.absoluteStart - step, "delete $step before by word")
        } else {
            removeAfter(snapshot, step, selectToDelete, "delete $step after by word")
        }
    }

    /** Null when the answer runs into the edge of the window and so cannot be trusted. */
    private fun wordStep(snapshot: EditSnapshot, forward: Boolean): Int? {
        if (forward) {
            val after = snapshot.textAfter
            val step = TextBoundary.nextWordLength(after)
            if (step == after.length && snapshot.hasMoreAfter) return null
            return step
        }
        val before = snapshot.textBefore
        val step = TextBoundary.previousWordLength(before)
        if (step == before.length && snapshot.hasMoreBefore) return null
        return step
    }

    /**
     * Every deletion means "the selection goes" when there is one.
     *
     * deleteSurroundingText spares the selected range, so it cannot say this at all. Replacing the
     * range with nothing says it without a key event, and so without depending on how an editor
     * reads one.
     */
    private fun removeSelection(snapshot: EditSnapshot) = EditPlan.ReplaceRange(
        snapshot.absoluteStart,
        snapshot.absoluteEnd,
        snapshot.absoluteStart,
        "delete selection",
    )

    /** Removes [length] characters after the cursor by whichever route the editor honours. */
    private fun removeAfter(snapshot: EditSnapshot, length: Int, selectToDelete: Boolean, detail: String) =
        if (selectToDelete) {
            EditPlan.ReplaceRange(
                snapshot.absoluteStart,
                snapshot.absoluteStart + length,
                snapshot.absoluteStart,
                detail,
            )
        } else {
            EditPlan.DeleteSurrounding(0, length, snapshot.absoluteStart, detail)
        }

    private fun delete(snapshot: EditSnapshot, backward: Boolean, selectToDelete: Boolean): EditPlan {
        if (snapshot.hasSelection) return removeSelection(snapshot)
        val length = if (backward) {
            TextBoundary.previousClusterLength(snapshot.textBefore)
        } else {
            TextBoundary.nextClusterLength(snapshot.textAfter)
        }
        if (length == 0) return EditPlan.Noop("nothing to delete")
        return if (backward) {
            EditPlan.DeleteSurrounding(length, 0, snapshot.absoluteStart - length, "delete $length before")
        } else {
            removeAfter(snapshot, length, selectToDelete, "delete $length after")
        }
    }

    /**
     * Emacs kill-to-line-end: remove the rest of the line, or the line break itself when the
     * cursor already sits at the end of one.
     */
    private fun killToLineEnd(snapshot: EditSnapshot, selectToDelete: Boolean): EditPlan {
        if (snapshot.hasSelection) return removeSelection(snapshot)

        val after = snapshot.textAfter
        val lineBreak = TextBoundary.indexOfLineBreakStart(after)
        return when {
            lineBreak > 0 -> removeAfter(snapshot, lineBreak, selectToDelete, "kill to line end")
            lineBreak == 0 -> removeAfter(
                snapshot,
                TextBoundary.lineBreakLengthAt(after, 0),
                selectToDelete,
                "kill the line break",
            )
            snapshot.hasMoreAfter -> EditPlan.Unsupported("line exceeds window")
            after.isEmpty() -> EditPlan.Noop("nothing to kill")
            else -> removeAfter(snapshot, after.length, selectToDelete, "kill to document end")
        }
    }

    private fun lineEdge(snapshot: EditSnapshot, toStart: Boolean): EditPlan {
        if (toStart) {
            val before = snapshot.textBefore
            val newline = TextBoundary.lastIndexOfNewline(before)
            if (newline >= 0) {
                return EditPlan.SetSelection(
                    snapshot.absoluteStart - (before.length - newline - 1),
                    "logical line start",
                )
            }
            if (snapshot.hasMoreBefore) {
                return EditPlan.KeyEventFallback(KeyEvent.KEYCODE_MOVE_HOME, "line exceeds window")
            }
            return EditPlan.SetSelection(0, "document start")
        }

        val after = snapshot.textAfter
        val lineBreak = TextBoundary.indexOfLineBreakStart(after)
        if (lineBreak >= 0) {
            return EditPlan.SetSelection(snapshot.absoluteEnd + lineBreak, "logical line end")
        }
        if (snapshot.hasMoreAfter) {
            return EditPlan.KeyEventFallback(KeyEvent.KEYCODE_MOVE_END, "line exceeds window")
        }
        return EditPlan.SetSelection(snapshot.absoluteEnd + after.length, "document end")
    }
}
