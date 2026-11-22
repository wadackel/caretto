package dev.wadackel.caretto.ui.main

import android.app.Application
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.EventLog
import dev.wadackel.caretto.core.KeyBindings
import dev.wadackel.caretto.core.KeyChord
import dev.wadackel.caretto.core.MappedAction
import dev.wadackel.caretto.core.Prefs
import dev.wadackel.caretto.service.CarettoService
import dev.wadackel.caretto.ui.describe
import dev.wadackel.caretto.ui.isServiceEnabledInSettings
import dev.wadackel.caretto.ui.operationLabel

data class SettingsUiState(
    val remapEnabled: Boolean = true,
    val fallbackEnabled: Boolean = true,
    val verboseLog: Boolean = false,
    val bindings: List<Pair<KeyChord, MappedAction>> = emptyList(),
    val rawApps: List<String> = emptyList(),
    val selectDeleteApps: List<String> = emptyList(),
)

/**
 * What to tell the user after an action, and what puts it back. Destructive actions run at once
 * and offer Undo rather than asking first.
 */
class Change(val message: String, val undo: (() -> Unit)?)

/** Which of the two app lists a page or an action is about. */
enum class AppList { PassThrough, SelectThenDelete }

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf(read())
        private set

    var serviceRunning by mutableStateOf(false)
        private set

    var serviceEnabled by mutableStateOf(false)
        private set

    var log by mutableStateOf<List<String>>(emptyList())
        private set

    private var seenRevision = -1

    /**
     * Held in a field on purpose: preferences keep listeners weakly, and a lambda passed straight
     * to register would be collected and the screen would quietly stop updating.
     */
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state = read() }

    init {
        Prefs.registerListener(application, preferenceListener)
    }

    override fun onCleared() {
        Prefs.unregisterListener(getApplication(), preferenceListener)
    }

    /** The service exposes a plain flag, and the log a revision counter; neither can be observed. */
    fun refreshStatus() {
        serviceRunning = CarettoService.isConnected
        serviceEnabled = isServiceEnabledInSettings(getApplication())
    }

    fun refreshLog() {
        if (EventLog.revision == seenRevision) return
        seenRevision = EventLog.revision
        log = EventLog.snapshot().asReversed()
    }

    fun setRemapEnabled(value: Boolean) = Prefs.setRemapEnabled(getApplication(), value)
    fun setFallbackEnabled(value: Boolean) = Prefs.setKeyEventFallbackEnabled(getApplication(), value)
    fun setVerboseLog(value: Boolean) = Prefs.setVerboseLog(getApplication(), value)

    /**
     * Binds [chord] to [operation]. Null when the chord already ran it, since nothing changed.
     */
    fun assign(chord: KeyChord, operation: MappedAction): Change? {
        val context = getApplication<Application>()
        val previous = KeyBindings.bindings(context)[chord]
        if (previous == operation) return null
        KeyBindings.assign(context, chord, operation)
        val undo: () -> Unit = {
            if (previous == null) KeyBindings.unassign(context, chord) else KeyBindings.assign(context, chord, previous)
        }
        return if (previous == null) {
            Change(text(R.string.binding_now_runs, describe(chord), label(operation)), undo)
        } else {
            Change(text(R.string.binding_taken_from, describe(chord), label(previous)), undo)
        }
    }

    fun unassign(chord: KeyChord): Change? {
        val context = getApplication<Application>()
        val previous = KeyBindings.bindings(context)[chord] ?: return null
        KeyBindings.unassign(context, chord)
        return Change(text(R.string.binding_removed, describe(chord))) {
            KeyBindings.assign(context, chord, previous)
        }
    }

    /**
     * Moves a binding to a different chord. Undo puts back both chords: a move touches two, so
     * undoing only the displacement would leave the moved action bound to nothing at all.
     */
    fun rebind(from: KeyChord, to: KeyChord): Change? {
        val context = getApplication<Application>()
        val before = KeyBindings.bindings(context)
        val action = before[from] ?: return null
        if (from == to) return null
        val displaced = before[to]?.takeIf { it != action }
        KeyBindings.unassign(context, from)
        KeyBindings.assign(context, to, action)
        val message = if (displaced != null) {
            text(R.string.binding_taken_from, describe(to), label(displaced))
        } else {
            text(R.string.binding_moved, label(action), describe(to))
        }
        return Change(message) { KeyBindings.replaceAll(context, before) }
    }

    fun resetBindings(): Change {
        val context = getApplication<Application>()
        val before = KeyBindings.bindings(context)
        KeyBindings.resetToDefaults(context)
        return Change(text(R.string.keymap_reset)) { KeyBindings.replaceAll(context, before) }
    }

    fun apps(list: AppList): List<String> = when (list) {
        AppList.PassThrough -> state.rawApps
        AppList.SelectThenDelete -> state.selectDeleteApps
    }

    fun addApp(list: AppList, packageName: String) = writeApps(list, readApps(list) + packageName)

    fun removeApp(list: AppList, packageName: String, shownAs: String): Change {
        writeApps(list, readApps(list) - packageName)
        return Change(text(R.string.app_removed, shownAs)) { writeApps(list, readApps(list) + packageName) }
    }

    fun resetApps(list: AppList): Change {
        val before = readApps(list)
        writeApps(
            list,
            when (list) {
                AppList.PassThrough -> Prefs.DEFAULT_RAW_APPS
                AppList.SelectThenDelete -> Prefs.DEFAULT_SELECT_DELETE_APPS
            },
        )
        return Change(text(R.string.apps_reset)) { writeApps(list, before) }
    }

    fun clearLog() {
        EventLog.clear()
        refreshLog()
    }

    private fun readApps(list: AppList): Set<String> = when (list) {
        AppList.PassThrough -> Prefs.rawApps(getApplication())
        AppList.SelectThenDelete -> Prefs.selectDeleteApps(getApplication())
    }

    private fun writeApps(list: AppList, packages: Set<String>) = when (list) {
        AppList.PassThrough -> Prefs.setRawApps(getApplication(), packages)
        AppList.SelectThenDelete -> Prefs.setSelectDeleteApps(getApplication(), packages)
    }

    private fun label(action: MappedAction) = text(operationLabel(action))

    private fun text(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun read(): SettingsUiState {
        val context = getApplication<Application>()
        return SettingsUiState(
            remapEnabled = Prefs.remapEnabled(context),
            fallbackEnabled = Prefs.keyEventFallbackEnabled(context),
            verboseLog = Prefs.verboseLog(context),
            bindings = KeyBindings.sortedBindings(context),
            rawApps = Prefs.rawApps(context).sorted(),
            selectDeleteApps = Prefs.selectDeleteApps(context).sorted(),
        )
    }
}
