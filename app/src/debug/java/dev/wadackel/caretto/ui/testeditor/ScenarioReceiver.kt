package dev.wadackel.caretto.ui.testeditor

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import dev.wadackel.caretto.core.BindingCodec
import dev.wadackel.caretto.core.KeyBindings
import dev.wadackel.caretto.core.Prefs
import org.json.JSONObject

/**
 * Puts the app in the state an emulator scenario starts from (`tools/e2e`), in debug builds only.
 *
 * The extra `setup` is base64 of a JSON object, so text with newlines, emoji or quotes survives
 * `adb shell`. Either half may be left out:
 *
 * - `prefs`: the default bindings plus any `extraBindings` (entries in [BindingCodec]'s format),
 *   the KeyEvent fallback on, the clipboard empty, and the given `remapEnabled`, `passThrough`
 *   (this app in the pass-through list) and `verboseLog`
 * - `editor`: `name`, `text` and `selection`, applied to that test editor, which is then focused
 *
 * The result data is `ok` or what went wrong. It rewrites the bindings and can turn on verbose
 * key logging, so only holders of `DUMP` can send it (the manifest), and it does nothing except on
 * an emulator, whose hardware is `ranchu` (or `goldfish` on old images).
 */
class ScenarioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") {
            resultData = "error: not an emulator (${Build.HARDWARE})"
            return
        }
        resultData = try {
            val encoded = intent.getStringExtra("setup") ?: error("no setup extra")
            apply(context, JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)))
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun apply(context: Context, setup: JSONObject) {
        setup.optJSONObject("prefs")?.let { prefs ->
            KeyBindings.resetToDefaults(context)
            prefs.optJSONArray("extraBindings")?.let { extra ->
                for (index in 0 until extra.length()) {
                    val entry = extra.getString(index)
                    val (chord, operation) = BindingCodec.decode(entry) ?: error("cannot read binding $entry")
                    KeyBindings.assign(context, chord, operation)
                }
            }
            Prefs.setKeyEventFallbackEnabled(context, true)
            Prefs.setRemapEnabled(context, prefs.getBoolean("remapEnabled"))
            Prefs.setVerboseLog(context, prefs.getBoolean("verboseLog"))
            val others = Prefs.rawApps(context) - context.packageName
            Prefs.setRawApps(context, if (prefs.getBoolean("passThrough")) others + context.packageName else others)
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
        setup.optJSONObject("editor")?.let { editor ->
            val name = editor.getString("name")
            val selection = editor.getJSONArray("selection")
            val applied = EditorProbe.setState(name, editor.getString("text"), selection.getInt(0), selection.getInt(1))
            check(applied) { "$name is not on screen; open the test editor first" }
        }
    }
}
