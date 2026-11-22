package dev.wadackel.caretto.core

import android.accessibilityservice.InputMethod
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Applies an [EditPlan] through the accessibility InputConnection.
 *
 * sendKeyEvent() is confined here so mappings stay expressed as semantic editor operations and the
 * experimental path can be switched off wholesale.
 */
class EditorBridge(
    private val inputMethodProvider: () -> InputMethod?,
    private val tracker: EditorStateTracker,
    private val selectToDeleteProvider: () -> Boolean = { false },
) {

    fun execute(operation: EditorOperation, allowKeyEventFallback: Boolean): Result {
        val connection = inputMethodProvider()?.currentInputConnection
            ?: return Result.Failed("no InputConnection")

        return try {
            apply(planFor(operation, connection), connection, allowKeyEventFallback)
        } catch (error: RuntimeException) {
            Result.Failed("exception: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun planFor(
        operation: EditorOperation,
        connection: InputMethod.AccessibilityInputConnection,
    ): EditPlan = when (operation) {
        is EditorOperation.Menu -> EditPlan.ContextMenu(operation.action, "context menu")

        is EditorOperation.Text -> {
            val selectToDelete = selectToDeleteProvider()
            // Vertical movement never reads text, so it must not pay for the IPC round trip.
            if (operation.requiresKeyEventFallback) {
                EditPlanner.withoutText(operation)
            } else {
                val window = if (operation.usesWideWindow) WIDE_WINDOW else EDIT_WINDOW
                val snapshot = readSnapshot(connection, window)
                if (snapshot == null) {
                    EditPlanner.withoutText(operation)
                } else {
                    EditPlanner.plan(operation, snapshot, selectToDelete)
                }
            }
        }
    }

    private fun apply(
        plan: EditPlan,
        connection: InputMethod.AccessibilityInputConnection,
        allowKeyEventFallback: Boolean,
    ): Result = when (plan) {
        is EditPlan.Noop -> Result.Done(plan.detail)

        is EditPlan.Unsupported -> Result.Failed(plan.detail)

        is EditPlan.ReplaceRange -> {
            connection.setSelection(plan.start, plan.end)
            // An empty commit takes the selection with it. Unlike a delete key it does nothing
            // when the selection turns out to be empty, so a miss cannot eat the wrong character.
            connection.commitText("", 1, null)
            tracker.assumeCursor(plan.resultingCursor.coerceAtLeast(0))
            Result.Done("setSelection(${plan.start}, ${plan.end}) + commitText('') ${plan.detail}")
        }

        is EditPlan.SetSelection -> {
            val position = plan.position.coerceAtLeast(0)
            connection.setSelection(position, position)
            tracker.assumeCursor(position)
            Result.Done("setSelection($position) ${plan.detail}")
        }

        is EditPlan.DeleteSurrounding -> {
            connection.deleteSurroundingText(plan.before, plan.after)
            tracker.assumeCursor(plan.resultingCursor.coerceAtLeast(0))
            Result.Done("deleteSurroundingText(${plan.before}, ${plan.after}) ${plan.detail}")
        }

        is EditPlan.ContextMenu -> {
            connection.performContextMenuAction(menuIdOf(plan.action))
            val alsoKey = plan.action.keyEquivalent?.takeIf { allowKeyEventFallback }
            alsoKey?.let { sendKey(connection, it, CTRL) }
            // The call reports nothing back, so an editor that ignores the id looks identical to
            // one that acted on it. Only the screen can tell those apart.
            val sent = if (alsoKey == null) "" else " + sendKeyEvent(Ctrl+${KeyEvent.keyCodeToString(alsoKey).removePrefix("KEYCODE_")})"
            Result.Done("performContextMenuAction(${plan.action})$sent ${plan.detail}")
        }

        is EditPlan.KeyEventFallback -> if (!allowKeyEventFallback) {
            Result.Failed("${plan.detail}, fallback disabled")
        } else {
            sendKey(connection, plan.keyCode, plan.metaState)
            Result.Done("sendKeyEvent(${KeyEvent.keyCodeToString(plan.keyCode)}) ${plan.detail}")
        }
    }

    private fun menuIdOf(action: ContextMenuAction): Int = when (action) {
        ContextMenuAction.Copy -> android.R.id.copy
        ContextMenuAction.Cut -> android.R.id.cut
        ContextMenuAction.Paste -> android.R.id.paste
        ContextMenuAction.SelectAll -> android.R.id.selectAll
        ContextMenuAction.Undo -> android.R.id.undo
        ContextMenuAction.Redo -> android.R.id.redo
    }

    private fun readSnapshot(
        connection: InputMethod.AccessibilityInputConnection,
        window: Int,
    ): EditSnapshot? {
        val surrounding = connection.getSurroundingText(window, window, 0) ?: return null
        val start = surrounding.selectionStart
        val end = surrounding.selectionEnd
        if (start < 0 || end < 0) return null
        val length = surrounding.text.length
        if (start > length || end > length) return null
        // An editor may return text without knowing where it sits in the document. The tracked
        // cursor is absolute, so it can anchor the window when the editor cannot.
        val offset = when {
            surrounding.offset >= 0 -> surrounding.offset
            tracker.selectionStart >= 0 -> (tracker.selectionStart - minOf(start, end)).coerceAtLeast(0)
            else -> return null
        }
        return EditSnapshot(
            text = surrounding.text,
            relativeStart = minOf(start, end),
            relativeEnd = maxOf(start, end),
            offset = offset,
            requestedBefore = window,
            requestedAfter = window,
        )
    }

    private fun sendKey(
        connection: InputMethod.AccessibilityInputConnection,
        keyCode: Int,
        metaState: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(keyEvent(now, KeyEvent.ACTION_DOWN, keyCode, metaState))
        connection.sendKeyEvent(keyEvent(now, KeyEvent.ACTION_UP, keyCode, metaState))
    }

    private fun keyEvent(time: Long, action: Int, keyCode: Int, metaState: Int) = KeyEvent(
        time,
        time,
        action,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
    )

    sealed interface Result {
        val detail: String

        data class Done(override val detail: String) : Result
        data class Failed(override val detail: String) : Result
    }

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val EDIT_WINDOW = 64
        const val WIDE_WINDOW = 4096
    }
}
