package dev.wadackel.caretto.core

/**
 * Turns a binding into the `"<chord>=<operation>"` entry it is stored as, and back.
 *
 * Kept free of Android so the round trip and the handling of damaged entries can be tested.
 */
object BindingCodec {

    private const val SEPARATOR = '='

    fun encode(chord: KeyChord, operation: MappedAction): String =
        "${chord.encode()}$SEPARATOR${operation.label}"

    fun decode(entry: String): Pair<KeyChord, MappedAction>? {
        val separator = entry.lastIndexOf(SEPARATOR)
        if (separator <= 0) return null
        val chord = KeyChord.decode(entry.substring(0, separator)) ?: return null
        val operation = MappedAction.fromLabel(entry.substring(separator + 1)) ?: return null
        return chord to operation
    }
}
