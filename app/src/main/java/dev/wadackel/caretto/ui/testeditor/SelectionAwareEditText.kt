package dev.wadackel.caretto.ui.testeditor

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText

/**
 * An EditText that says when its selection moves, and which keys reach it.
 *
 * A TextWatcher only hears about text, and watching every frame to catch a bare cursor move costs
 * a callback per frame while a chord repeats — which is exactly what these fields exist to measure.
 */
class SelectionAwareEditText(context: Context) : EditText(context) {

    var onStateChanged: ((selectionStart: Int, selectionEnd: Int, length: Int) -> Unit)? = null
    var onKey: ((KeyEvent) -> Unit)? = null

    /**
     * The field's name for a screen reader. The visible label is a Compose Text, which has no View
     * for `labelFor` to point at, and a real hint would draw on the field.
     */
    var accessibleName: CharSequence? = null

    init {
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = report()
            },
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onKey?.invoke(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        accessibleName?.let { info.hintText = it }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        report()
    }

    private fun report() {
        onStateChanged?.invoke(selectionStart, selectionEnd, text?.length ?: 0)
    }
}
