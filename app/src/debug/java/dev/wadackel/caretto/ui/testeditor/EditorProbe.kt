package dev.wadackel.caretto.ui.testeditor

import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * One editor's link to the emulator scenarios: it reports the editor's own text and selection to
 * logcat, a line when it appears and one for each change after that, and each key press that
 * reaches the editor, and while the editor is on screen it lets [ScenarioReceiver] put it in a
 * given state and focus it.
 *
 * The scenarios read results from here rather than from a UI dump, which makes the service
 * disconnect and reconnect, and rather than from Caretto's log, which would be Caretto grading
 * itself. Debug builds only: release gets an empty stand-in from its own source set.
 *
 * Each line is a JSON object, because the text can hold newlines that logcat would split:
 * `{"editor":"edittext-multiline","text":"xab","selection":[3,3]}`, or for a key
 * `{"editor":"compose-state","key":"ctrl+h"}`. A chord Caretto remaps never produces a key line:
 * the service consumes it before any window sees it.
 */
internal class EditorProbe(private val editor: String) {

    private var last: String? = null
    private var setState: ((String, Int, Int) -> Unit)? = null

    fun report(text: CharSequence, selectionStart: Int, selectionEnd: Int) {
        val line = JSONObject()
            .put("editor", editor)
            .put("text", text.toString())
            .put("selection", JSONArray().put(selectionStart).put(selectionEnd))
            .toString()
        // A single keystroke reaches an EditText as both a text change and a selection change.
        if (line == last) return
        last = line
        Log.d(TAG, line)
    }

    /** A key press, written as modifiers then the key in tools/emu's chord syntax. */
    fun reportKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(event.keyCode)) return
        val chord = buildList {
            if (event.isCtrlPressed) add("ctrl")
            if (event.isAltPressed) add("alt")
            if (event.isShiftPressed) add("shift")
            if (event.isMetaPressed) add("meta")
            add(KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").lowercase())
        }.joinToString("+")
        Log.d(TAG, JSONObject().put("editor", editor).put("key", chord).toString())
    }

    /** [apply] replaces the text, sets the selection and focuses the editor, on the main thread. */
    fun attach(apply: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit) {
        setState = apply
        attached[editor] = this
    }

    fun detach() {
        setState = null
        if (attached[editor] === this) attached.remove(editor)
    }

    companion object {
        private const val TAG = "TestEditor"

        // Touched only on the main thread: composition and the receiver both run there.
        private val attached = HashMap<String, EditorProbe>()

        /** False when no editor of that name is on screen. */
        fun setState(editor: String, text: String, selectionStart: Int, selectionEnd: Int): Boolean {
            val apply = attached[editor]?.setState ?: return false
            apply(text, selectionStart, selectionEnd)
            return true
        }
    }
}
