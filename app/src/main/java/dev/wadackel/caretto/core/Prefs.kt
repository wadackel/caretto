package dev.wadackel.caretto.core

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences

object Prefs {

    private const val FILE = "caretto"

    /**
     * Settings that stay on this device. data_extraction_rules.xml includes only [FILE], so nothing
     * here reaches a cloud backup or a device transfer: verbose logging switched on and carried to
     * a new phone would record every key there without its owner remembering it was on.
     */
    private const val LOCAL_FILE = "caretto_local"
    private const val KEY_REMAP_ENABLED = "remap_enabled"
    private const val KEY_FALLBACK_ENABLED = "keyevent_fallback_enabled"
    private const val KEY_VERBOSE_LOG = "verbose_log"
    private const val KEY_RAW_APPS = "raw_apps"
    private const val KEY_SELECT_DELETE_APPS = "select_delete_apps"

    val DEFAULT_RAW_APPS: Set<String> = linkedSetOf(
        "com.server.auditor.ssh.client",
        "com.termux",
        "com.sonelli.juicessh",
        "org.connectbot",
    )

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun local(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)

    /** Listens to both files, since a screen showing every setting has to hear a change in either. */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        of(context).registerOnSharedPreferenceChangeListener(listener)
        local(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        of(context).unregisterOnSharedPreferenceChangeListener(listener)
        local(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun remapEnabled(context: Context): Boolean = of(context).getBoolean(KEY_REMAP_ENABLED, true)

    fun setRemapEnabled(context: Context, value: Boolean) {
        of(context).edit { putBoolean(KEY_REMAP_ENABLED, value) }
    }

    fun keyEventFallbackEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_FALLBACK_ENABLED, true)

    fun setKeyEventFallbackEnabled(context: Context, value: Boolean) {
        of(context).edit { putBoolean(KEY_FALLBACK_ENABLED, value) }
    }

    fun verboseLog(context: Context): Boolean = local(context).getBoolean(KEY_VERBOSE_LOG, false)

    fun setVerboseLog(context: Context, value: Boolean) {
        local(context).edit { putBoolean(KEY_VERBOSE_LOG, value) }
    }

    fun rawApps(context: Context): Set<String> =
        of(context).getStringSet(KEY_RAW_APPS, null) ?: DEFAULT_RAW_APPS

    fun setRawApps(context: Context, value: Set<String>) {
        of(context).edit { putStringSet(KEY_RAW_APPS, LinkedHashSet(value)) }
    }

    /** Editors that ignore the after half of deleteSurroundingText and need the range selected. */
    val DEFAULT_SELECT_DELETE_APPS: Set<String> = linkedSetOf("md.obsidian")

    fun selectDeleteApps(context: Context): Set<String> =
        of(context).getStringSet(KEY_SELECT_DELETE_APPS, null) ?: DEFAULT_SELECT_DELETE_APPS

    fun setSelectDeleteApps(context: Context, value: Set<String>) {
        of(context).edit { putStringSet(KEY_SELECT_DELETE_APPS, LinkedHashSet(value)) }
    }
}
