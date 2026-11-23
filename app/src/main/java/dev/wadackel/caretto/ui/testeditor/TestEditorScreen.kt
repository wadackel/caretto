package dev.wadackel.caretto.ui.testeditor

import android.view.Gravity
import android.text.InputType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wadackel.caretto.R
import dev.wadackel.caretto.core.Prefs
import dev.wadackel.caretto.ui.AccentTextButton
import dev.wadackel.caretto.ui.CarettoSwitch
import dev.wadackel.caretto.ui.GroupInset
import dev.wadackel.caretto.ui.HelpText
import dev.wadackel.caretto.ui.PageBar
import dev.wadackel.caretto.ui.theme.Caretto
import dev.wadackel.caretto.ui.theme.mono

/** ASCII, Japanese, a ZWJ emoji, a flag and a combining accent — the grapheme cluster corpus. */
private val SAMPLE = buildString {
    appendLine("hello emacs style editing")
    appendLine("日本語のテキストもここで確認します")
    appendLine("emoji: 👨‍👩‍👧‍👦 🇯🇵 café")
    append("last line without newline")
}

@Composable
fun TestEditorScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                PageBar(stringResource(R.string.test_editor_title), onBack, stringResource(R.string.back))
            }
        },
        containerColor = Caretto.colors.background,
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        // A Column rather than a LazyColumn: a lazy list leaves an editor scrolled out of view
        // uncomposed, and the emulator scenarios need all four on hand to set and focus.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HelpText(
                stringResource(R.string.test_editor_intro),
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            NativeField(stringResource(R.string.field_edittext_multiline), "edittext-multiline", multiLine = true)
            NativeField(stringResource(R.string.field_edittext_singleline), "edittext-singleline", multiLine = false)
            ComposeStateField()
            ComposeValueField()
            ExclusionCard()
        }
    }
}

/** One editor's block, on the same ground as the rows elsewhere in the app. */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupInset)
            .clip(RoundedCornerShape(18.dp))
            .background(Caretto.colors.row),
    ) { content() }
}

@Composable
private fun FieldCard(
    label: String,
    state: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            content()
            Text(
                state,
                style = MaterialTheme.typography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NativeField(label: String, logName: String, multiLine: Boolean) {
    var report by remember { mutableStateOf("sel=[0,0] len=0") }
    var view by remember { mutableStateOf<SelectionAwareEditText?>(null) }
    val probe = remember { EditorProbe(logName) }
    DisposableEffect(probe) { onDispose { probe.detach() } }

    FieldCard(label, report) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidView(
                factory = { context ->
                    SelectionAwareEditText(context).apply {
                        inputType = if (multiLine) {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        } else {
                            InputType.TYPE_CLASS_TEXT
                        }
                        if (multiLine) gravity = Gravity.TOP or Gravity.START
                        onStateChanged = { start, end, length ->
                            report = "sel=[$start,$end] len=$length"
                            probe.report(text, start, end)
                        }
                        probe.report(text, selectionStart, selectionEnd)
                        onKey = probe::reportKey
                        accessibleName = label
                        probe.attach { text, start, end ->
                            // Focus first: taking focus moves the cursor to the end of the text.
                            requestFocus()
                            setText(text)
                            setSelection(start, end)
                        }
                        view = this
                    }
                },
                modifier = if (multiLine) {
                    Modifier.fillMaxWidth().heightIn(min = 112.dp)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            Row {
                AccentTextButton(stringResource(R.string.insert_sample), onClick = { view?.setText(SAMPLE) })
                AccentTextButton(stringResource(R.string.clear), onClick = { view?.setText("") })
            }
        }
    }
}

@Composable
private fun ComposeStateField() {
    val state = rememberTextFieldState()
    val selection: TextRange = state.selection
    val focus = remember { FocusRequester() }
    val probe = remember { EditorProbe("compose-state") }
    SideEffect { probe.report(state.text, selection.start, selection.end) }
    DisposableEffect(probe) {
        probe.attach { text, start, end ->
            focus.requestFocus()
            state.edit {
                replace(0, length, text)
                this.selection = TextRange(start, end)
            }
        }
        onDispose { probe.detach() }
    }
    val label = stringResource(R.string.field_compose_state)
    FieldCard(label, "sel=[${selection.start},${selection.end}] len=${state.text.length}") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state = state,
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5),
                modifier = Modifier
                    .fillMaxWidth()
                    // The label slot would draw the card's title a second time, inside the field.
                    // Compose keeps this description on a child node, so the text is still read.
                    .semantics { contentDescription = label }
                    .focusRequester(focus)
                    .onPreviewKeyEvent { probe.reportKey(it.nativeKeyEvent); false },
            )
            Row {
                AccentTextButton(stringResource(R.string.insert_sample), onClick = { state.setTextAndPlaceCursorAtEnd(SAMPLE) })
                AccentTextButton(stringResource(R.string.clear), onClick = { state.clearText() })
            }
        }
    }
}

@Composable
private fun ComposeValueField() {
    var value by remember { mutableStateOf(TextFieldValue()) }
    val focus = remember { FocusRequester() }
    val probe = remember { EditorProbe("compose-value") }
    SideEffect { probe.report(value.text, value.selection.start, value.selection.end) }
    DisposableEffect(probe) {
        probe.attach { text, start, end ->
            focus.requestFocus()
            value = TextFieldValue(text, TextRange(start, end))
        }
        onDispose { probe.detach() }
    }
    val label = stringResource(R.string.field_compose_value)
    FieldCard(label, "sel=[${value.selection.start},${value.selection.end}] len=${value.text.length}") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label }
                    .focusRequester(focus)
                    .onPreviewKeyEvent { probe.reportKey(it.nativeKeyEvent); false },
            )
            Row {
                AccentTextButton(
                    stringResource(R.string.insert_sample),
                    onClick = { value = TextFieldValue(SAMPLE.lineSequence().first(), TextRange(0)) },
                )
                AccentTextButton(stringResource(R.string.clear), onClick = { value = TextFieldValue() })
            }
        }
    }
}

/** Flips this app in and out of the pass-through list, so the exclusion can be tried both ways. */
@Composable
private fun ExclusionCard() {
    val context = LocalContext.current
    var excluded by remember { mutableStateOf(context.packageName in Prefs.rawApps(context)) }

    Panel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.exclusion_label), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.exclusion_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CarettoSwitch(
                    checked = excluded,
                    onCheckedChange = { wanted ->
                        val current = Prefs.rawApps(context)
                        Prefs.setRawApps(
                            context,
                            if (wanted) current + context.packageName else current - context.packageName,
                        )
                        excluded = wanted
                    },
                )
                Text(
                    stringResource(if (excluded) R.string.exclusion_passing else R.string.exclusion_remapping),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
