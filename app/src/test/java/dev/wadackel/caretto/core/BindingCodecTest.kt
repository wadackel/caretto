package dev.wadackel.caretto.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingCodecTest {

    /** Every bindable action has to survive the trip through preferences, not just the editing ones. */
    @Test
    fun everyActionSurvivesARoundTrip() {
        val chord = KeyChord(keyCode = 36, ctrl = true, alt = true, shift = true, meta = true)
        MappedAction.ALL.forEach { action ->
            assertEquals("$action", chord to action, BindingCodec.decode(BindingCodec.encode(chord, action)))
        }
    }


    @Test
    fun aBindingSurvivesTheRoundTrip() {
        EditorOperation.ALL.forEach { operation ->
            val chord = KeyChord(KeyEvent.KEYCODE_K, ctrl = true, shift = true)
            assertEquals(chord to operation, BindingCodec.decode(BindingCodec.encode(chord, operation)))
        }
    }

    @Test
    fun theStoredFormIsReadable() {
        assertEquals(
            "34:1=MoveForward",
            BindingCodec.encode(KeyChord(KeyEvent.KEYCODE_F, ctrl = true), EditorOperation.MoveForward),
        )
    }

    /** Written by an earlier build that was never committed; the label is what keeps it bound. */
    @Test
    fun theStoredAllAppsBindingStillDecodes() {
        assertEquals(
            KeyChord(KeyEvent.KEYCODE_SPACE, meta = true) to GlobalAction.AllApps,
            BindingCodec.decode("62:8=AllApps"),
        )
    }

    @Test
    fun damagedEntriesAreDropped() {
        listOf(
            "",
            "34:1",
            "=MoveForward",
            "34:1=Bogus",
            "34=MoveForward",
            "nonsense=MoveForward",
            "34:1=",
        ).forEach { assertNull("decoded $it", BindingCodec.decode(it)) }
    }

    /** The stored form splits on these, so an operation label must never contain one. */
    @Test
    fun noOperationLabelCollidesWithTheSeparators() {
        EditorOperation.ALL.forEach { operation ->
            assertTrue(operation.label, operation.label.none { it == '=' || it == ':' })
            assertTrue(operation.label, operation.label.isNotEmpty())
        }
    }
}
