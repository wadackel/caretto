package dev.wadackel.caretto.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.KeyChord
import dev.wadackel.caretto.ui.theme.Caretto
import dev.wadackel.caretto.ui.theme.LocalCarettoColors
import dev.wadackel.caretto.ui.theme.PlexMono

/** The only gaps this UI uses. Anything outside the scale is a mistake, not a fine adjustment. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** How far a group of rows sits from the screen edge, and its rows' text from the group's edge. */
val GroupInset = 12.dp
private val RowPadding = 16.dp
private val OuterCorner = 18.dp
private val InnerCorner = 5.dp

private val FocusRingWidth = 2.dp

/** How far the ring around a filled control (a switch, the add button) sits beyond its edge. */
val FocusRingOffset = 4.dp

/** Height of a switch's track; the switch pads itself out to the minimum touch size around it. */
private val SwitchTrackHeight = 32.dp

/**
 * Draws a 2 dp Accent ring, following [shape], while the control after this modifier has keyboard
 * focus. It takes the place of Material's focus tint, which the theme turns off.
 *
 * The ring's outer edge lies [outset] beyond the bounds; at zero it sits just inside them, which
 * suits a row. A filled control takes [FocusRingOffset], since a ring over its own edge would
 * change Ink to Accent, too small a step to see. [height] is the drawn height of a control padded
 * out to the minimum touch size, so the ring follows what is seen rather than the touch target;
 * once text makes the control taller than that, its bounds are what is drawn. [color] replaces
 * Accent on a ground too close to it, such as the Ink of the snackbar.
 */
fun Modifier.focusRing(
    shape: Shape,
    outset: Dp = 0.dp,
    height: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified,
): Modifier = this then FocusRingElement(shape, outset, height, color)

private data class FocusRingElement(
    val shape: Shape,
    val outset: Dp,
    val height: Dp,
    val color: Color,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, outset, height, color)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.outset = outset
        node.height = height
        node.color = color
        node.invalidateDraw()
    }
}

private class FocusRingNode(var shape: Shape, var outset: Dp, var height: Dp, var color: Color) :
    Modifier.Node(), FocusEventModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = FocusRingWidth.toPx()
        val padded = size.height <= currentValueOf(LocalMinimumInteractiveComponentSize).toPx() + 1f
        val drawn = if (height.isSpecified && padded) height.toPx() else size.height
        // From the bounds to the centre line of the stroke, so the outer edge lands on outset.
        val inset = stroke / 2 - outset.toPx()
        val ring = Size(size.width - 2 * inset, drawn - 2 * inset)
        translate(left = inset, top = (size.height - drawn) / 2 + inset) {
            drawOutline(
                shape.createOutline(ring, layoutDirection, this),
                color.takeOrElse { currentValueOf(LocalCarettoColors).accent },
                style = Stroke(stroke),
            )
        }
    }
}

/** Where a row sits in its group decides which corners are rounded fully. */
private fun rowShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) OuterCorner else InnerCorner,
    topEnd = if (index == 0) OuterCorner else InnerCorner,
    bottomStart = if (index == count - 1) OuterCorner else InnerCorner,
    bottomEnd = if (index == count - 1) OuterCorner else InnerCorner,
)

/**
 * Rows separated by a 2 dp gap rather than by rules, each on a ground slightly off the background,
 * with the outer corners of the group rounded. Rules between rows were tried and read as too many
 * lines flickering on a long list.
 */
@Composable
fun <T> RowGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, shape: RoundedCornerShape) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = GroupInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item -> row(item, rowShape(index, items.size)) }
    }
}

/** One row of a [RowGroup]; [onClick] makes the whole row the target. */
@Composable
fun GroupRow(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = Caretto.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (selected) colors.rowSelected else colors.row)
            .then(
                if (onClick != null) {
                    Modifier.focusRing(shape).clickable(onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/** A row's text: a title and, under it, a smaller line in the muted colour. */
@Composable
fun RowScope.RowText(
    title: String,
    detail: String? = null,
    detailMono: Boolean = false,
    titleColor: Color = Caretto.colors.ink,
) {
    Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                detail,
                style = if (detailMono) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono, fontSize = 11.5.sp)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = Caretto.colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The small capitals over a group, with an optional count at the far end. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, count: Int? = null, top: Dp = 26.dp) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = top, bottom = Spacing.sm)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        val style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono, letterSpacing = 0.9.sp)
        Text(text.uppercase(), style = style, color = Caretto.colors.muted, modifier = Modifier.weight(1f))
        if (count != null) Text(count.toString(), style = style, color = Caretto.colors.muted)
    }
}

@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Caretto.colors.muted,
        modifier = modifier,
    )
}

/**
 * A chord drawn as the keys you actually press. Keys are filled rather than outlined, and the
 * modifiers are lighter than the key itself, so the letter that tells two chords apart stands out.
 */
@Composable
fun ChordKeys(chord: KeyChord, modifier: Modifier = Modifier, large: Boolean = false) {
    val spoken = describe(chord)
    val parts = chordParts(chord)
    val ink = Caretto.colors.ink
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        parts.forEachIndexed { index, part ->
            val key = index == parts.lastIndex
            Box(
                modifier = Modifier
                    .widthIn(min = if (large) 34.dp else 24.dp)
                    .clip(RoundedCornerShape(if (large) 6.dp else 4.dp))
                    .background(ink.copy(alpha = if (key) 0.12f else 0.07f))
                    .padding(
                        horizontal = if (large) 10.dp else 7.dp,
                        vertical = if (large) 8.dp else 4.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    part,
                    fontFamily = PlexMono,
                    fontSize = if (large) 18.sp else 12.5.sp,
                    lineHeight = if (large) 22.sp else 15.sp,
                    fontWeight = if (key) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (key) ink else Caretto.colors.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun CarettoSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Caretto.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.focusRing(CircleShape, outset = FocusRingOffset, height = SwitchTrackHeight),
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.accent,
            checkedThumbColor = colors.thumbOn,
            checkedBorderColor = colors.accent,
            // Track alone is too close to Row to show the switch (1.4:1 in light), and a white
            // thumb on it is no better, so the off state is drawn by a Muted outline and thumb.
            uncheckedTrackColor = colors.track,
            uncheckedThumbColor = colors.muted,
            uncheckedBorderColor = colors.muted,
        ),
    )
}

/** A row that is one switch: the whole row toggles it, and the help sits under the title. */
@Composable
fun SwitchGroupRow(
    shape: RoundedCornerShape,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    help: String? = null,
) {
    GroupRow(
        shape = shape,
        modifier = Modifier
            .focusRing(shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            // Naming the node outright rather than relying on the merge to pull the title in:
            // the merged node came back unlabelled, which announces a state with no subject.
            .semantics(mergeDescendants = true) { contentDescription = title },
    ) {
        RowText(title, help)
        CarettoSwitch(checked = checked, onCheckedChange = null)
    }
}

/** The bar at the top of every page: a back button when there is somewhere to go back to. */
@Composable
fun PageBar(
    title: String,
    onBack: (() -> Unit)?,
    backLabel: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = if (onBack != null) 6.dp else 20.dp, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backLabel)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (onBack != null) Spacing.xs else 0.dp),
        )
        actions()
    }
}

@Composable
fun AccentTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.focusRing(CircleShape)) {
        Text(text, color = if (enabled) Caretto.colors.accent else Caretto.colors.muted)
    }
}

@Composable
fun IconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.focusRing(CircleShape)) {
        Icon(painterResource(icon), contentDescription = label)
    }
}
