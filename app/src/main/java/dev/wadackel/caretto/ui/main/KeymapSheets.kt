package dev.wadackel.caretto.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.KeyChord
import dev.wadackel.caretto.core.MappedAction
import dev.wadackel.caretto.service.CarettoService
import dev.wadackel.caretto.ui.AccentTextButton
import dev.wadackel.caretto.ui.ChordKeys
import dev.wadackel.caretto.ui.GroupRow
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.OperationGroup
import dev.wadackel.caretto.ui.RowGroup
import dev.wadackel.caretto.ui.RowText
import dev.wadackel.caretto.ui.SectionLabel
import dev.wadackel.caretto.ui.Spacing
import dev.wadackel.caretto.ui.describe
import dev.wadackel.caretto.ui.operationGroup
import dev.wadackel.caretto.ui.operationLabel
import dev.wadackel.caretto.ui.theme.Caretto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which sheet the key map is showing, if any. Exclusive by construction. */
sealed interface KeymapSheet {
    /** What to do with an existing binding. */
    data class Binding(val chord: KeyChord, val action: MappedAction) : KeymapSheet

    /** Reading a chord: for a new binding when [from] is null, or to move the binding on [from]. */
    data class Capture(val from: KeyChord? = null) : KeymapSheet

    data class Picker(val chord: KeyChord) : KeymapSheet
}

/**
 * A sheet from the bottom on the app's own ground. [content] receives a close that slides the
 * sheet away before [onDismiss] removes it, rather than having it vanish mid-frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarettoSheet(
    onDismiss: () -> Unit,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { dismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Caretto.colors.sheet,
        contentColor = Caretto.colors.ink,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
            content(close)
        }
    }
}

@Composable
private fun SheetHeader(title: String, chord: KeyChord? = null, detail: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = Spacing.sm, bottom = 14.dp)) {
        if (chord != null) ChordKeys(chord, Modifier.padding(bottom = 10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (detail != null) HelpText(detail, Modifier.padding(top = Spacing.xs))
    }
}

/** Change the key, change the operation, or remove: the three things a binding can have done to it. */
@Composable
fun BindingSheet(
    chord: KeyChord,
    action: MappedAction,
    onChangeKey: () -> Unit,
    onChangeOperation: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    CarettoSheet(onDismiss) { close ->
        SheetHeader(stringResource(operationLabel(action)), chord)
        val rows = listOf(
            Triple(stringResource(R.string.change_key), false, onChangeKey),
            Triple(stringResource(R.string.change_operation), false, onChangeOperation),
            Triple(stringResource(R.string.remove), true) { close(); onRemove() },
        )
        RowGroup(rows) { (label, danger, onClick), shape ->
            GroupRow(shape, onClick = onClick) {
                RowText(label, titleColor = if (danger) Caretto.colors.error else Caretto.colors.ink)
            }
        }
    }
}

/**
 * Reads one chord straight from the keyboard.
 *
 * While this is on screen the service hands every key over untouched, which is the only way a
 * chord that is already bound can be pressed here at all. The flag lives exactly as long as the
 * sheet does.
 */
@Composable
fun CaptureSheet(
    changing: Boolean,
    onCaptured: (KeyChord) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val captured = rememberUpdatedState(onCaptured)
    val dismiss = rememberUpdatedState(onDismiss)
    var got by remember { mutableStateOf<KeyChord?>(null) }

    DisposableEffect(Unit) {
        // The service reads the chord, not this sheet: the window manager takes some combinations
        // before any app sees them, and the service sits upstream of that. It asks here, on the
        // main thread, and the answer decides whether the key is consumed.
        CarettoService.beginCapture { chord ->
            got = chord
            true
        }
        onDispose { CarettoService.endCapture() }
    }
    // The chord stays on screen for a moment, so what was read is seen before the next step.
    LaunchedEffect(got) {
        val chord = got ?: return@LaunchedEffect
        delay(500)
        captured.value(chord)
    }
    // Leaving the screen with this open would strand a request for keys that can no longer be
    // delivered, since the sheet goes with the composition.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { onDismiss() }
    // The same window the service enforces, so a sheet that outlived its request cannot sit there
    // looking as though it were still listening.
    LaunchedEffect(Unit) {
        delay(CarettoService.CAPTURE_WINDOW_MS)
        if (got == null) dismiss.value()
    }

    CarettoSheet(onDismiss) { close ->
        // Recents and the notification shade cover the app without pausing it, so ON_PAUSE never
        // fires for them; this sheet's window losing focus does. The activity's window cannot
        // stand in, since it loses focus to the sheet the moment the sheet opens.
        val window = LocalWindowInfo.current
        LaunchedEffect(window) {
            snapshotFlow { window.isWindowFocused }.dropWhile { !it }.first { !it }
            dismiss.value()
        }
        Column(
            modifier = Modifier
                // Order matters, and without something focusable the key modifier never runs.
                .focusRequester(focusRequester)
                .focusable()
                // Only Escape is answered here. A bindable chord never arrives — the service takes
                // it first — and everything else is left alone so that Back still closes the sheet.
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    native.action == android.view.KeyEvent.ACTION_DOWN &&
                        native.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE &&
                        run {
                            close()
                            true
                        }
                },
        ) {
            SheetHeader(
                title = stringResource(if (changing) R.string.capture_title_change else R.string.capture_title),
                detail = stringResource(R.string.capture_message),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 104.dp)
                    .dashedOutline(Caretto.colors.track)
                    // The sheet takes focus for key capture, so nothing else would announce it.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                val chord = got
                if (chord != null) {
                    ChordKeys(chord, large = true)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BlinkingCaret()
                        Text(
                            stringResource(R.string.capture_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Caretto.colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.lg, end = Spacing.lg),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, end = 10.dp), horizontalArrangement = Arrangement.End) {
                AccentTextButton(stringResource(R.string.cancel), onClick = close)
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** A dashed outline: the box is a place a chord will land, not a field to type into. */
private fun Modifier.dashedOutline(color: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val visible = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0f at 500; 0f at 999 },
            RepeatMode.Restart,
        ),
        label = "caret-alpha",
    )
    // Read in the draw phase, so the blink does not recompose the sheet every frame.
    Box(Modifier.width(2.dp).height(22.dp).graphicsLayer { alpha = visible.value }.background(Caretto.colors.accent))
}

/** Picks what the captured chord should do, showing which chords each operation already answers to. */
@Composable
fun OperationPickerSheet(
    chord: KeyChord,
    bindings: List<Pair<KeyChord, MappedAction>>,
    onPick: (MappedAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = bindings.firstOrNull { it.first == chord }?.second
    CarettoSheet(onDismiss, fullHeight = true) { close ->
        SheetHeader(stringResource(R.string.choose_operation), chord)
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            OperationGroup.entries.forEach { group ->
                SectionLabel(stringResource(group.label), top = 14.dp)
                val operations = MappedAction.ALL.filter { operationGroup(it) == group }
                RowGroup(operations) { operation, shape ->
                    val held = bindings.filter { it.second == operation }.map { describe(it.first) }
                    val detail = when {
                        operation == current -> stringResource(R.string.operation_current)
                        held.isNotEmpty() -> stringResource(R.string.operation_on, held.joinToString(", "))
                        else -> null
                    }
                    GroupRow(shape, onClick = { close(); onPick(operation) }) {
                        RowText(stringResource(operationLabel(operation)), detail)
                    }
                }
            }
            Spacer(Modifier.size(Spacing.lg))
        }
    }
}
