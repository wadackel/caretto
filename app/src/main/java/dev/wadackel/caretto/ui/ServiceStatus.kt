package dev.wadackel.caretto.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.wadackel.caretto.service.CarettoService

/**
 * Whether the service is switched on in accessibility settings.
 *
 * Reads the raw setting rather than asking AccessibilityManager for the enabled list: this is the
 * form that has been verified against the device, and the two do not always agree during a
 * transition.
 */
fun isServiceEnabledInSettings(context: Context): Boolean {
    val component = ComponentName(context, CarettoService::class.java)
    return enabledServiceEntries(context).any { isEntryFor(component, it) }
}

/** Whether `adb shell pm grant` has given the app the one permission that can switch the service. */
fun canSwitchService(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Adds the service to, or removes it from, the enabled accessibility services, leaving every other
 * entry as it was. Requires [canSwitchService].
 *
 * `accessibility_enabled` follows whether anything is left in the list, the way the Settings app
 * writes it when a service is switched there.
 */
fun setServiceEnabledInSettings(context: Context, enabled: Boolean) {
    val component = ComponentName(context, CarettoService::class.java)
    val others = enabledServiceEntries(context).filterNot { isEntryFor(component, it) }
    val services = if (enabled) others + component.flattenToString() else others
    val resolver = context.contentResolver
    Settings.Secure.putString(
        resolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        services.joinToString(":"),
    )
    Settings.Secure.putInt(
        resolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        if (services.isEmpty()) 0 else 1,
    )
}

private fun enabledServiceEntries(context: Context): List<String> =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty()
        .split(':')
        .filter { it.isNotBlank() }

private fun isEntryFor(component: ComponentName, entry: String): Boolean =
    entry.equals(component.flattenToString(), ignoreCase = true) ||
        entry.equals(component.flattenToShortString(), ignoreCase = true)
