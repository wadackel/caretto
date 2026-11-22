package dev.wadackel.caretto.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingEngineTest {

    private val engine = MappingEngine(MappingEngine.DEFAULT_BINDINGS)

    @Test
    fun presetChordsResolveToEmacsOperations() {
        assertEquals(
            EditorOperation.DeleteBackward,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_H, ctrl = true)),
        )
        assertEquals(
            EditorOperation.DeleteToLineEnd,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_K, ctrl = true)),
        )
        assertEquals(
            EditorOperation.MoveLineEnd,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_E, ctrl = true)),
        )
    }

    @Test
    fun theWordScopedPresetSitsOnAlt() {
        assertEquals(
            EditorOperation.MoveWordForward,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_F, alt = true)),
        )
        assertEquals(
            EditorOperation.DeleteWordBackward,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_H, alt = true)),
        )
    }

    @Test
    fun everyOperationHasAPresetBinding() {
        val bound = MappingEngine.DEFAULT_BINDINGS.values.toSet()
        EditorOperation.ALL.forEach { assertTrue("$it is unbound", it in bound) }
    }

    /** Only the two the context menu does not carry everywhere need a chord sent alongside. */
    @Test
    fun onlyUndoAndRedoCarryAKeyEquivalent() {
        assertEquals(KeyEvent.KEYCODE_Z, ContextMenuAction.Undo.keyEquivalent)
        assertEquals(KeyEvent.KEYCODE_Y, ContextMenuAction.Redo.keyEquivalent)
        listOf(
            ContextMenuAction.Copy,
            ContextMenuAction.Cut,
            ContextMenuAction.Paste,
            ContextMenuAction.SelectAll,
        ).forEach { assertNull("$it", it.keyEquivalent) }
    }

    /**
     * Hyper is all four modifiers, Shift included.
     *
     * A firmware sends the whole set from one key, so a preset that leaves Shift out matches
     * nothing at all — which is exactly what happened the first time these were written.
     */
    @Test
    fun everyHyperPresetCarriesAllFourModifiers() {
        val hyperBound = setOf(
            EditorOperation.Copy,
            EditorOperation.Cut,
            EditorOperation.Paste,
            EditorOperation.SelectAll,
            EditorOperation.Undo,
            EditorOperation.Redo,
            GlobalAction.Home,
        )
        MappingEngine.DEFAULT_BINDINGS.filterValues { it in hyperBound }.forEach { (chord, action) ->
            assertTrue("$action", chord.ctrl && chord.alt && chord.shift && chord.meta)
        }
        assertEquals(hyperBound.size, MappingEngine.DEFAULT_BINDINGS.count { it.value in hyperBound })
    }

    /**
     * Holding a key means "again" only for moving and deleting.
     *
     * A repeating paste is a mess to undo, and a repeating Home would leave the device unusable
     * for as long as the key was down.
     */
    @Test
    fun onlyTextOperationsRepeatWhileHeld() {
        EditorOperation.TEXT.forEach { assertTrue("$it", it.repeats) }
        (EditorOperation.ALL - EditorOperation.TEXT.toSet()).forEach { assertFalse("$it", it.repeats) }
        GlobalAction.ALL.forEach { assertFalse("$it", it.repeats) }
    }

    /** The other global actions are offered but not spent: which chord they deserve is a matter of taste. */
    @Test
    fun onlyGoingHomeIsPresetAmongTheGlobalActions() {
        val bound = MappingEngine.DEFAULT_BINDINGS.values.toSet()
        assertTrue(GlobalAction.Home in bound)
        assertFalse(GlobalAction.Recents in bound)
        assertFalse(GlobalAction.Back in bound)
        assertFalse(GlobalAction.AllApps in bound)
    }

    @Test
    fun extraModifiersDoNotMatch() {
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_F, ctrl = true, shift = true)))
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_F, ctrl = true, alt = true)))
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_F, ctrl = true, meta = true)))
    }

    @Test
    fun unmappedKeysArePassedThrough() {
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_F)))
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_G, ctrl = true)))
        assertNull(engine.resolve(KeyChord(KeyEvent.KEYCODE_C, ctrl = true)))
    }

    @Test
    fun severalChordsMayShareOneOperation() {
        val engine = MappingEngine(
            mapOf(
                KeyChord(KeyEvent.KEYCODE_B, ctrl = true) to EditorOperation.MoveBackward,
                KeyChord(KeyEvent.KEYCODE_DPAD_LEFT, alt = true) to EditorOperation.MoveBackward,
            ),
        )
        assertEquals(EditorOperation.MoveBackward, engine.resolve(KeyChord(KeyEvent.KEYCODE_B, ctrl = true)))
        assertEquals(
            EditorOperation.MoveBackward,
            engine.resolve(KeyChord(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)),
        )
    }

    @Test
    fun everyOperationIsListedAndResolvableByLabel() {
        assertEquals(EditorOperation.ALL.size, EditorOperation.ALL.distinct().size)
        EditorOperation.ALL.forEach { operation ->
            assertEquals(operation, EditorOperation.fromLabel(operation.label))
        }
        assertNull(EditorOperation.fromLabel("NoSuchOperation"))
        assertNull(EditorOperation.fromLabel(""))
    }

    @Test
    fun everyPresetBindingCarriesACommandModifier() {
        MappingEngine.DEFAULT_BINDINGS.keys.forEach { chord ->
            assertTrue("$chord needs Ctrl, Alt or Meta", chord.hasCommandModifier)
        }
        assertFalse(KeyChord(KeyEvent.KEYCODE_A).hasCommandModifier)
        assertFalse(KeyChord(KeyEvent.KEYCODE_A, shift = true).hasCommandModifier)
    }

    @Test
    fun aChordSurvivesEncodingAndDecoding() {
        listOf(
            KeyChord(KeyEvent.KEYCODE_H, ctrl = true),
            KeyChord(KeyEvent.KEYCODE_F, ctrl = true, shift = true),
            KeyChord(KeyEvent.KEYCODE_1, alt = true, meta = true),
            KeyChord(KeyEvent.KEYCODE_SLASH, ctrl = true, alt = true, shift = true, meta = true),
        ).forEach { chord ->
            assertEquals(chord, KeyChord.decode(chord.encode()))
        }
    }

    @Test
    fun brokenStoredValuesDecodeToNothing() {
        listOf("", "abc", "36", "36:", ":1", "36:1:2", "-1:1", "x:1", "36:y")
            .forEach { assertNull("decoded $it", KeyChord.decode(it)) }
    }

    @Test
    fun onlyVerticalMovementNeedsTheKeyEventFallback() {
        assertTrue(EditorOperation.MoveUp.requiresKeyEventFallback)
        assertTrue(EditorOperation.MoveDown.requiresKeyEventFallback)
        (EditorOperation.TEXT - EditorOperation.MoveUp - EditorOperation.MoveDown).forEach {
            assertFalse("$it", it.requiresKeyEventFallback)
        }
    }
}
