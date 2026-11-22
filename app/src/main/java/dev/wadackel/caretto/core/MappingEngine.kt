package dev.wadackel.caretto.core

import android.view.KeyEvent

class MappingEngine(private val mappings: Map<KeyChord, MappedAction>) {

    fun resolve(chord: KeyChord): MappedAction? = mappings[chord]

    companion object {
        private fun ctrl(keyCode: Int) = KeyChord(keyCode = keyCode, ctrl = true)

        private fun alt(keyCode: Int) = KeyChord(keyCode = keyCode, alt = true)

        /**
         * All four modifiers at once, which a keyboard can be made to send as a single key.
         *
         * Nothing on Android claims this combination, so it is free for the operations that would
         * otherwise collide with the system's own Meta shortcuts.
         */
        private fun hyper(keyCode: Int) =
            KeyChord(keyCode = keyCode, ctrl = true, alt = true, shift = true, meta = true)

        /** The preset, keyed by chord because a chord may only mean one thing at a time. */
        val DEFAULT_BINDINGS: Map<KeyChord, MappedAction> = linkedMapOf(
            ctrl(KeyEvent.KEYCODE_B) to EditorOperation.MoveBackward,
            ctrl(KeyEvent.KEYCODE_F) to EditorOperation.MoveForward,
            ctrl(KeyEvent.KEYCODE_P) to EditorOperation.MoveUp,
            ctrl(KeyEvent.KEYCODE_N) to EditorOperation.MoveDown,
            ctrl(KeyEvent.KEYCODE_A) to EditorOperation.MoveLineStart,
            ctrl(KeyEvent.KEYCODE_E) to EditorOperation.MoveLineEnd,
            ctrl(KeyEvent.KEYCODE_H) to EditorOperation.DeleteBackward,
            ctrl(KeyEvent.KEYCODE_D) to EditorOperation.DeleteForward,
            ctrl(KeyEvent.KEYCODE_K) to EditorOperation.DeleteToLineEnd,
            // Emacs puts the word-scoped F, B and D on Meta, which is Alt here. Alt+H is not
            // Emacs — it pairs with Ctrl+H being backspace in this app.
            alt(KeyEvent.KEYCODE_B) to EditorOperation.MoveWordBackward,
            alt(KeyEvent.KEYCODE_F) to EditorOperation.MoveWordForward,
            alt(KeyEvent.KEYCODE_D) to EditorOperation.DeleteWordForward,
            alt(KeyEvent.KEYCODE_H) to EditorOperation.DeleteWordBackward,
            // The macOS clipboard, on a modifier the system does not already spend.
            hyper(KeyEvent.KEYCODE_C) to EditorOperation.Copy,
            hyper(KeyEvent.KEYCODE_X) to EditorOperation.Cut,
            hyper(KeyEvent.KEYCODE_V) to EditorOperation.Paste,
            hyper(KeyEvent.KEYCODE_A) to EditorOperation.SelectAll,
            hyper(KeyEvent.KEYCODE_Z) to EditorOperation.Undo,
            // Shift is already spent on Hyper itself, so redo cannot be the shifted undo
            // it is on macOS. Y is the other convention for it.
            hyper(KeyEvent.KEYCODE_Y) to EditorOperation.Redo,
            hyper(KeyEvent.KEYCODE_H) to GlobalAction.Home,
        )
    }
}
