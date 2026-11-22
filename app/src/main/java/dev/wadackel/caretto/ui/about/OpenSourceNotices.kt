package dev.wadackel.caretto.ui.about

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import dev.wadackel.caretto.R
import org.json.JSONObject

/** A licence as About shows it: a short label at a row's end, the full name and text in its sheet. */
data class NoticeLicense(val label: String, val name: String, val text: String)

/**
 * One row of the open-source list: a bundled font, or the artifacts of one Maven group together.
 * A group rather than an artifact, because the build pulls in over seventy artifacts, thirteen of
 * them from androidx.lifecycle alone, and a row for each reads as noise; the sheet names them.
 */
data class OpenSourceNotice(
    val name: String,
    val detail: String,
    val parts: List<String>,
    val licenses: List<NoticeLicense>,
    val font: FontFamily? = null,
) {
    /** What the row shows at its end: every licence of the group, usually one. */
    val label get() = licenses.joinToString(" / ") { it.label }.ifEmpty { "?" }
}

/**
 * The version, and the commit the build came from (`0.1.0+e984f34`), marked when the tree had
 * uncommitted changes (`0.1.0+e984f34-dirty`), since such a build is not that commit.
 */
data class BuildInfo(val version: String, val commit: String?, val dirty: Boolean) {
    companion object {
        fun parse(versionName: String?): BuildInfo {
            val name = versionName.orEmpty()
            val build = name.substringAfter('+', "")
            return BuildInfo(
                version = name.substringBefore('+'),
                commit = build.removeSuffix("-dirty").ifEmpty { null },
                dirty = build.endsWith("-dirty"),
            )
        }
    }
}

const val SOURCE_URL = "https://github.com/wadackel/caretto"

private val ListItem = Regex("""^(\s|[-*#]|\d+[.)]|\([a-z0-9]+\))""")

private fun isHeading(line: String): Boolean {
    val rule = line.all { it == '-' || it == '=' || it.isWhitespace() }
    val capitals = line.length <= 40 && !line.endsWith(".") && line.any(Char::isLetter) && line == line.uppercase()
    return line.startsWith("#") || rule || capitals
}

/**
 * Joins the lines of each paragraph so the text wraps to the screen rather than at the column the
 * file was written for: licence files break near 80 columns, and the cover screen holds about
 * sixty, so left alone every line wrapped once more into a ragged column. Headings, rules and list
 * items keep their lines; a heading is a short line in capitals, since the warranty paragraphs are
 * capitals too.
 */
fun reflowLicense(text: String): String {
    val lines = text.replace("\r\n", "\n").lines()
    val out = StringBuilder()
    lines.forEachIndexed { index, line ->
        out.append(line.trimEnd())
        val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
        val joins = line.isNotBlank() && next.isNotBlank() && !isHeading(line.trim()) &&
            !isHeading(next.trim()) && !ListItem.containsMatchIn(next)
        out.append(if (joins) ' ' else '\n')
    }
    return out.toString().trim()
}

/** Names for groups whose id says little to a reader; the rest are named by rule in [groupName]. */
private val GroupNames = mapOf(
    "com.google.guava" to "Guava",
    "org.jetbrains" to "JetBrains Annotations",
    "org.jetbrains.kotlin" to "Kotlin",
    "org.jetbrains.kotlinx" to "Kotlin Coroutines",
    "org.jspecify" to "JSpecify",
)

private fun titled(segments: String) =
    segments.split('.').joinToString(" ") { if (it == "ui") "UI" else it.replaceFirstChar(Char::uppercaseChar) }

/** A group added later and missing from [GroupNames] falls back to its id: plainer, still correct. */
fun groupName(group: String, artifactNames: List<String>): String = GroupNames[group] ?: when {
    group.startsWith("androidx.compose.") -> "Compose " + titled(group.removePrefix("androidx.compose."))
    group.startsWith("androidx.") -> "AndroidX " + titled(group.removePrefix("androidx."))
    artifactNames.size == 1 -> artifactNames.single()
    else -> group
}

/** Short names for the row's end; a licence missing here shows its SPDX id. */
private val LicenseLabels = mapOf("Apache-2.0" to "Apache 2.0", "MIT" to "MIT", "OFL-1.1" to "OFL 1.1")

/**
 * Reads the definitions the AboutLibraries Gradle plugin writes into the APK, without its runtime
 * library: that would add a dependency to read three plain fields per entry. Unknown keys are
 * ignored, since the plugin adds fields between releases.
 */
fun parseLibraryDefinitions(json: String): List<OpenSourceNotice> {
    val root = JSONObject(json)
    val licenseObjects = root.getJSONObject("licenses")
    val licenses = licenseObjects.keys().asSequence().associateWith { key ->
        val license = licenseObjects.getJSONObject(key)
        NoticeLicense(LicenseLabels[key] ?: key, license.optString("name", key), reflowLicense(license.optString("content")))
    }
    val libraries = root.getJSONArray("libraries")
    data class Artifact(val group: String, val id: String, val version: String, val name: String, val licenses: List<String>)
    val artifacts = (0 until libraries.length()).map { i ->
        val library = libraries.getJSONObject(i)
        val (group, id) = library.getString("uniqueId").split(":", limit = 2)
        val keys = library.optJSONArray("licenses")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        Artifact(group, id, library.optString("artifactVersion"), library.optString("name", id), keys)
    }
    // A key without a text still makes an entry, empty, so the test that every notice has its text fails.
    val notice = { key: String -> licenses[key] ?: NoticeLicense(key, key, "") }
    return artifacts.groupBy { it.group }.map { (group, members) ->
        OpenSourceNotice(
            name = groupName(group, members.map { it.name }),
            detail = group,
            parts = members.map { if (it.version.isEmpty()) it.id else "${it.id} ${it.version}" },
            licenses = members.flatMap { it.licenses }.distinct().map(notice),
        )
    }
}

/** Everything About reads from the APK: Caretto's licence and the list, fonts first, then by name. */
class AboutContent(val ownLicense: NoticeLicense, val notices: List<OpenSourceNotice>)

/** Copied from the repository's LICENSE by the build. */
private const val OWN_LICENSE_ASSET = "licenses/Caretto-LICENSE.txt"
private const val PLEX_LICENSE_ASSET = "licenses/IBM-Plex-OFL.txt"

fun loadAboutContent(context: Context, fonts: List<Pair<String, FontFamily>>): AboutContent {
    val asset = { path: String -> context.assets.open(path).bufferedReader().use { it.readText() } }
    val definitions = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    val ofl = NoticeLicense(LicenseLabels.getValue("OFL-1.1"), "SIL Open Font License 1.1", reflowLicense(asset(PLEX_LICENSE_ASSET)))
    return AboutContent(
        ownLicense = NoticeLicense("MIT", "MIT License", reflowLicense(asset(OWN_LICENSE_ASSET))),
        notices = fonts.map { (name, family) -> OpenSourceNotice(name, "Font", emptyList(), listOf(ofl), family) } +
            parseLibraryDefinitions(definitions).sortedBy { it.name.lowercase() },
    )
}
