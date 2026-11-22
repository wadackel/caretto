package dev.wadackel.caretto.core

sealed interface EditorOperation : MappedAction {

    /** Worked out from the text around the cursor, and so expressed as an [EditPlan]. */
    sealed interface Text : EditorOperation

    /** Handed to the editor's own context menu, which needs no reading of the text first. */
    sealed interface Menu : EditorOperation {
        val action: ContextMenuAction
    }

    data object DeleteBackward : Text {
        override val label = "DeleteBackward"
    }

    data object DeleteForward : Text {
        override val label = "DeleteForward"
    }

    data object DeleteToLineEnd : Text {
        override val label = "DeleteToLineEnd"
    }

    data object MoveWordForward : Text {
        override val label = "MoveWordForward"
    }

    data object MoveWordBackward : Text {
        override val label = "MoveWordBackward"
    }

    data object DeleteWordForward : Text {
        override val label = "DeleteWordForward"
    }

    data object DeleteWordBackward : Text {
        override val label = "DeleteWordBackward"
    }

    data object MoveBackward : Text {
        override val label = "MoveBackward"
    }

    data object MoveForward : Text {
        override val label = "MoveForward"
    }

    data object MoveUp : Text {
        override val label = "MoveUp"
    }

    data object MoveDown : Text {
        override val label = "MoveDown"
    }

    data object MoveLineStart : Text {
        override val label = "MoveLineStart"
    }

    data object MoveLineEnd : Text {
        override val label = "MoveLineEnd"
    }
    data object Copy : Menu {
        override val label = "Copy"
        override val action = ContextMenuAction.Copy
    }

    data object Cut : Menu {
        override val label = "Cut"
        override val action = ContextMenuAction.Cut
    }

    data object Paste : Menu {
        override val label = "Paste"
        override val action = ContextMenuAction.Paste
    }

    data object SelectAll : Menu {
        override val label = "SelectAll"
        override val action = ContextMenuAction.SelectAll
    }

    data object Undo : Menu {
        override val label = "Undo"
        override val action = ContextMenuAction.Undo
    }

    data object Redo : Menu {
        override val label = "Redo"
        override val action = ContextMenuAction.Redo
    }

    companion object {
        /** Declaration order, used for the settings list and the operation picker. */
        val ALL: List<EditorOperation> = listOf(
            MoveBackward,
            MoveForward,
            MoveUp,
            MoveDown,
            MoveWordBackward,
            MoveWordForward,
            MoveLineStart,
            MoveLineEnd,
            DeleteBackward,
            DeleteForward,
            DeleteWordBackward,
            DeleteWordForward,
            DeleteToLineEnd,
            Copy,
            Cut,
            Paste,
            SelectAll,
            Undo,
            Redo,
        )

        /** Derived from [ALL] so the two can never disagree about what is planned from text. */
        val TEXT: List<Text> = ALL.filterIsInstance<Text>()

        fun fromLabel(label: String): EditorOperation? = ALL.firstOrNull { it.label == label }
    }
}

/**
 * Operations that cannot be expressed through AccessibilityInputConnection editing primitives and
 * therefore depend on the experimental sendKeyEvent() path.
 */
val EditorOperation.Text.requiresKeyEventFallback: Boolean
    get() = this is EditorOperation.MoveUp || this is EditorOperation.MoveDown

/** Operations that remove text, and so must not be aimed through a composing span. */
val EditorOperation.Text.deletes: Boolean
    get() = when (this) {
        EditorOperation.DeleteBackward,
        EditorOperation.DeleteForward,
        EditorOperation.DeleteWordBackward,
        EditorOperation.DeleteWordForward,
        EditorOperation.DeleteToLineEnd,
        -> true

        EditorOperation.MoveBackward,
        EditorOperation.MoveForward,
        EditorOperation.MoveUp,
        EditorOperation.MoveDown,
        EditorOperation.MoveWordBackward,
        EditorOperation.MoveWordForward,
        EditorOperation.MoveLineStart,
        EditorOperation.MoveLineEnd,
        -> false
    }

val EditorOperation.Text.isWordScoped: Boolean
    get() = when (this) {
        EditorOperation.MoveWordBackward,
        EditorOperation.MoveWordForward,
        EditorOperation.DeleteWordBackward,
        EditorOperation.DeleteWordForward,
        -> true

        EditorOperation.MoveBackward,
        EditorOperation.MoveForward,
        EditorOperation.MoveUp,
        EditorOperation.MoveDown,
        EditorOperation.MoveLineStart,
        EditorOperation.MoveLineEnd,
        EditorOperation.DeleteBackward,
        EditorOperation.DeleteForward,
        EditorOperation.DeleteToLineEnd,
        -> false
    }

/** Operations whose answer can lie far from the cursor, so a 64 character window is not enough. */
val EditorOperation.Text.usesWideWindow: Boolean
    get() = when (this) {
        EditorOperation.MoveLineStart,
        EditorOperation.MoveLineEnd,
        EditorOperation.DeleteToLineEnd,
        EditorOperation.MoveWordBackward,
        EditorOperation.MoveWordForward,
        EditorOperation.DeleteWordBackward,
        EditorOperation.DeleteWordForward,
        -> true

        EditorOperation.MoveBackward,
        EditorOperation.MoveForward,
        EditorOperation.MoveUp,
        EditorOperation.MoveDown,
        EditorOperation.DeleteBackward,
        EditorOperation.DeleteForward,
        -> false
    }

val EditorOperation.Text.fallsBackOnSelection: Boolean
    get() = when (this) {
        EditorOperation.DeleteBackward,
        EditorOperation.DeleteForward,
        EditorOperation.DeleteWordBackward,
        EditorOperation.DeleteWordForward,
        -> true

        EditorOperation.DeleteToLineEnd,
        EditorOperation.MoveBackward,
        EditorOperation.MoveForward,
        EditorOperation.MoveUp,
        EditorOperation.MoveDown,
        EditorOperation.MoveWordBackward,
        EditorOperation.MoveWordForward,
        EditorOperation.MoveLineStart,
        EditorOperation.MoveLineEnd,
        -> false
    }


/**
 * Operations that change the text, and so must not be aimed through a composing span.
 *
 * Copying and selecting are safe mid-composition; anything that rewrites is not, for the same
 * reason a delete is not — only the IME can edit what it is composing.
 */
val EditorOperation.mutatesText: Boolean
    get() = when (this) {
        is EditorOperation.Text -> deletes
        EditorOperation.Cut,
        EditorOperation.Paste,
        EditorOperation.Undo,
        EditorOperation.Redo,
        -> true

        EditorOperation.Copy,
        EditorOperation.SelectAll,
        -> false
    }

/**
 * Whether holding the chord down should keep firing it.
 *
 * Moving and deleting are the only things anyone holds a key for. A repeating paste would be a
 * mess to undo, and a repeating Home would leave the device unusable while the key was down.
 */
val MappedAction.repeats: Boolean
    get() = this is EditorOperation.Text
