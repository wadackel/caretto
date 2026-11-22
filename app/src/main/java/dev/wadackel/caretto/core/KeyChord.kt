package dev.wadackel.caretto.core

import android.view.KeyEvent

data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    /** At least one of Ctrl, Alt or Meta. Shift alone is ordinary typing. */
    val hasCommandModifier: Boolean
        get() = ctrl || alt || meta

    fun encode(): String {
        var bits = 0
        if (ctrl) bits = bits or CTRL_BIT
        if (alt) bits = bits or ALT_BIT
        if (shift) bits = bits or SHIFT_BIT
        if (meta) bits = bits or META_BIT
        return "$keyCode:$bits"
    }

    companion object {
        private const val CTRL_BIT = 1
        private const val ALT_BIT = 2
        private const val SHIFT_BIT = 4
        private const val META_BIT = 8

        fun decode(value: String): KeyChord? {
            val parts = value.split(':')
            if (parts.size != 2) return null
            val keyCode = parts[0].toIntOrNull() ?: return null
            val bits = parts[1].toIntOrNull() ?: return null
            if (keyCode <= 0) return null
            return KeyChord(
                keyCode = keyCode,
                ctrl = bits and CTRL_BIT != 0,
                alt = bits and ALT_BIT != 0,
                shift = bits and SHIFT_BIT != 0,
                meta = bits and META_BIT != 0,
            )
        }

        fun of(event: KeyEvent): KeyChord = KeyChord(
            keyCode = event.keyCode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            meta = event.isMetaPressed,
        )
    }
}
