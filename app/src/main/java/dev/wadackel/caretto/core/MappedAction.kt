package dev.wadackel.caretto.core

import android.view.KeyEvent

/**
 * Anything a chord can be bound to.
 *
 * Editor operations need a focused editor and are expressed as an [EditPlan]. Global actions need
 * no editor at all — the point of binding Home to a chord is that it works on the launcher too.
 */
sealed interface MappedAction {
    val label: String

    companion object {
        /** Deferred because the members initialise after this interface does. */
        val ALL: List<MappedAction> by lazy { EditorOperation.ALL + GlobalAction.ALL }

        fun fromLabel(label: String): MappedAction? = ALL.firstOrNull { it.label == label }
    }
}

/** Actions handed to the system rather than to an editor. */
enum class GlobalAction(override val label: String) : MappedAction {
    Home("Home"),
    Recents("Recents"),
    Back("Back"),
    AllApps("AllApps"),
    ;

    companion object {
        val ALL: List<GlobalAction> = entries
    }
}

/**
 * The editor's own context menu entries, named rather than numbered.
 *
 * The `android.R.id` values they map to are resolved in [EditorBridge]: keeping them out of here
 * leaves the planning layer testable on the JVM, where android.R would read as zero.
 */
enum class ContextMenuAction {
    Copy,
    Cut,
    Paste,
    SelectAll,
    Undo,
    Redo,
}

/**
 * The chord an editor may answer instead of the menu entry, where it does not answer the entry.
 *
 * Compose's input connection replies to select-all, cut, copy and paste and to nothing else, so
 * undo and redo fall on the floor there — but it does map Ctrl+Z and Ctrl+Y in its own key
 * handling. A TextView is the other way round: the menu entry reaches its undo stack while the
 * chord does not, because it is answered in onKeyShortcut, which an injected key never reaches.
 *
 * Sending both is what covers the two of them without having to tell which is on the other end.
 */
val ContextMenuAction.keyEquivalent: Int?
    get() = when (this) {
        ContextMenuAction.Undo -> KeyEvent.KEYCODE_Z
        ContextMenuAction.Redo -> KeyEvent.KEYCODE_Y
        ContextMenuAction.Copy,
        ContextMenuAction.Cut,
        ContextMenuAction.Paste,
        ContextMenuAction.SelectAll,
        -> null
    }
