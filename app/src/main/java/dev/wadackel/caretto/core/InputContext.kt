package dev.wadackel.caretto.core

sealed interface InputContext {
    /** The foreground or editor package opted out of remapping; every key must stay untouched. */
    data class RawApp(val packageName: String) : InputContext

    data class Editable(val packageName: String?) : InputContext

    data object NoEditor : InputContext

    data object Unknown : InputContext

    val shouldPassThrough: Boolean
        get() = this !is Editable
}
