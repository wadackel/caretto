package dev.wadackel.caretto.core

/**
 * A snapshot of the editor around the cursor, expressed in absolute editor offsets.
 *
 * [requestedBefore] / [requestedAfter] are kept so the planner can tell "the line really ends here"
 * apart from "the window ran out", which decides whether a fallback KeyEvent is needed.
 */
data class EditSnapshot(
    val text: CharSequence,
    val relativeStart: Int,
    val relativeEnd: Int,
    val offset: Int,
    val requestedBefore: Int,
    val requestedAfter: Int,
) {
    val absoluteStart: Int get() = offset + relativeStart
    val absoluteEnd: Int get() = offset + relativeEnd

    val textBefore: CharSequence get() = text.subSequence(0, relativeStart)
    val textAfter: CharSequence get() = text.subSequence(relativeEnd, text.length)

    val hasSelection: Boolean get() = relativeStart != relativeEnd

    val hasMoreBefore: Boolean get() = offset > 0
    val hasMoreAfter: Boolean get() = text.length - relativeEnd >= requestedAfter
}

sealed interface EditPlan {
    val detail: String

    data class SetSelection(val position: Int, override val detail: String) : EditPlan

    data class DeleteSurrounding(
        val before: Int,
        val after: Int,
        val resultingCursor: Int,
        override val detail: String,
    ) : EditPlan

    /** Requires the experimental sendKeyEvent() path; skipped entirely when it is disabled. */
    data class KeyEventFallback(
        val keyCode: Int,
        override val detail: String,
        val metaState: Int = 0,
    ) : EditPlan

    data class Noop(override val detail: String) : EditPlan

    /**
     * Select the range, then replace it with nothing.
     *
     * For editors that carry out the before half of deleteSurroundingText and drop the after half.
     */
    data class ReplaceRange(
        val start: Int,
        val end: Int,
        val resultingCursor: Int,
        override val detail: String,
    ) : EditPlan

    /** Handed to the editor's own context menu; needs no reading of the text. */
    data class ContextMenu(
        val action: ContextMenuAction,
        override val detail: String,
    ) : EditPlan

    /** The operation cannot be expressed here — no text to work from and no equivalent key. */
    data class Unsupported(override val detail: String) : EditPlan
}
