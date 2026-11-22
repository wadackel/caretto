package dev.wadackel.caretto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import dev.wadackel.caretto.core.EditorStateTracker
import dev.wadackel.caretto.core.EventLog

/**
 * Accessibility-side InputMethod used purely to observe the editor session and to obtain an
 * AccessibilityInputConnection. Gboard remains the user's real IME.
 */
class RemapInputMethod(
    service: AccessibilityService,
    private val tracker: EditorStateTracker,
    private val verbose: () -> Boolean,
) : InputMethod(service) {

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        tracker.onStartInput(attribute, restarting)
        if (verbose()) {
            EventLog.add("onStartInput restarting=$restarting ${tracker.describe()}")
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (verbose()) EventLog.add("onFinishInput")
        tracker.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        tracker.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (verbose()) {
            EventLog.add(
                "onUpdateSelection sel=[$newSelStart,$newSelEnd] composing=[$candidatesStart,$candidatesEnd]",
            )
        }
    }
}
