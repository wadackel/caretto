package dev.wadackel.caretto.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.KeyChord
import dev.wadackel.caretto.core.MappedAction
import dev.wadackel.caretto.ui.AccentTextButton
import dev.wadackel.caretto.ui.CarettoSwitch
import dev.wadackel.caretto.ui.ChordKeys
import dev.wadackel.caretto.ui.FocusRingOffset
import dev.wadackel.caretto.ui.GroupInset
import dev.wadackel.caretto.ui.GroupRow
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.IconAction
import dev.wadackel.caretto.ui.OperationGroup
import dev.wadackel.caretto.ui.RowGroup
import dev.wadackel.caretto.ui.RowText
import dev.wadackel.caretto.ui.SectionLabel
import dev.wadackel.caretto.ui.Spacing
import dev.wadackel.caretto.ui.describe
import dev.wadackel.caretto.ui.focusRing
import dev.wadackel.caretto.ui.offsetRingCorner
import dev.wadackel.caretto.ui.operationGroup
import dev.wadackel.caretto.ui.operationLabel
import dev.wadackel.caretto.ui.theme.Caretto

/** What the service is doing, as the status row shows it. */
enum class ServiceState { Remapping, Paused, Connecting, Stopped }

fun serviceState(running: Boolean, enabled: Boolean, remapEnabled: Boolean): ServiceState = when {
    running -> if (remapEnabled) ServiceState.Remapping else ServiceState.Paused
    enabled -> ServiceState.Connecting
    else -> ServiceState.Stopped
}

/**
 * The home page: the status, then the key map and nothing else, so the bindings are reached at
 * once and the settings around them are not pushed down by however many there are.
 */
@Composable
fun HomePage(
    status: ServiceState,
    bindings: List<Pair<KeyChord, MappedAction>>,
    wide: Boolean,
    contentPadding: PaddingValues,
    onRemapChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditBinding: (KeyChord, MappedAction) -> Unit,
    onReset: () -> Unit,
) {
    val groups = OperationGroup.entries.map { group -> group to bindings.filter { operationGroup(it.second) == group } }
        .filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize()) {
        HomeBar(onOpenSettings)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item { StatusRow(status, onRemapChange, onOpenAccessibilitySettings) }
            if (bindings.isEmpty()) {
                item { HelpText(stringResource(R.string.keymap_empty), Modifier.padding(horizontal = 28.dp, vertical = Spacing.xl)) }
            }
            if (wide) {
                // Unfolded, the moves fill one column and everything else the other, so the whole
                // default key map is on screen without scrolling.
                val (first, rest) = groups.partition { it.first == OperationGroup.Move }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Column(Modifier.weight(1f)) { first.forEach { BindingGroup(it.first, it.second, onEditBinding) } }
                        Column(Modifier.weight(1f)) { rest.forEach { BindingGroup(it.first, it.second, onEditBinding) } }
                    }
                }
            } else {
                groups.forEach { (group, rows) -> item(key = group.name) { BindingGroup(group, rows, onEditBinding) } }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm, end = Spacing.sm),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AccentTextButton(stringResource(R.string.reset_to_defaults), onClick = onReset)
                }
            }
            // Room for the add button, so it never sits over the last row.
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun HomeBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.ic_mark),
            contentDescription = null,
            modifier = Modifier.width(30.dp),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        IconAction(R.drawable.ic_settings, stringResource(R.string.settings), onOpenSettings)
    }
}

@Composable
private fun BindingGroup(
    group: OperationGroup,
    rows: List<Pair<KeyChord, MappedAction>>,
    onEdit: (KeyChord, MappedAction) -> Unit,
) {
    Column {
        SectionLabel(stringResource(group.label), count = rows.size)
        RowGroup(rows) { (chord, action), shape ->
            GroupRow(
                shape = shape,
                onClick = { onEdit(chord, action) },
                onClickLabel = stringResource(R.string.edit_binding, describe(chord)),
            ) {
                Text(
                    stringResource(operationLabel(action)),
                    style = MaterialTheme.typography.bodyLarge,
                    // A chord long enough to crowd this out would otherwise wrap it into a column
                    // one character wide, which is how the row used to break.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                ChordKeys(chord)
            }
        }
    }
}

/**
 * One row, 78 dp tall in every state at the default font size on a screen 320 dp wide or more, so
 * the button that appears when the service is off does not shift the key map under the finger.
 * Where the text does not fit, the row grows to hold it rather than cutting it off.
 */
@Composable
private fun StatusRow(
    status: ServiceState,
    onRemapChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val colors = Caretto.colors
    val (title, detail) = when (status) {
        ServiceState.Remapping -> R.string.status_remapping to R.string.status_detail_remapping
        ServiceState.Paused -> R.string.status_paused to R.string.status_detail_paused
        ServiceState.Connecting -> R.string.status_connecting to R.string.status_detail_connecting
        ServiceState.Stopped -> R.string.status_stopped to R.string.status_detail_stopped
    }
    // Paused takes the muted tone: green beside "Paused" would read as working.
    val dot = when (status) {
        ServiceState.Remapping -> colors.ok
        ServiceState.Paused -> colors.muted
        ServiceState.Connecting -> colors.warn
        ServiceState.Stopped -> colors.error
    }
    // Measured rather than read from the styles' lineHeight: under Android 14's non-linear font
    // scaling the drawn line is not that sp value converted on its own.
    val measurer = rememberTextMeasurer()
    val firstLines = with(LocalDensity.current) {
        val lines = measurer.measure(" ", statusTitleStyle).size.height + measurer.measure(" ", statusDetailStyle).size.height
        lines.toDp() + DetailGap
    }
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GroupInset, end = GroupInset, top = Spacing.xs)
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.row)
            // Only 8 dp, so that a detail wrapped onto a second line on a 320 dp screen still fits
            // in 78 dp and the row keeps one height in every state there too.
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        content = {
            StatusDot(dot, pulsing = status == ServiceState.Connecting)
            StatusText(stringResource(title), stringResource(detail))
            StatusControl(status, onRemapChange, onOpenAccessibilitySettings)
        },
        measurePolicy = statusRowMeasurePolicy(firstLines),
    )
}

private val DetailGap = 2.dp

private val statusTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium

private val statusDetailStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)

/**
 * Dot, text and control in a line while every word of the text fits beside the control, and the
 * control below the text at the end otherwise, as a banner places its action. Squeezed beside
 * `Open settings` at the largest font size, the text column had no room left even for "running".
 * The dot is centred on the title and the detail's first line, [firstLines] tall: on the whole row
 * it would sit beside the detail once the text wraps.
 */
private fun statusRowMeasurePolicy(firstLines: Dp) = MeasurePolicy { measurables, constraints ->
    val (dot, text, control) = measurables
    val gap = Spacing.md.roundToPx()
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val dotPlaceable = dot.measure(loose)
    val controlPlaceable = control.measure(loose)
    val textStart = dotPlaceable.width + gap
    val besideWidth = constraints.maxWidth - textStart - gap - controlPlaceable.width
    val stacked = text.minIntrinsicWidth(Constraints.Infinity) > besideWidth
    val textWidth = if (stacked) constraints.maxWidth - textStart else besideWidth
    val textPlaceable = text.measure(loose.copy(maxWidth = textWidth.coerceAtLeast(0)))
    val contentHeight = if (stacked) {
        textPlaceable.height + gap + controlPlaceable.height
    } else {
        maxOf(dotPlaceable.height, textPlaceable.height, controlPlaceable.height)
    }
    val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
    val textTop = if (stacked) (height - contentHeight) / 2 else (height - textPlaceable.height) / 2
    val dotY = textTop + (firstLines.roundToPx() - dotPlaceable.height) / 2
    val controlX = constraints.maxWidth - controlPlaceable.width
    val controlY = if (stacked) textTop + textPlaceable.height + gap else (height - controlPlaceable.height) / 2
    layout(constraints.maxWidth, height) {
        dotPlaceable.placeRelative(0, dotY)
        textPlaceable.placeRelative(textStart, textTop)
        controlPlaceable.placeRelative(controlX, controlY)
    }
}

@Composable
private fun StatusText(title: String, detail: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(title, style = statusTitleStyle)
        Text(
            detail,
            style = statusDetailStyle,
            color = Caretto.colors.muted,
            modifier = Modifier.padding(top = DetailGap),
        )
    }
}

@Composable
private fun StatusControl(
    status: ServiceState,
    onRemapChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    when (status) {
        ServiceState.Remapping, ServiceState.Paused -> {
            val label = stringResource(R.string.status_remapping)
            CarettoSwitch(
                checked = status == ServiceState.Remapping,
                onCheckedChange = onRemapChange,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        ServiceState.Connecting -> WaitingDots(Caretto.colors.warn)
        ServiceState.Stopped -> OpenSettingsButton(onOpenAccessibilitySettings)
    }
}

@Composable
private fun OpenSettingsButton(onClick: () -> Unit) {
    val colors = Caretto.colors
    val density = LocalDensity.current
    var labelHeight by remember { mutableStateOf(0.dp) }
    val padding = Spacing.sm
    Button(
        onClick = onClick,
        // Its touch target is padded out to 48 dp, and how much of that the fill takes depends on
        // the font size, so the ring is sized from the label rather than from the bounds.
        modifier = Modifier.focusRing(
            RoundedCornerShape(offsetRingCorner(ButtonCorner)),
            outset = FocusRingOffset,
            height = maxOf(ButtonDefaults.MinHeight, labelHeight + padding * 2),
        ),
        shape = RoundedCornerShape(ButtonCorner),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = padding),
        colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.background),
    ) {
        Text(
            stringResource(R.string.open_settings),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.onSizeChanged { labelHeight = with(density) { it.height.toDp() } },
        )
    }
}

private val ButtonCorner = 8.dp

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val modifier = if (pulsing) Modifier.pulsing(0) else Modifier
    // Two nested Boxes are each placed at whole pixels: at 420 dpi the halo is 42 px and the dot
    // 21 px, so the dot sat half a pixel off centre. Drawing both from one centre cannot round apart.
    Canvas(modifier.size(16.dp)) {
        drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension / 2)
        drawCircle(color, radius = size.minDimension / 4)
    }
}

@Composable
private fun WaitingDots(color: Color) {
    Row(
        modifier = Modifier.widthIn(min = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        repeat(3) { index ->
            Box(Modifier.size(6.dp).pulsing(index * 200).clip(CircleShape).background(color))
        }
    }
}

/**
 * Fades in and out, read in the draw phase only. Read during composition, the value recomposed the
 * row every frame, and the screen never went idle: `uiautomator dump` then failed outright, and a
 * dump is what disconnects the service and makes this row pulse in the first place.
 */
@Composable
private fun Modifier.pulsing(delayMillis: Int): Modifier {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = delayMillis), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    return graphicsLayer { this.alpha = alpha.value }
}
