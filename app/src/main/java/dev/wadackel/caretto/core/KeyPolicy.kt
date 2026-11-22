package dev.wadackel.caretto.core

/** Whether a chord becomes an editor operation, and if not, why it was left alone. */
sealed interface KeyDecision {
    data class Remap(val action: MappedAction) : KeyDecision
    data class PassThrough(val reason: String) : KeyDecision
}

/**
 * The whole of the decision to take a key or leave it, as a function of what is known at the
 * moment it is pressed.
 *
 * Kept apart from the service so the combinations can be laid out in a test rather than reasoned
 * about across a method that also does bookkeeping.
 */
object KeyPolicy {

    fun decide(
        action: MappedAction?,
        context: InputContext,
        composing: Boolean,
        fallbackEnabled: Boolean,
    ): KeyDecision = when {
        action == null -> KeyDecision.PassThrough("no binding")

        // Every gate below asks about the editor. A global action has no editor to ask about, and
        // binding Home to a chord is pointless if it stops working the moment nothing is focused.
        action is GlobalAction -> KeyDecision.Remap(action)

        context.shouldPassThrough -> KeyDecision.PassThrough(describe(context))

        // Only the IME can edit what it is composing: deleteSurroundingText steps around the
        // composing span, and a key event reaches the editor without the IME ever learning of it.
        action is EditorOperation && action.mutatesText && composing ->
            KeyDecision.PassThrough("composing")

        !fallbackEnabled && action is EditorOperation.Text && action.requiresKeyEventFallback ->
            KeyDecision.PassThrough("fallback disabled")

        else -> KeyDecision.Remap(action)
    }

    private fun describe(context: InputContext): String = when (context) {
        is InputContext.RawApp -> "raw app ${context.packageName}"
        is InputContext.Editable -> "editable ${context.packageName ?: "-"}"
        InputContext.NoEditor -> "no editor"
        InputContext.Unknown -> "unknown"
    }
}
