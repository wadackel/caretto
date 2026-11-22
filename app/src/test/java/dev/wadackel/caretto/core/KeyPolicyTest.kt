package dev.wadackel.caretto.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyPolicyTest {

    private val editable = InputContext.Editable("com.example.editor")

    @Test
    fun aBoundChordInAnEditorIsTaken() {
        EditorOperation.ALL.forEach { operation ->
            assertEquals("$operation", remap(operation), decide(operation))
        }
    }

    @Test
    fun anUnboundChordIsLeftAlone() {
        assertEquals(KeyDecision.PassThrough("no binding"), decide(null))
    }

    @Test
    fun everyContextButAnEditorIsLeftAlone() {
        listOf(
            InputContext.RawApp("com.server.auditor.ssh.client"),
            InputContext.NoEditor,
            InputContext.Unknown,
        ).forEach { context ->
            val decision = KeyPolicy.decide(EditorOperation.MoveForward, context, false, true)
            assertEquals("$context", KeyDecision.PassThrough::class, decision::class)
        }
    }

    /** The IME owns its composing text; nothing here can edit it, so deletions must reach it. */
    @Test
    fun deletionIsLeftToTheImeWhileComposing() {
        EditorOperation.TEXT.filter { it.deletes }.forEach { operation ->
            assertEquals(
                "$operation",
                KeyDecision.PassThrough("composing"),
                decide(operation, composing = true),
            )
        }
    }

    @Test
    fun movementIsStillTakenWhileComposing() {
        EditorOperation.TEXT.filterNot { it.deletes }.forEach { operation ->
            assertEquals("$operation", remap(operation), decide(operation, composing = true))
        }
    }

    @Test
    fun withoutTheFallbackOnlyWhatNeedsItIsLeftAlone() {
        EditorOperation.TEXT.forEach { operation ->
            val decision = decide(operation, fallbackEnabled = false)
            if (operation.requiresKeyEventFallback) {
                assertEquals("$operation", KeyDecision.PassThrough("fallback disabled"), decision)
            } else {
                assertEquals("$operation", remap(operation), decision)
            }
        }
    }

    /** A raw app is answered before anything else, so nothing there is ever consumed. */
    @Test
    fun aRawAppOutranksEveryOtherReason() {
        val decision = KeyPolicy.decide(
            EditorOperation.DeleteBackward,
            InputContext.RawApp("com.termux"),
            composing = true,
            fallbackEnabled = false,
        )
        assertEquals(KeyDecision.PassThrough("raw app com.termux"), decision)
    }

    /** Going home from a terminal, or from a screen with nothing focused, is the whole point. */
    @Test
    fun aGlobalActionIsTakenInEveryContext() {
        listOf(
            InputContext.RawApp("com.termux"),
            InputContext.NoEditor,
            InputContext.Unknown,
            editable,
        ).forEach { context ->
            GlobalAction.ALL.forEach { action ->
                assertEquals(
                    "$action in $context",
                    KeyDecision.Remap(action),
                    KeyPolicy.decide(action, context, composing = true, fallbackEnabled = false),
                )
            }
        }
    }

    @Test
    fun rewritingTheTextIsLeftToTheImeWhileComposing() {
        listOf(
            EditorOperation.Cut,
            EditorOperation.Paste,
            EditorOperation.Undo,
            EditorOperation.Redo,
        ).forEach { operation ->
            assertEquals(
                "$operation",
                KeyDecision.PassThrough("composing"),
                decide(operation, composing = true),
            )
        }
    }

    /** Neither reads nor writes the composing span, so the IME has no stake in them. */
    @Test
    fun copyingAndSelectingSurviveComposing() {
        listOf(EditorOperation.Copy, EditorOperation.SelectAll).forEach { operation ->
            assertEquals("$operation", remap(operation), decide(operation, composing = true))
        }
    }

    private fun remap(action: MappedAction) = KeyDecision.Remap(action)

    private fun decide(
        action: MappedAction?,
        composing: Boolean = false,
        fallbackEnabled: Boolean = true,
    ) = KeyPolicy.decide(action, editable, composing, fallbackEnabled)
}
