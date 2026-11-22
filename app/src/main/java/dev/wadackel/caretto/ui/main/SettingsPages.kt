package dev.wadackel.caretto.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.caretto.R
import dev.wadackel.caretto.ui.AccentTextButton
import dev.wadackel.caretto.ui.GroupRow
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.IconAction
import dev.wadackel.caretto.ui.RowGroup
import dev.wadackel.caretto.ui.RowText
import dev.wadackel.caretto.ui.SectionLabel
import dev.wadackel.caretto.ui.Spacing
import dev.wadackel.caretto.ui.SwitchGroupRow
import dev.wadackel.caretto.ui.about.buildLine
import dev.wadackel.caretto.ui.about.rememberBuildInfo
import dev.wadackel.caretto.ui.theme.Caretto
import dev.wadackel.caretto.ui.theme.PlexMono

/** A page under Settings: each is its own page when narrow, and the right pane when wide. */
sealed interface SettingsItem {
    data class Apps(val list: AppList) : SettingsItem
    data object Log : SettingsItem
    data object About : SettingsItem
}

/**
 * Everything around the key map, on one page: the two app lists, the two behaviour switches, and
 * the tools for checking it works. The switches sit here directly rather than on a page of their
 * own, since two switches do not repay a tap.
 */
@Composable
fun SettingsList(
    state: SettingsUiState,
    logSize: Int,
    directory: AppDirectory,
    selected: SettingsItem?,
    contentPadding: PaddingValues,
    onOpen: (SettingsItem) -> Unit,
    onOpenTestEditor: () -> Unit,
    onFallbackChange: (Boolean) -> Unit,
    onVerboseChange: (Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { SectionLabel(stringResource(R.string.section_apps), top = Spacing.sm) }
        item {
            RowGroup(AppList.entries) { list, shape ->
                val packages = if (list == AppList.PassThrough) state.rawApps else state.selectDeleteApps
                GroupRow(
                    shape = shape,
                    selected = selected == SettingsItem.Apps(list),
                    onClick = { onOpen(SettingsItem.Apps(list)) },
                ) {
                    RowText(stringResource(appListTitle(list)), appSummary(packages, directory))
                    Count(packages.size)
                    Chevron()
                }
            }
        }
        item { SectionLabel(stringResource(R.string.section_behavior)) }
        item {
            val rows = listOf(
                Triple(R.string.fallback_enabled, R.string.fallback_enabled_help, state.fallbackEnabled to onFallbackChange),
                Triple(R.string.verbose_log, R.string.verbose_log_help, state.verboseLog to onVerboseChange),
            )
            RowGroup(rows) { (title, help, toggle), shape ->
                SwitchGroupRow(shape, stringResource(title), toggle.first, toggle.second, stringResource(help))
            }
        }
        item { SectionLabel(stringResource(R.string.section_check)) }
        item {
            RowGroup(listOf(false, true)) { isLog, shape ->
                if (isLog) {
                    GroupRow(shape, selected = selected == SettingsItem.Log, onClick = { onOpen(SettingsItem.Log) }) {
                        RowText(stringResource(R.string.section_log))
                        Count(logSize)
                        Chevron()
                    }
                } else {
                    GroupRow(shape, onClick = onOpenTestEditor) {
                        RowText(stringResource(R.string.test_editor_title), stringResource(R.string.test_editor_detail))
                        Chevron()
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.section_app)) }
        item {
            val build = rememberBuildInfo()
            RowGroup(listOf(Unit)) { _, shape ->
                GroupRow(shape, selected = selected == SettingsItem.About, onClick = { onOpen(SettingsItem.About) }) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(stringResource(R.string.about), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildLine(build),
                            fontFamily = PlexMono,
                            fontSize = 11.5.sp,
                            color = Caretto.colors.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Chevron()
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }
}

fun appListTitle(list: AppList): Int = when (list) {
    AppList.PassThrough -> R.string.section_raw_apps
    AppList.SelectThenDelete -> R.string.section_select_delete_apps
}

@Composable
private fun appSummary(packages: List<String>, directory: AppDirectory): String {
    val names = packages.map(directory::label).sortedBy { it.lowercase() }
    return when {
        names.isEmpty() -> stringResource(R.string.raw_apps_empty)
        names.size <= 2 -> names.joinToString(", ")
        else -> pluralStringResource(R.plurals.apps_summary_more, names.size - 2, names.take(2).joinToString(", "), names.size - 2)
    }
}

@Composable
private fun Count(count: Int) {
    Text(
        count.toString(),
        fontFamily = PlexMono,
        fontSize = 13.sp,
        color = Caretto.colors.muted,
    )
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Caretto.colors.muted)
}

/** One of the app lists: icon and name with the package under it, removed at once with Undo. */
@Composable
fun AppListPage(
    list: AppList,
    packages: List<String>,
    directory: AppDirectory,
    contentPadding: PaddingValues,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
) {
    val help = stringResource(
        if (list == AppList.PassThrough) R.string.raw_apps_help else R.string.select_delete_apps_help,
    )
    val sorted = packages.sortedBy { directory.label(it).lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { HelpText(help, Modifier.padding(start = 28.dp, end = 28.dp, top = Spacing.xs, bottom = Spacing.md)) }
        item {
            if (sorted.isEmpty()) {
                HelpText(stringResource(R.string.raw_apps_empty), Modifier.padding(horizontal = 28.dp, vertical = Spacing.sm))
            }
            RowGroup(sorted) { packageName, shape ->
                val name = directory.label(packageName)
                GroupRow(shape) {
                    AppIcon(directory, packageName)
                    // An app that is not installed has only its package name; once is enough.
                    RowText(name, packageName.takeIf { it != name }, detailMono = true)
                    IconAction(
                        R.drawable.ic_close,
                        stringResource(R.string.remove_package, name),
                    ) { onRemove(packageName) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AccentTextButton("+ " + stringResource(R.string.add_app), onClick = onAdd)
                AccentTextButton(stringResource(R.string.reset_to_defaults), onClick = onReset)
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }
}

/** The log, newest first, in the monospace face so its columns line up. */
@Composable
fun LogPage(log: List<String>, contentPadding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + Spacing.xs,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
    ) {
        if (log.isEmpty()) {
            item { HelpText(stringResource(R.string.log_empty)) }
        }
        items(log) { line -> LogLine(line) }
    }
}

@Composable
private fun LogLine(line: String) {
    val colors = Caretto.colors
    // The service's lines lead with what happened, then where; the first word carries the weight.
    val head = line.substringBefore(' ')
    val rest = line.removePrefix(head)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.Medium)) { append(head) }
            withStyle(SpanStyle(color = colors.muted)) { append(rest) }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono, fontSize = 11.5.sp, lineHeight = 19.sp),
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
