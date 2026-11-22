package dev.wadackel.caretto.core

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTrackerTest {

    private val tracker = EditorStateTracker()

    @Test
    fun aRestartingEditorRefreshesTheCachedCursor() {
        tracker.onStartInput(editorInfo(320, 320), restarting = false)
        assertEquals(320, tracker.selectionStart)

        tracker.onStartInput(editorInfo(341, 341), restarting = true)
        assertEquals(341, tracker.selectionStart)
        assertEquals(341, tracker.selectionEnd)
    }

    @Test
    fun aRestartWithoutAReportedCursorKeepsWhatWeKnow() {
        tracker.onStartInput(editorInfo(10, 10), restarting = false)
        tracker.onStartInput(editorInfo(-1, -1), restarting = true)
        assertEquals(10, tracker.selectionStart)
    }

    @Test
    fun selectionStateFollowsTheEditorUpdates() {
        tracker.onStartInput(editorInfo(0, 0), restarting = false)
        assertFalse(tracker.hasSelection)

        tracker.onUpdateSelection(3, 7, -1, -1)
        assertTrue(tracker.hasSelection)
        assertFalse(tracker.composing)

        tracker.onUpdateSelection(7, 7, 4, 7)
        assertFalse(tracker.hasSelection)
        assertTrue(tracker.composing)
    }

    /** WebView editors restart on every keystroke; clearing here would hide live composing text. */
    @Test
    fun aRestartKeepsTheComposingRegion() {
        tracker.onStartInput(editorInfo(0, 0), restarting = false)
        tracker.onUpdateSelection(4, 4, 0, 4)
        assertTrue(tracker.composing)

        tracker.onStartInput(editorInfo(4, 4), restarting = true)
        assertTrue("a restart must not hide the composition", tracker.composing)

        tracker.onStartInput(editorInfo(4, 4), restarting = false)
        assertFalse("a fresh editor starts with none", tracker.composing)
    }

    @Test
    fun finishingInputDropsEverything() {
        tracker.onStartInput(editorInfo(5, 9), restarting = false)
        tracker.onFinishInput()
        assertFalse(tracker.inputStarted)
        assertFalse(tracker.hasSelection)
        assertEquals(-1, tracker.selectionStart)
    }

    private fun editorInfo(start: Int, end: Int) = EditorInfo().apply {
        initialSelStart = start
        initialSelEnd = end
        packageName = "md.obsidian"
        inputType = EditorInfo.TYPE_CLASS_TEXT
    }
}
