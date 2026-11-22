package dev.wadackel.caretto.core

import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo

/**
 * Resolves what the physical keyboard is currently talking to.
 *
 * Every ambiguous result resolves to a pass-through: an incorrect remap silently destroys input the
 * user could otherwise perform, while a missed remap only loses a convenience.
 */
class ContextResolver(
    private val inputMethodProvider: () -> InputMethod?,
    private val rawAppsProvider: () -> Set<String>,
    private val foregroundPackageProvider: () -> String?,
) {

    fun resolve(): InputContext {
        val inputMethod = inputMethodProvider() ?: return InputContext.Unknown

        val editorInfo: EditorInfo? = inputMethod.currentInputEditorInfo
        val editorPackage = editorInfo?.packageName
        val foregroundPackage = foregroundPackageProvider()

        val rawApps = rawAppsProvider()
        val rawMatch = listOfNotNull(editorPackage, foregroundPackage).firstOrNull { it in rawApps }
        if (rawMatch != null) return InputContext.RawApp(rawMatch)

        if (!inputMethod.currentInputStarted) return InputContext.NoEditor
        if (editorInfo == null) return InputContext.NoEditor
        if (inputMethod.currentInputConnection == null) return InputContext.NoEditor
        if (!isEditable(editorInfo)) return InputContext.NoEditor

        return InputContext.Editable(editorPackage ?: foregroundPackage)
    }

    private fun isEditable(info: EditorInfo): Boolean {
        // TYPE_NULL editors are the ones Android documents as raw KeyEvent consumers; semantic
        // InputConnection edits are not what they expect.
        if (info.inputType == EditorInfo.TYPE_NULL) return false
        return info.inputType and EditorInfo.TYPE_MASK_CLASS != 0
    }
}
