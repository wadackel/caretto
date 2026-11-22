package dev.wadackel.caretto.core

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Tracks which physical keys were consumed on ACTION_DOWN so their ACTION_UP is consumed as well.
 *
 * Android warns that dropping half of a DOWN/UP pair leaves the downstream key event stream
 * inconsistent, so the decision is made once per key press and replayed for the release.
 */
class KeyFilter {

    private val consumed = HashMap<Long, MappedAction>()

    /**
     * Presses taken by the capture dialog, which answer to no action.
     *
     * Kept apart from [consumed] because a captured key must not repeat and has nothing to repeat,
     * but its release still has to be swallowed — a DOWN taken here with its UP left loose would
     * strand the entry and fire it at some later press of the same key.
     */
    private val captured = HashSet<Long>()

    /**
     * Id 0 is the built-in keyboard, which is physical too: an emulator's keyboard has it. The
     * guard is there to keep out Caretto's own `sendKeyEvent`, which carries `VIRTUAL_KEYBOARD`
     * (-1), so `> 0` would be stricter than that requires.
     */
    fun isPhysicalKeyboardEvent(event: KeyEvent): Boolean =
        event.deviceId >= 0 && event.isFromSource(InputDevice.SOURCE_KEYBOARD)

    fun pendingOperation(event: KeyEvent): MappedAction? = consumed[idOf(event)]

    fun markConsumed(event: KeyEvent, operation: MappedAction) {
        consumed[idOf(event)] = operation
    }

    /** Marks a press taken by the capture dialog, so its release is taken too. */
    fun markCaptured(event: KeyEvent) {
        captured += idOf(event)
    }

    fun releaseIfConsumed(event: KeyEvent): Boolean {
        val id = idOf(event)
        // Both halves are asked, because a release must be swallowed whichever took the press.
        val hadAction = consumed.remove(id) != null
        val wasCaptured = captured.remove(id)
        return hadAction || wasCaptured
    }

    fun reset() {
        consumed.clear()
        captured.clear()
    }

    private fun idOf(event: KeyEvent): Long =
        (event.deviceId.toLong() shl 32) or (event.keyCode.toLong() and 0xFFFFFFFFL)
}
