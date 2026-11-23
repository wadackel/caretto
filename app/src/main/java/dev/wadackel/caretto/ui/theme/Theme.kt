package dev.wadackel.caretto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.wadackel.caretto.R

/**
 * The palette, fixed rather than taken from the wallpaper: an app whose colours change from device
 * to device cannot be recognised by them. Violet is kept to the mark and the few controls
 * docs/design.md lists for Accent, so the screen reads as type on a quiet ground.
 */
@Immutable
data class CarettoColors(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    /** The ground of a row: a little apart from the background, so rows need no rules between them. */
    val row: Color,
    val rowSelected: Color,
    /** The off track of a switch and the outline of the capture box. */
    val track: Color,
    val thumbOn: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
    val sheet: Color,
)

private val Light = CarettoColors(
    background = Color(0xFFFBFAF7),
    ink = Color(0xFF1D1B20),
    // Measured against the darkest grounds it sits on, not the background: a modifier key's fill
    // and rowSelected. A lighter grey passes on the background and falls below 4.5:1 on those.
    muted = Color(0xFF625C6A),
    line = Color(0xFFE4DFE8),
    accent = Color(0xFF6A4BD0),
    row = Color(0xFFF1EEF4),
    rowSelected = Color(0xFFE1DAF0),
    track = Color(0xFFCFC8D8),
    thumbOn = Color(0xFFFFFFFF),
    ok = Color(0xFF2F7D4F),
    warn = Color(0xFF9A6A12),
    error = Color(0xFFB3261E),
    sheet = Color(0xFFFFFFFF),
)

private val Dark = CarettoColors(
    background = Color(0xFF131116),
    ink = Color(0xFFEBE7F0),
    muted = Color(0xFFA29CAB),
    line = Color(0xFF2A2630),
    accent = Color(0xFFCBB6FF),
    row = Color(0xFF1D1A22),
    rowSelected = Color(0xFF332D40),
    track = Color(0xFF433D4C),
    thumbOn = Color(0xFF1D1832),
    ok = Color(0xFF7FD3A0),
    warn = Color(0xFFE8B86A),
    error = Color(0xFFF2B8B5),
    sheet = Color(0xFF1B1820),
)

/** The Material roles, filled from the palette so that stock components land on the same colours. */
private fun CarettoColors.toScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = thumbOn, primaryContainer = rowSelected, onPrimaryContainer = ink,
        secondary = muted, onSecondary = background, secondaryContainer = row, onSecondaryContainer = ink,
        tertiary = muted, onTertiary = background,
        error = error, onError = background, errorContainer = row, onErrorContainer = error,
        background = background, onBackground = ink, surface = background, onSurface = ink,
        surfaceVariant = row, onSurfaceVariant = muted, outline = track, outlineVariant = line,
        surfaceContainerLowest = background, surfaceContainerLow = sheet, surfaceContainer = sheet,
        surfaceContainerHigh = sheet, surfaceContainerHighest = row,
        inverseSurface = ink, inverseOnSurface = background, inversePrimary = Light.accent,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = thumbOn, primaryContainer = rowSelected, onPrimaryContainer = ink,
        secondary = muted, onSecondary = background, secondaryContainer = row, onSecondaryContainer = ink,
        tertiary = muted, onTertiary = background,
        error = error, onError = background, errorContainer = row, onErrorContainer = error,
        background = background, onBackground = ink, surface = background, onSurface = ink,
        surfaceVariant = row, onSurfaceVariant = muted, outline = track, outlineVariant = line,
        surfaceContainerLowest = sheet, surfaceContainerLow = sheet, surfaceContainer = sheet,
        surfaceContainerHigh = sheet, surfaceContainerHighest = row,
        inverseSurface = ink, inverseOnSurface = background, inversePrimary = Dark.accent,
    )
}

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private fun sans(size: Float, weight: FontWeight = FontWeight.Normal, line: Float = size * 1.4f) =
    TextStyle(fontFamily = PlexSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/** Every Material style is set, so nothing falls back to Roboto beside the bundled faces. */
private val CarettoTypography = Typography(
    displayLarge = sans(48f, FontWeight.SemiBold),
    displayMedium = sans(40f, FontWeight.SemiBold),
    displaySmall = sans(32f, FontWeight.SemiBold),
    headlineLarge = sans(28f, FontWeight.SemiBold),
    headlineMedium = sans(24f, FontWeight.SemiBold),
    headlineSmall = sans(22f, FontWeight.SemiBold),
    titleLarge = sans(20f, FontWeight.SemiBold),
    titleMedium = sans(15f, FontWeight.Medium),
    titleSmall = sans(14f, FontWeight.Medium),
    bodyLarge = sans(15f),
    bodyMedium = sans(13.5f, line = 20f),
    bodySmall = sans(12.5f, line = 17f),
    labelLarge = sans(14f, FontWeight.Medium),
    labelMedium = sans(12.5f, FontWeight.Medium),
    labelSmall = sans(11.5f, FontWeight.Medium),
)

internal val LocalCarettoColors = staticCompositionLocalOf { Light }

/**
 * Material's state-layer opacities, except focus: a focused control is shown by the ring that
 * `focusRing` draws instead. The stock 10% tint reached only 1.2:1, and laid over a row it pulled
 * Muted text below 4.5:1.
 */
private val NoFocusTint = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f),
)

object Caretto {
    val colors: CarettoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCarettoColors.current
}

@Composable
fun CarettoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Dark else Light
    CompositionLocalProvider(LocalCarettoColors provides colors, LocalRippleConfiguration provides NoFocusTint) {
        MaterialTheme(
            colorScheme = colors.toScheme(darkTheme),
            typography = CarettoTypography,
            content = content,
        )
    }
}

/**
 * One monospace style, shared by everything that has to line up: chords, package names and log
 * lines. Defining it once keeps them from drifting into three different sizes.
 */
val Typography.mono: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = bodySmall.copy(fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 17.sp)
