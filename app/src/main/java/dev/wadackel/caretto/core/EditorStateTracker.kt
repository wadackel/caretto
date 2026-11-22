package dev.wadackel.caretto.core

import android.view.inputmethod.EditorInfo

/**
 * Mirrors the editor session state reported by the accessibility InputMethod callbacks so that the
 * key handling hot path can decide without an IPC round trip.
 */
class EditorStateTracker {

    @Volatile
    var inputStarted: Boolean = false
        private set

    @Volatile
    var editorPackage: String? = null
        private set

    @Volatile
    var inputType: Int = EditorInfo.TYPE_NULL
        private set

    @Volatile
    var selectionStart: Int = -1
        private set

    @Volatile
    var selectionEnd: Int = -1
        private set

    @Volatile
    var candidatesStart: Int = -1
        private set

    @Volatile
    var candidatesEnd: Int = -1
        private set

    val hasSelection: Boolean
        get() = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd

    val composing: Boolean
        get() = candidatesStart >= 0 && candidatesEnd >= 0 && candidatesStart != candidatesEnd

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        inputStarted = true
        editorPackage = info?.packageName
        inputType = info?.inputType ?: EditorInfo.TYPE_NULL
        // WebView-backed editors restart the connection on nearly every keystroke and report a
        // fresh cursor position each time; preferring the cached value there leaves it stale.
        val reportedStart = info?.initialSelStart ?: -1
        val reportedEnd = info?.initialSelEnd ?: -1
        if (reportedStart >= 0 && reportedEnd >= 0) {
            selectionStart = reportedStart
            selectionEnd = reportedEnd
        } else if (!restarting) {
            selectionStart = -1
            selectionEnd = -1
        }
        // WebView-backed editors restart on nearly every keystroke while a composition is still
        // live. Clearing here would leave the tracker blind to composing text in exactly those
        // editors, which is where deletions must not be aimed.
        if (!restarting) {
            candidatesStart = -1
            candidatesEnd = -1
        }
    }

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        this.candidatesStart = candidatesStart
        this.candidatesEnd = candidatesEnd
    }

    fun onFinishInput() {
        inputStarted = false
        editorPackage = null
        inputType = EditorInfo.TYPE_NULL
        selectionStart = -1
        selectionEnd = -1
        candidatesStart = -1
        candidatesEnd = -1
    }

    /**
     * onUpdateSelection arrives asynchronously; without this the next chord in a fast repeat would
     * still read the pre-edit cursor position.
     */
    fun assumeCursor(position: Int) {
        selectionStart = position
        selectionEnd = position
    }

    fun describe(): String =
        "started=$inputStarted pkg=${editorPackage ?: "-"} inputType=${describeInputType(inputType)} " +
            "sel=[$selectionStart,$selectionEnd] composing=[$candidatesStart,$candidatesEnd]"

    private fun describeInputType(value: Int): String {
        if (value == EditorInfo.TYPE_NULL) return "NULL"
        val className = when (value and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> "TEXT"
            EditorInfo.TYPE_CLASS_NUMBER -> "NUMBER"
            EditorInfo.TYPE_CLASS_PHONE -> "PHONE"
            EditorInfo.TYPE_CLASS_DATETIME -> "DATETIME"
            else -> "UNKNOWN"
        }
        return "$className(0x${Integer.toHexString(value)})"
    }
}
