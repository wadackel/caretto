package dev.wadackel.caretto.ui.testeditor

import android.view.KeyEvent

/** Release builds neither report editor state nor accept one; the debug source set has the real one. */
@Suppress("UNUSED_PARAMETER")
internal class EditorProbe(editor: String) {

    fun report(text: CharSequence, selectionStart: Int, selectionEnd: Int) = Unit

    fun reportKey(event: KeyEvent) = Unit

    fun attach(apply: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit) = Unit

    fun detach() = Unit
}
