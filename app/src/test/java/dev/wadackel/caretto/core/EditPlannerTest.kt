package dev.wadackel.caretto.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class EditPlannerTest {

    @Test
    fun moveForwardAdvancesOneAsciiChar() {
        val plan = plan(EditorOperation.MoveForward, "abcdef", 3)
        assertEquals(4, (plan as EditPlan.SetSelection).position)
    }

    @Test
    fun moveBackwardStepsOverASurrogatePair() {
        val document = "ab😀cd"
        val plan = plan(EditorOperation.MoveBackward, document, 4)
        assertEquals(2, (plan as EditPlan.SetSelection).position)
    }

    @Test
    fun moveForwardStepsOverASurrogatePair() {
        val plan = plan(EditorOperation.MoveForward, "ab😀cd", 2)
        assertEquals(4, (plan as EditPlan.SetSelection).position)
    }

    @Test
    fun movingPastTheDocumentEdgeDoesNothing() {
        assertEquals(EditPlan.Noop::class, plan(EditorOperation.MoveForward, "abc", 3)::class)
        assertEquals(EditPlan.Noop::class, plan(EditorOperation.MoveBackward, "abc", 0)::class)
    }

    @Test
    fun movementCollapsesAnExistingSelection() {
        val forward = plan(EditorOperation.MoveForward, "abcdef", 1, 4)
        assertEquals(4, (forward as EditPlan.SetSelection).position)
        val backward = plan(EditorOperation.MoveBackward, "abcdef", 1, 4)
        assertEquals(1, (backward as EditPlan.SetSelection).position)
    }

    @Test
    fun deleteBackwardRemovesOneClusterWithoutSplittingIt() {
        val ascii = plan(EditorOperation.DeleteBackward, "abc", 3) as EditPlan.DeleteSurrounding
        assertEquals(1, ascii.before)
        assertEquals(0, ascii.after)

        val emoji = plan(EditorOperation.DeleteBackward, "ab😀", 4) as EditPlan.DeleteSurrounding
        assertEquals(2, emoji.before)
        assertEquals(0, emoji.after)
    }

    @Test
    fun deleteReportsWhereTheCursorEndsUp() {
        val backward = plan(EditorOperation.DeleteBackward, "ab😀", 4) as EditPlan.DeleteSurrounding
        assertEquals(2, backward.resultingCursor)
        val forward = plan(EditorOperation.DeleteForward, "a😀b", 1) as EditPlan.DeleteSurrounding
        assertEquals(1, forward.resultingCursor)
    }

    @Test
    fun deleteForwardRemovesTheFollowingCluster() {
        val plan = plan(EditorOperation.DeleteForward, "a😀b", 1) as EditPlan.DeleteSurrounding
        assertEquals(0, plan.before)
        assertEquals(2, plan.after)
    }

    @Test
    fun deletingAtTheDocumentEdgeDoesNothing() {
        assertEquals(EditPlan.Noop::class, plan(EditorOperation.DeleteBackward, "abc", 0)::class)
        assertEquals(EditPlan.Noop::class, plan(EditorOperation.DeleteForward, "abc", 3)::class)
    }

    /** Whatever the key was named, a selection is what goes. No editor has to read a key for it. */
    @Test
    fun everyDeletionTakesTheSelectionWhenThereIsOne() {
        EditorOperation.TEXT.filter { it.deletes }.forEach { operation ->
            val plan = plan(operation, "abcdef", 1, 4) as EditPlan.ReplaceRange
            assertEquals("$operation", 1, plan.start)
            assertEquals("$operation", 4, plan.end)
            assertEquals("$operation", 1, plan.resultingCursor)
        }
    }

    @Test
    fun aSelectionIsTakenTheSameWayWhereForwardDeletesAreIgnored() {
        EditorOperation.TEXT.filter { it.deletes }.forEach { operation ->
            val plan = planSelecting(operation, "abcdef", 1, 4) as EditPlan.ReplaceRange
            assertEquals("$operation", 1, plan.start)
            assertEquals("$operation", 4, plan.end)
        }
    }

    @Test
    fun lineStartStopsAfterThePrecedingNewline() {
        val document = "first\nsecond line"
        val plan = plan(EditorOperation.MoveLineStart, document, 10)
        assertEquals(6, (plan as EditPlan.SetSelection).position)
    }

    @Test
    fun lineEndStopsBeforeTheFollowingNewline() {
        val document = "first\nsecond line"
        val plan = plan(EditorOperation.MoveLineEnd, document, 2)
        assertEquals(5, (plan as EditPlan.SetSelection).position)
    }

    @Test
    fun lineEdgesFallBackToTheDocumentEdgesOnASingleLine() {
        assertEquals(0, (plan(EditorOperation.MoveLineStart, "single line", 5) as EditPlan.SetSelection).position)
        assertEquals(11, (plan(EditorOperation.MoveLineEnd, "single line", 5) as EditPlan.SetSelection).position)
    }

    @Test
    fun aLineLongerThanTheWindowFallsBackToKeyEvents() {
        val document = "x".repeat(600)
        val start = plan(EditorOperation.MoveLineStart, document, 300, window = 64)
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, (start as EditPlan.KeyEventFallback).keyCode)
        val end = plan(EditorOperation.MoveLineEnd, document, 300, window = 64)
        assertEquals(KeyEvent.KEYCODE_MOVE_END, (end as EditPlan.KeyEventFallback).keyCode)
    }

    @Test
    fun killToLineEndRemovesTheRestOfTheLine() {
        val plan = plan(EditorOperation.DeleteToLineEnd, "first\nsecond", 2) as EditPlan.DeleteSurrounding
        assertEquals(0, plan.before)
        assertEquals(3, plan.after)
        assertEquals(2, plan.resultingCursor)
    }

    @Test
    fun killAtTheLineEndRemovesTheLineBreak() {
        val plan = plan(EditorOperation.DeleteToLineEnd, "first\nsecond", 5) as EditPlan.DeleteSurrounding
        assertEquals(0, plan.before)
        assertEquals(1, plan.after)
    }

    @Test
    fun killAtTheLineEndLeavesTheCursorWhereItWas() {
        val plan = plan(EditorOperation.DeleteToLineEnd, "first\nsecond", 5) as EditPlan.DeleteSurrounding
        assertEquals(5, plan.resultingCursor)
    }

    @Test
    fun killCountsCarriageReturnAndLineFeedAsOneBreak() {
        val atEnd = plan(EditorOperation.DeleteToLineEnd, "ab\r\ncd", 2) as EditPlan.DeleteSurrounding
        assertEquals("both halves of the break go", 2, atEnd.after)

        val midLine = plan(EditorOperation.DeleteToLineEnd, "ab\r\ncd", 1) as EditPlan.DeleteSurrounding
        assertEquals("stops before the carriage return", 1, midLine.after)
    }

    @Test
    fun lineEndStopsBeforeACarriageReturn() {
        val plan = plan(EditorOperation.MoveLineEnd, "ab\r\ncd", 0) as EditPlan.SetSelection
        assertEquals(2, plan.position)
    }

    @Test
    fun killFarIntoADocumentUsesAbsolutePositions() {
        val document = "x".repeat(300) + "\n" + "y".repeat(300)
        val plan = plan(EditorOperation.DeleteToLineEnd, document, 290, window = 64) as EditPlan.DeleteSurrounding
        assertEquals("distance to the break, not to the window", 10, plan.after)
        assertEquals(290, plan.resultingCursor)
    }

    @Test
    fun killOnTheLastLineRemovesTheRestOfTheDocument() {
        val plan = plan(EditorOperation.DeleteToLineEnd, "abc", 1) as EditPlan.DeleteSurrounding
        assertEquals(2, plan.after)
    }

    @Test
    fun killAtTheDocumentEndDoesNothing() {
        assertEquals(
            EditPlan.Noop::class,
            plan(EditorOperation.DeleteToLineEnd, "abc", 3)::class,
        )
    }

    @Test
    fun killIsRefusedWhenTheLineRunsPastTheWindow() {
        val plan = plan(EditorOperation.DeleteToLineEnd, "x".repeat(600), 300, window = 64)
        assertEquals(EditPlan.Unsupported::class, plan::class)
    }

    @Test
    fun anEditorWithoutTextCannotKillToTheLineEnd() {
        assertEquals(
            EditPlan.Unsupported::class,
            EditPlanner.withoutText(EditorOperation.DeleteToLineEnd)::class,
        )
    }

    @Test
    fun anEditorWithoutTextFallsBackForEveryOtherOperation() {
        (EditorOperation.TEXT - EditorOperation.DeleteToLineEnd).forEach { operation ->
            assertEquals(
                "$operation",
                EditPlan.KeyEventFallback::class,
                EditPlanner.withoutText(operation)::class,
            )
        }
    }

    @Test
    fun wordMovementLandsOnWordEdges() {
        val forward = plan(EditorOperation.MoveWordForward, "hello world", 0) as EditPlan.SetSelection
        assertEquals(5, forward.position)
        val backward = plan(EditorOperation.MoveWordBackward, "hello world", 11) as EditPlan.SetSelection
        assertEquals(6, backward.position)
    }

    @Test
    fun wordDeletionRemovesAWholeWord() {
        val backward = plan(EditorOperation.DeleteWordBackward, "hello world", 11) as EditPlan.DeleteSurrounding
        assertEquals(5, backward.before)
        assertEquals(0, backward.after)
        assertEquals(6, backward.resultingCursor)

        val forward = plan(EditorOperation.DeleteWordForward, "hello world", 0) as EditPlan.DeleteSurrounding
        assertEquals(0, forward.before)
        assertEquals(5, forward.after)
        assertEquals(0, forward.resultingCursor)
    }

    @Test
    fun wordMovementCollapsesASelection() {
        val plan = plan(EditorOperation.MoveWordForward, "hello world", 1, 4) as EditPlan.SetSelection
        assertEquals(4, plan.position)
    }

    @Test
    fun aWordRunningPastTheWindowIsRefused() {
        val document = "x".repeat(600)
        assertEquals(
            EditPlan.Unsupported::class,
            plan(EditorOperation.MoveWordForward, document, 300, window = 64)::class,
        )
        assertEquals(
            EditPlan.Unsupported::class,
            plan(EditorOperation.DeleteWordBackward, document, 300, window = 64)::class,
        )
    }

    @Test
    fun wordFallbacksCarryCtrlSoTheyAreNotReadAsSingleSteps() {
        listOf(
            EditorOperation.MoveWordForward to KeyEvent.KEYCODE_DPAD_RIGHT,
            EditorOperation.MoveWordBackward to KeyEvent.KEYCODE_DPAD_LEFT,
            EditorOperation.DeleteWordForward to KeyEvent.KEYCODE_FORWARD_DEL,
            EditorOperation.DeleteWordBackward to KeyEvent.KEYCODE_DEL,
        ).forEach { (operation, keyCode) ->
            val plan = EditPlanner.withoutText(operation) as EditPlan.KeyEventFallback
            assertEquals("$operation", keyCode, plan.keyCode)
            assertCarriesCtrl(plan)
        }
    }

    @Test
    fun characterFallbacksCarryNoModifier() {
        listOf(
            EditorOperation.MoveForward,
            EditorOperation.MoveBackward,
            EditorOperation.MoveUp,
            EditorOperation.MoveDown,
            EditorOperation.DeleteBackward,
            EditorOperation.DeleteForward,
        ).forEach { operation ->
            val plan = EditPlanner.withoutText(operation) as EditPlan.KeyEventFallback
            assertEquals("$operation", 0, plan.metaState)
        }
    }

    @Test
    fun aWordEndingExactlyAtTheDocumentEndIsAccepted() {
        val plan = plan(EditorOperation.MoveWordForward, "hello", 0, window = 64) as EditPlan.SetSelection
        assertEquals(5, plan.position)
    }

    @Test
    fun everyOperationDeclaresWhetherItIsWordScopedAndHowWideItReads() {
        listOf(
            EditorOperation.MoveWordForward,
            EditorOperation.MoveWordBackward,
            EditorOperation.DeleteWordForward,
            EditorOperation.DeleteWordBackward,
        ).forEach {
            assertEquals("$it", true, it.isWordScoped)
            assertEquals("$it", true, it.usesWideWindow)
        }
        listOf(EditorOperation.MoveLineStart, EditorOperation.MoveLineEnd, EditorOperation.DeleteToLineEnd)
            .forEach {
                assertEquals("$it", false, it.isWordScoped)
                assertEquals("$it", true, it.usesWideWindow)
            }
        listOf(EditorOperation.MoveForward, EditorOperation.DeleteBackward, EditorOperation.MoveUp)
            .forEach {
                assertEquals("$it", false, it.isWordScoped)
                assertEquals("$it", false, it.usesWideWindow)
            }
    }

    @Test
    fun selectToDeleteRewritesForwardDeletionsAsARangeReplacement() {
        val forward = planSelecting(EditorOperation.DeleteForward, "a\uD83D\uDE00b", 1) as EditPlan.ReplaceRange
        assertEquals(1, forward.start)
        assertEquals(3, forward.end)
        assertEquals(1, forward.resultingCursor)

        val kill = planSelecting(EditorOperation.DeleteToLineEnd, "first\nsecond", 2) as EditPlan.ReplaceRange
        assertEquals(2, kill.start)
        assertEquals(5, kill.end)

        val word = planSelecting(EditorOperation.DeleteWordForward, "hello world", 0) as EditPlan.ReplaceRange
        assertEquals(0, word.start)
        assertEquals(5, word.end)
    }

    @Test
    fun selectToDeleteLeavesBackwardDeletionAlone() {
        val backward = planSelecting(EditorOperation.DeleteBackward, "abc", 3)
        assertEquals(EditPlan.DeleteSurrounding::class, backward::class)
        val word = planSelecting(EditorOperation.DeleteWordBackward, "hello world", 11)
        assertEquals(EditPlan.DeleteSurrounding::class, word::class)
    }

    @Test
    fun selectToDeleteChangesNothingAboutTheEdges() {
        assertEquals(
            EditPlan.Noop::class,
            planSelecting(EditorOperation.DeleteToLineEnd, "abc", 3)::class,
        )
        assertEquals(
            EditPlan.Noop::class,
            planSelecting(EditorOperation.DeleteForward, "abc", 3)::class,
        )
    }

    @Test
    fun everyOperationDeclaresWhetherItDeletes() {
        listOf(
            EditorOperation.DeleteBackward,
            EditorOperation.DeleteForward,
            EditorOperation.DeleteWordBackward,
            EditorOperation.DeleteWordForward,
            EditorOperation.DeleteToLineEnd,
        ).forEach { assertEquals("$it", true, it.deletes) }
        listOf(
            EditorOperation.MoveForward,
            EditorOperation.MoveWordBackward,
            EditorOperation.MoveLineEnd,
            EditorOperation.MoveUp,
        ).forEach { assertEquals("$it", false, it.deletes) }
    }

    @Test
    fun verticalMovementAlwaysUsesKeyEvents() {
        assertEquals(
            KeyEvent.KEYCODE_DPAD_UP,
            (plan(EditorOperation.MoveUp, "abc", 1) as EditPlan.KeyEventFallback).keyCode,
        )
        assertEquals(
            KeyEvent.KEYCODE_DPAD_DOWN,
            (plan(EditorOperation.MoveDown, "abc", 1) as EditPlan.KeyEventFallback).keyCode,
        )
    }

    @Test
    fun anEditorThatHidesItsTextGetsKeyEventsInstead() {
        val expected = mapOf(
            EditorOperation.MoveForward to KeyEvent.KEYCODE_DPAD_RIGHT,
            EditorOperation.MoveBackward to KeyEvent.KEYCODE_DPAD_LEFT,
            EditorOperation.DeleteBackward to KeyEvent.KEYCODE_DEL,
            EditorOperation.DeleteForward to KeyEvent.KEYCODE_FORWARD_DEL,
            EditorOperation.MoveLineStart to KeyEvent.KEYCODE_MOVE_HOME,
            EditorOperation.MoveLineEnd to KeyEvent.KEYCODE_MOVE_END,
            EditorOperation.MoveUp to KeyEvent.KEYCODE_DPAD_UP,
            EditorOperation.MoveDown to KeyEvent.KEYCODE_DPAD_DOWN,
        )
        expected.forEach { (operation, keyCode) ->
            val plan = EditPlanner.withoutText(operation)
            assertEquals(keyCode, (plan as EditPlan.KeyEventFallback).keyCode)
        }
    }

    /** Both the generic and the side-specific bit, since editors differ on which they test. */
    private fun assertCarriesCtrl(plan: EditPlan.KeyEventFallback) {
        assertEquals(KeyEvent.META_CTRL_ON, plan.metaState and KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.META_CTRL_LEFT_ON, plan.metaState and KeyEvent.META_CTRL_LEFT_ON)
    }

    private fun plan(
        operation: EditorOperation.Text,
        document: String,
        selectionStart: Int,
        selectionEnd: Int = selectionStart,
        window: Int = 64,
    ): EditPlan = EditPlanner.plan(operation, snapshot(document, selectionStart, selectionEnd, window))

    private fun planSelecting(
        operation: EditorOperation.Text,
        document: String,
        selectionStart: Int,
        selectionEnd: Int = selectionStart,
        window: Int = 64,
    ): EditPlan = EditPlanner.plan(
        operation,
        snapshot(document, selectionStart, selectionEnd, window),
        selectToDelete = true,
    )

    /** Reproduces what getSurroundingText() returns for a window around the cursor. */
    private fun snapshot(
        document: String,
        selectionStart: Int,
        selectionEnd: Int,
        window: Int,
    ): EditSnapshot {
        val from = maxOf(0, selectionStart - window)
        val to = minOf(document.length, selectionEnd + window)
        return EditSnapshot(
            text = document.substring(from, to),
            relativeStart = selectionStart - from,
            relativeEnd = selectionEnd - from,
            offset = from,
            requestedBefore = window,
            requestedAfter = window,
        )
    }
}
