package dev.wadackel.caretto.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wadackel.caretto.R
import dev.wadackel.caretto.ui.AccentTextButton
import dev.wadackel.caretto.ui.FocusRingOffset
import dev.wadackel.caretto.ui.PageBar
import dev.wadackel.caretto.ui.focusRing
import dev.wadackel.caretto.ui.offsetRingCorner
import dev.wadackel.caretto.ui.about.AboutPage
import dev.wadackel.caretto.ui.theme.Caretto
import kotlinx.coroutines.launch

/** The pages, as saved across recreation: a short path, deepest last. */
private const val HOME = "home"
private const val SETTINGS = "settings"
private const val PASS_THROUGH = "apps/pass"
private const val SELECT_DELETE = "apps/select"
private const val LOG = "log"
private const val ABOUT = "about"

private val FabCorner = 18.dp

private fun routeOf(item: SettingsItem) = when (item) {
    is SettingsItem.Apps -> if (item.list == AppList.PassThrough) PASS_THROUGH else SELECT_DELETE
    SettingsItem.Log -> LOG
    SettingsItem.About -> ABOUT
}

private fun itemOf(route: String): SettingsItem? = when (route) {
    PASS_THROUGH -> SettingsItem.Apps(AppList.PassThrough)
    SELECT_DELETE -> SettingsItem.Apps(AppList.SelectThenDelete)
    LOG -> SettingsItem.Log
    ABOUT -> SettingsItem.About
    else -> null
}

/** Unfolded, or any screen this wide, gets two columns of bindings and a two-pane Settings. */
private const val WIDE_DP = 600

@Composable
fun CarettoApp(
    viewModel: SettingsViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenTestEditor: () -> Unit,
) {
    val state = viewModel.state
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = windowWidth >= WIDE_DP.dp
    // On an unfolded phone a fixed 360 dp list would leave the page beside it too narrow to read.
    val listWidth = minOf(360.dp, windowWidth * 0.48f)
    var path by rememberSaveable { mutableStateOf(listOf(HOME)) }
    var sheet by remember { mutableStateOf<KeymapSheet?>(null) }
    var pickingFor by remember { mutableStateOf<AppList?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val directory = rememberAppDirectory()
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.undo)
    val copiedMessage = stringResource(R.string.log_copied)

    val show: (Change?) -> Unit = { change ->
        if (change != null) {
            scope.launch {
                snackbars.currentSnackbarData?.dismiss()
                val undo = change.undo
                val result = snackbars.showSnackbar(change.message, actionLabel = undo?.let { undoLabel })
                if (result == SnackbarResult.ActionPerformed) undo?.invoke()
            }
        }
    }
    val copyLog: () -> Unit = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Caretto log", viewModel.log.joinToString("\n")))
        show(Change(copiedMessage, undo = null))
    }
    val pages = ItemPageActions(viewModel, directory, show, copyLog) { pickingFor = it }
    val top = path.last()
    // Wide, Settings and the page open beside it are one screen, so Back leaves both at once.
    val back: () -> Unit = {
        path = if (wide && top != HOME) listOf(HOME) else path.dropLast(1)
    }
    BackHandler(enabled = path.size > 1, onBack = back)

    Scaffold(
        containerColor = Caretto.colors.background,
        // Only the status bar: lists run under the navigation bar and pad their own ends.
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                val colors = Caretto.colors
                // Spelled out rather than Snackbar(data), whose Undo button takes no modifier and so
                // no focus ring. The ring is in the text colour: Accent on Ink is too faint to see.
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    shape = RoundedCornerShape(10.dp),
                    containerColor = colors.ink,
                    contentColor = colors.background,
                    action = data.visuals.actionLabel?.let { label ->
                        {
                            TextButton(
                                onClick = data::performAction,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.background),
                                modifier = Modifier.focusRing(CircleShape, color = colors.background),
                            ) { Text(label) }
                        }
                    },
                ) { Text(data.visuals.message) }
            }
        },
        floatingActionButton = {
            if (top == HOME) {
                FloatingActionButton(
                    onClick = { sheet = KeymapSheet.Capture() },
                    shape = RoundedCornerShape(FabCorner),
                    containerColor = Caretto.colors.ink,
                    contentColor = Caretto.colors.background,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .focusRing(RoundedCornerShape(offsetRingCorner(FabCorner)), outset = FocusRingOffset),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.add_binding))
                }
            }
        },
    ) { insets ->
        val bottom = PaddingValues(bottom = navigationBarHeight())
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            val page = when {
                top == HOME -> HOME
                wide -> SETTINGS
                else -> top
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "page",
            ) { shown ->
                when (shown) {
                    HOME -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = if (wide) 1040.dp else 640.dp)) {
                            HomePage(
                                status = serviceState(viewModel.serviceRunning, viewModel.serviceEnabled, state.remapEnabled),
                                bindings = state.bindings,
                                wide = wide,
                                contentPadding = bottom,
                                onRemapChange = viewModel::setRemapEnabled,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenSettings = { path = path + SETTINGS },
                                onEditBinding = { chord, action -> sheet = KeymapSheet.Binding(chord, action) },
                                onReset = { show(viewModel.resetBindings()) },
                            )
                        }
                    }

                    SETTINGS -> Column(Modifier.fillMaxSize()) {
                        PageBar(stringResource(R.string.settings), back, stringResource(R.string.back))
                        val list: @Composable (SettingsItem?) -> Unit = { selected ->
                            SettingsList(
                                state = state,
                                logSize = viewModel.log.size,
                                directory = directory,
                                selected = selected,
                                contentPadding = bottom,
                                onOpen = { item ->
                                    path = if (wide) listOf(HOME, SETTINGS, routeOf(item)) else path + routeOf(item)
                                },
                                onOpenTestEditor = onOpenTestEditor,
                                onFallbackChange = viewModel::setFallbackEnabled,
                                onVerboseChange = viewModel::setVerboseLog,
                            )
                        }
                        if (wide) {
                            // The first list is open beside the settings from the start, so the
                            // right pane is never an empty placeholder.
                            val selected = itemOf(top) ?: SettingsItem.Apps(AppList.PassThrough)
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(listWidth).fillMaxHeight()) { list(selected) }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Caretto.colors.line))
                                Column(Modifier.weight(1f)) {
                                    ItemPage(selected, onBack = null, bottom, pages)
                                }
                            }
                        } else {
                            list(null)
                        }
                    }

                    else -> Column(Modifier.fillMaxSize()) {
                        val item = itemOf(shown) ?: SettingsItem.Log
                        ItemPage(item, back, bottom, pages)
                    }
                }
            }
        }
    }

    when (val shown = sheet) {
        is KeymapSheet.Binding -> BindingSheet(
            chord = shown.chord,
            action = shown.action,
            onChangeKey = { sheet = KeymapSheet.Capture(from = shown.chord) },
            onChangeOperation = { sheet = KeymapSheet.Picker(shown.chord) },
            onRemove = { show(viewModel.unassign(shown.chord)) },
            onDismiss = { sheet = null },
        )

        is KeymapSheet.Capture -> CaptureSheet(
            changing = shown.from != null,
            onCaptured = { captured ->
                val from = shown.from
                if (from == null) {
                    sheet = KeymapSheet.Picker(captured)
                } else {
                    sheet = null
                    show(viewModel.rebind(from, captured))
                }
            },
            onDismiss = { sheet = null },
        )

        is KeymapSheet.Picker -> OperationPickerSheet(
            chord = shown.chord,
            bindings = state.bindings,
            onPick = { operation -> show(viewModel.assign(shown.chord, operation)) },
            onDismiss = { sheet = null },
        )

        null -> Unit
    }

    pickingFor?.let { list ->
        AppPickerSheet(
            directory = directory,
            alreadyAdded = viewModel.apps(list),
            onPick = { viewModel.addApp(list, it) },
            onDismiss = { pickingFor = null },
        )
    }
}

/** What the pages under Settings act through, the same whether they are a page or the right pane. */
private class ItemPageActions(
    val viewModel: SettingsViewModel,
    val directory: AppDirectory,
    val show: (Change?) -> Unit,
    val copyLog: () -> Unit,
    val pickApp: (AppList) -> Unit,
)

/** A page under Settings with its own bar: Back when it is a page of its own, none in the right pane. */
@Composable
private fun ItemPage(
    item: SettingsItem,
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues,
    actions: ItemPageActions,
) {
    val viewModel = actions.viewModel
    val directory = actions.directory
    val show = actions.show
    when (item) {
        is SettingsItem.Apps -> {
            PageBar(stringResource(appListTitle(item.list)), onBack, stringResource(R.string.back))
            AppListPage(
                list = item.list,
                packages = viewModel.apps(item.list),
                directory = directory,
                contentPadding = contentPadding,
                onRemove = { show(viewModel.removeApp(item.list, it, directory.label(it))) },
                onAdd = { actions.pickApp(item.list) },
                onReset = { show(viewModel.resetApps(item.list)) },
            )
        }

        SettingsItem.Log -> {
            val log = viewModel.log
            PageBar(stringResource(R.string.section_log), onBack, stringResource(R.string.back)) {
                AccentTextButton(
                    stringResource(R.string.copy),
                    enabled = log.isNotEmpty(),
                    onClick = actions.copyLog,
                )
                AccentTextButton(stringResource(R.string.clear), enabled = log.isNotEmpty(), onClick = viewModel::clearLog)
            }
            LogPage(log, contentPadding)
        }

        SettingsItem.About -> {
            PageBar(stringResource(R.string.about), onBack, stringResource(R.string.back))
            AboutPage(contentPadding)
        }
    }
}

@Composable
private fun navigationBarHeight() = with(LocalDensity.current) {
    WindowInsets.navigationBars.getBottom(this).toDp()
}
