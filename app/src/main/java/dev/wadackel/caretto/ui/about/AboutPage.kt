package dev.wadackel.caretto.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.caretto.R
import dev.wadackel.caretto.ui.GroupRow
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.RowGroup
import dev.wadackel.caretto.ui.RowText
import dev.wadackel.caretto.ui.SectionLabel
import dev.wadackel.caretto.ui.Spacing
import dev.wadackel.caretto.ui.main.CarettoSheet
import dev.wadackel.caretto.ui.theme.Caretto
import dev.wadackel.caretto.ui.theme.PlexMono
import dev.wadackel.caretto.ui.theme.PlexSans

/** The version this app was installed as. */
@Composable
fun rememberBuildInfo(): BuildInfo {
    val context = LocalContext.current
    return remember(context) {
        BuildInfo.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }
}

/** The build as one line, with a dirty build's suffix in the warning colour. */
@Composable
fun buildLine(build: BuildInfo) = buildAnnotatedString {
    append(build.version)
    if (build.commit != null) {
        append(" · ")
        append(build.commit)
        if (build.dirty) withStyle(SpanStyle(color = Caretto.colors.warn)) { append("-dirty") }
    }
}

/** What a sheet of About shows: Caretto's own licence, or one row of the list. */
private sealed interface AboutSheet {
    data object Own : AboutSheet
    data class Notice(val notice: OpenSourceNotice) : AboutSheet
}

/**
 * About: the mark, name and build, then the source and Caretto's licence, then the open-source
 * list. A row opens its notice in a sheet. Read on the main thread: the definitions are about
 * 25 KB and reading them takes a few milliseconds.
 */
@Composable
fun AboutPage(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val content = remember(context) {
        loadAboutContent(context, listOf("IBM Plex Sans" to PlexSans, "IBM Plex Mono" to PlexMono))
    }
    val build = rememberBuildInfo()
    val uri = LocalUriHandler.current
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.width(64.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = Spacing.md),
                )
                Text(
                    buildLine(build),
                    fontFamily = PlexMono,
                    fontSize = 12.5.sp,
                    color = Caretto.colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item {
            SectionLabel(stringResource(R.string.section_project))
            RowGroup(listOf(false, true)) { isLicense, shape ->
                if (isLicense) {
                    GroupRow(shape, onClick = { sheet = AboutSheet.Own }) {
                        RowText(stringResource(R.string.license))
                        EndLabel(content.ownLicense.label)
                    }
                } else {
                    GroupRow(shape, onClick = { runCatching { uri.openUri(SOURCE_URL) } }) {
                        RowText(stringResource(R.string.source_code), SOURCE_URL.removePrefix("https://"))
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, tint = Caretto.colors.muted)
                    }
                }
            }
        }
        item {
            SectionLabel(stringResource(R.string.section_open_source), count = content.notices.size)
            RowGroup(content.notices) { notice, shape ->
                GroupRow(shape, onClick = { sheet = AboutSheet.Notice(notice) }) {
                    RowText(notice.name)
                    EndLabel(notice.label)
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }

    when (val shown = sheet) {
        AboutSheet.Own -> NoticeSheet(
            title = stringResource(R.string.app_name),
            subtitle = content.ownLicense.name,
            parts = emptyList(),
            font = null,
            licenses = listOf(content.ownLicense),
        ) { sheet = null }

        is AboutSheet.Notice -> NoticeSheet(
            title = shown.notice.name,
            subtitle = "${shown.notice.detail} · ${shown.notice.licenses.joinToString(" / ") { it.name }}",
            parts = shown.notice.parts,
            font = shown.notice.font,
            licenses = shown.notice.licenses,
        ) { sheet = null }

        null -> Unit
    }
}

@Composable
private fun EndLabel(text: String) {
    Text(text, fontFamily = PlexMono, fontSize = 12.5.sp, color = Caretto.colors.muted)
}

/**
 * One notice in full: its name, where it comes from, the artifacts of a group, a line set in the
 * font for a font, and the licence text.
 */
@Composable
private fun NoticeSheet(
    title: String,
    subtitle: String,
    parts: List<String>,
    font: FontFamily?,
    licenses: List<NoticeLicense>,
    onDismiss: () -> Unit,
) {
    CarettoSheet(onDismiss, fullHeight = true) {
        val inset = Modifier.padding(horizontal = 28.dp)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = inset.padding(top = Spacing.sm))
            HelpText(subtitle, inset.padding(top = Spacing.xs))
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    fontFamily = PlexMono,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Caretto.colors.ink,
                    modifier = inset.padding(top = Spacing.md),
                )
            }
            if (font != null) {
                Text(
                    "Aa Bb 0123 Ctrl+K",
                    fontFamily = font,
                    fontSize = 24.sp,
                    color = Caretto.colors.ink,
                    modifier = inset.padding(top = Spacing.md),
                )
            }
            licenses.forEach { license ->
                if (licenses.size > 1) {
                    Text(
                        license.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Caretto.colors.muted,
                        modifier = inset.padding(top = Spacing.lg),
                    )
                }
                Text(
                    license.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Caretto.colors.muted,
                    modifier = inset.padding(top = Spacing.md),
                )
            }
        }
    }
}
