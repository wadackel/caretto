package dev.wadackel.caretto.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.wadackel.caretto.R
import dev.wadackel.caretto.ui.GroupInset
import dev.wadackel.caretto.ui.GroupRow
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.RowText
import dev.wadackel.caretto.ui.Spacing
import dev.wadackel.caretto.ui.theme.Caretto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An installed app, named the way its user knows it and the way the setting stores it. */
data class InstalledApp(val label: String, val packageName: String)

/**
 * The launchable apps, read once off the main thread, and their icons, decoded as rows reach the
 * screen. The settings store package names, which are the one thing about an app nobody
 * remembers, so every place that shows one looks its name and icon up here.
 */
@Stable
class AppDirectory(private val context: Context) {
    var apps by mutableStateOf<List<InstalledApp>?>(null)
        private set
    private val icons = mutableStateMapOf<String, ImageBitmap?>()

    suspend fun load() {
        if (apps != null) return
        apps = withContext(Dispatchers.IO) { launchableApps(context.packageManager) }
    }

    /** The app's name, or its package name while the list loads or when it is not installed. */
    fun label(packageName: String): String =
        apps?.firstOrNull { it.packageName == packageName }?.label ?: packageName

    fun icon(packageName: String): ImageBitmap? = icons[packageName]

    suspend fun loadIcon(packageName: String, sizePx: Int) {
        if (packageName in icons) return
        icons[packageName] = withContext(Dispatchers.IO) {
            renderIcon(context.packageManager, packageName, sizePx)
        }
    }
}

@Composable
fun rememberAppDirectory(): AppDirectory {
    val context = LocalContext.current
    val directory = remember(context) { AppDirectory(context.applicationContext) }
    LaunchedEffect(directory) { directory.load() }
    return directory
}

private val ICON_SIZE = 36.dp

/**
 * An app's icon, fetched once the row it belongs to is on screen.
 *
 * Decoding every installed app's icon up front would cost seconds and megabytes for a list most
 * of which is never scrolled to.
 */
@Composable
fun AppIcon(directory: AppDirectory, packageName: String, size: Dp = ICON_SIZE) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    LaunchedEffect(packageName) { directory.loadIcon(packageName, sizePx) }
    val icon = directory.icon(packageName)
    if (icon == null) {
        // Not installed, or not decoded yet: a blank tile keeps the names lined up.
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(9.dp))
                .background(Caretto.colors.ink.copy(alpha = 0.07f)),
        )
    } else {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** Picks a package from what is installed, with a search over names and package names. */
@Composable
fun AppPickerSheet(
    directory: AppDirectory,
    alreadyAdded: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val added = remember(alreadyAdded) { alreadyAdded.toSet() }
    val apps = directory.apps
    val shown = remember(apps, query) { apps.orEmpty().filter { it.matches(query) } }

    CarettoSheet(onDismiss, fullHeight = true) { close ->
        Text(
            stringResource(R.string.pick_app),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = Spacing.sm, bottom = 14.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_apps)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Caretto.colors.line,
                focusedBorderColor = Caretto.colors.accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        if (apps == null) {
            HelpText(stringResource(R.string.loading_apps), Modifier.padding(horizontal = 28.dp, vertical = Spacing.sm))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(shown, key = { _, app -> app.packageName }) { index, app ->
                val isAdded = app.packageName in added
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) 18.dp else 5.dp,
                    topEnd = if (index == 0) 18.dp else 5.dp,
                    bottomStart = if (index == shown.lastIndex) 18.dp else 5.dp,
                    bottomEnd = if (index == shown.lastIndex) 18.dp else 5.dp,
                )
                GroupRow(
                    shape = shape,
                    modifier = Modifier
                        .padding(horizontal = GroupInset)
                        .padding(bottom = 2.dp)
                        .alpha(if (isAdded) 0.5f else 1f),
                    onClick = if (isAdded) null else ({ close(); onPick(app.packageName) }),
                ) {
                    AppIcon(directory, app.packageName)
                    RowText(
                        title = app.label,
                        detail = if (isAdded) stringResource(R.string.already_added, app.packageName) else app.packageName,
                        detailMono = true,
                    )
                }
            }
        }
    }
}

private fun InstalledApp.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return label.contains(needle, ignoreCase = true) ||
        packageName.contains(needle, ignoreCase = true)
}

private fun launchableApps(packageManager: PackageManager): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(intent, 0)
        .map {
            InstalledApp(
                label = it.loadLabel(packageManager).toString(),
                packageName = it.activityInfo.packageName,
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Draws the icon at the size it will be shown; an adaptive one has no intrinsic pixels. */
private fun renderIcon(
    packageManager: PackageManager,
    packageName: String,
    size: Int,
): ImageBitmap? = try {
    val drawable = packageManager.getApplicationIcon(packageName)
    val bitmap = createBitmap(size, size)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
} catch (notInstalled: PackageManager.NameNotFoundException) {
    null
}
