package dev.wadackel.caretto.core

import android.content.Context
import androidx.core.content.edit

/**
 * The chord-to-operation bindings, persisted as a set of `"<chord>=<operation>"` entries.
 *
 * Keyed by chord rather than by operation, so one operation can answer to several chords while a
 * chord still means exactly one thing. The lookup is cached because it sits on the key handling
 * hot path; the service and the settings screen share a process, so a write invalidates it
 * directly instead of through a preference listener.
 */
object KeyBindings {

    private const val KEY = "key_bindings"
    private const val LEGACY_PREFIX = "binding_"

    @Volatile
    private var cachedEngine: MappingEngine? = null

    fun bindings(context: Context): Map<KeyChord, MappedAction> {
        val stored = Prefs.of(context).getStringSet(KEY, null) ?: return emptyMap()
        return stored.mapNotNull(BindingCodec::decode).toMap()
    }

    /** Sorted for display: operations in declaration order, then by key code. */
    fun sortedBindings(context: Context): List<Pair<KeyChord, MappedAction>> =
        bindings(context).entries
            .map { it.key to it.value }
            .sortedWith(
                compareBy(
                    { MappedAction.ALL.indexOf(it.second) },
                    { it.first.keyCode },
                    { it.first.encode() },
                ),
            )

    /** Cached so that the key handling hot path does not rebuild the lookup on every press. */
    fun engine(context: Context): MappingEngine {
        cachedEngine?.let { return it }
        return MappingEngine(bindings(context)).also { cachedEngine = it }
    }

    /**
     * Binds [chord] to [operation], replacing whatever that chord meant before.
     *
     * @return the operation the chord was taken from, or null when it was free.
     */
    fun assign(context: Context, chord: KeyChord, operation: MappedAction): MappedAction? {
        val current = bindings(context)
        val displaced = current[chord]?.takeIf { it != operation }
        write(context, current + (chord to operation))
        return displaced
    }

    fun unassign(context: Context, chord: KeyChord) {
        write(context, bindings(context) - chord)
    }

    fun resetToDefaults(context: Context) {
        write(context, MappingEngine.DEFAULT_BINDINGS)
    }

    /** Replaces every binding at once, for putting back a key map saved before a reset. */
    fun replaceAll(context: Context, bindings: Map<KeyChord, MappedAction>) {
        write(context, bindings)
    }

    /** Writes the preset the first time the app runs, so a fresh install starts bound. */
    fun seedIfUnset(context: Context) {
        val preferences = Prefs.of(context)
        if (preferences.contains(KEY)) return
        // The previous release stored one key per operation. Nothing carries over: rebinding a
        // handful of chords is cheaper than a reader for a format no longer written.
        preferences.edit {
            MappedAction.ALL.forEach { remove(LEGACY_PREFIX + it.label) }
        }
        resetToDefaults(context)
    }

    fun invalidate() {
        cachedEngine = null
    }

    private fun write(context: Context, bindings: Map<KeyChord, MappedAction>) {
        val encoded = bindings.entries.map { (chord, operation) ->
            BindingCodec.encode(chord, operation)
        }
        Prefs.of(context).edit { putStringSet(KEY, LinkedHashSet(encoded)) }
        invalidate()
    }

}
