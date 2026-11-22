# What testing on a device revealed

Device: Galaxy Z Fold8 (SM-F971Q) / Android 17 (API 37) / Gboard / ZMK keyboard (`dax6 Keyboard`)

## F1. Termius's doubled Japanese input has nothing to do with Caretto

**Situation**: type `test` into Termius with the Japanese IME active.

| Condition | Result |
|---|---|
| Caretto enabled, remapping off | `ててｓてすてすとてすと` |
| Caretto fully stopped (`KeyboardInterceptor` released too) | `ｔててｓてすてすｔてすとテストテスト` |

Both broke the same way: intermediate composition states pile up instead of being replaced.

**Conclusion**: the symptom does not depend on whether the service is running. It is a problem
with the Termius + Gboard + physical keyboard combination itself.

**What this changes**: before the implementation, this symptom was put down to Key Mapper's
Expert mode, which had been turned on to keep Gboard. It reproduces with no remapper running at
all, let alone Expert mode, so that attribution was most likely wrong. The condition that would
have stopped the project, "an AccessibilityService alone breaks Termius input", does not hold.
What was kept from that diagnosis, and why, is in
[architecture.md](./architecture.md#editing-operations-not-replacement-key-events).

**Scope**: only Termius shows the symptom. Japanese input works normally in Chrome, Obsidian,
and Caretto's test editor. In Termius the IME's composing text is entered as-is, before commit.

**Narrowing further: it depends on the IME**

In the same Termius, switching the IME to Samsung Keyboard makes Japanese input work normally.

| IME | Japanese input in Termius |
|---|---|
| Gboard | the intermediate state is entered before commit |
| Samsung Keyboard | normal |

So the cause lies in the combination of Termius and Gboard. Termius reports neither the cursor
position nor the composing region in `onStartInput` / `onUpdateSelection` (always
`sel=[-1,-1] composing=[-1,-1]`), and Gboard apparently cannot hold a composition under that
and commits on every keystroke. Samsung Keyboard tolerates the situation.

**Workaround**: switch to Samsung Keyboard when you need to type Japanese in Termius.

Termius is designed to pass everything through as a Raw Zone anyway, so this does not affect
Caretto's design decisions. It is handled separately, as a Termius problem.

**Retest with identical keystrokes**: compared using the same phrase, `これは日本語`.

```
service ON : 　ここｒこれこれｈこれはこれはｎににほにほｎにほんにほんｇにほんご日本語日本語
service OFF: ｋここｒこれこれｈこれはこれはｎににほにほｎにほんにほんごにほんご
```

Apart from a typo at the start and the number of commits at the end, the intermediate states
pile up the same way. The strings do not match exactly because the race is timing-dependent;
an exact match would be the surprising outcome.

With the service stopped, `service disconnected` was confirmed in the log and the disappearance
of `KeyboardInterceptor` confirmed with `dumpsys accessibility` before running the test. Caretto
was nowhere in the input path.

**Phase 1 verdict**: pass. No No-Go condition applies.

## F2. The Accessibility Input Method works on an Android 17 device

Observing editor sessions from `onCreateInputMethod()` works, and it yields the package name
and the cursor position.

```
onStartInput restarting=false started=true pkg=com.server.auditor.ssh.client
onStartInput restarting=true  started=true pkg=md.obsidian sel=[341,341]
```

`dumpsys accessibility` also shows `requestFilterKeyEvents=true` and `KeyboardInterceptor`.
Chords held with Ctrl (`Ctrl+S` / `Ctrl+H` / `Ctrl+SPACE`) reach `onKeyEvent()` as well.

Keys that are not remapped pass through without being regenerated: confirmed on a device.

## F3. WebView-based editors rebuild the InputConnection on every keystroke

In Obsidian, `onStartInput restarting=true` fires on every keystroke, and each time it reports
the correct cursor position.

`EditorStateTracker` preferred its cache and discarded the reported value when `restarting=true`,
so a stale cursor position survived. It decides whether deletion needs the KeyEvent fallback from
whether there is a selection, so entering Phase 4 unchanged would have produced wrong decisions.

**Fixed**: always take the reported value when it is valid (>= 0). `EditorStateTrackerTest` pins
the regression.

## F4. Movement by grapheme cluster works correctly on a device

Distances moved when running `Ctrl+F` / `Ctrl+B` back and forth over the emoji line of the
sample text.

```
Ctrl+B ... move backward 11    👨‍👩‍👧‍👦 (4 emoji × 2 + 3 ZWJ)
Ctrl+B ... move backward 4     🇯🇵 (2 Regional Indicator chars)
Ctrl+F ... move forward 11
Ctrl+F ... move forward 4
```

Forward and backward move the same distance, so a round trip lands back where it started.
Android's `BreakIterator` treats a ZWJ sequence and a flag sequence as a single cluster.

Grapheme cluster support had been planned as something to consider later, but it already holds
on a device. The JVM unit tests only pin behavior up to surrogate pairs, to allow for
differences in ICU.

## F5. setSelection() does not break the composition, even while Gboard is composing

A record of moving the cursor six times with conversion candidates on screen.

```
onUpdateSelection sel=[100,100] composing=[99,100]
onUpdateSelection sel=[103,103] composing=[99,103]
Ctrl+B -> MoveBackward [ok] setSelection(101)
onUpdateSelection sel=[101,101] composing=[99,102]
Ctrl+B -> MoveBackward [ok] setSelection(100)
onUpdateSelection sel=[100,100] composing=[99,102]
Ctrl+B -> MoveBackward [ok] setSelection(99)
onUpdateSelection sel=[99,99]   composing=[99,102]
Ctrl+F -> MoveForward  [ok] setSelection(100)
Ctrl+F -> MoveForward  [ok] setSelection(101)
Ctrl+F -> MoveForward  [ok] setSelection(102)
onUpdateSelection sel=[102,102] composing=[99,102]
onUpdateSelection sel=[102,102] composing=[-1,-1]   ← committed as usual
```

The composing region `[99,102]` survived all six moves and then committed as usual. A separate
session (composing=`[0,3]`) gave the same result.

The API contract says `setSelection()` keeps the composing text, and Gboard's implementation
holds to it. Editing operations work naturally while composing: settled, yes.

## F6. Ctrl+E moves to the end of the logical line

```
Ctrl+E -> MoveLineEnd [ok] setSelection(43) logical line end
```

The path that searches the surrounding text for `\n` and lands at the end of the line works on
a device.

## F7. Termius's Japanese input problem is known, and the cause matches the literature

Investigating the symptom observed in F1 turned up existing reports of it as a known Termius
problem.

### Reported symptoms

According to
[SSHアプリで日本語入力できない？Termius・ConnectBot・Termuxの原因と解決策まとめ](https://iru-yo.com/blog/termius-android-japanese-input/),
the Termius terminal emulator cannot handle the Android IME's composing (uncommitted) state
correctly and processes events from the IME as ordinary key input. The symptoms it lists are
below, and they match what we observed.

- uncommitted characters are sent as-is
- no conversion candidates appear
- the space key commits before conversion
- the cursor jumps while typing

The Termius team has made individual Gboard-related fixes in the past, but as of March 2026 the
underlying Japanese IME problem is unresolved and is not treated as a high-priority fix.

### Mechanism

[Termuxでハードウェアキーボードからスムーズに日本語入力したい](https://misohena.jp/blog/2022-01-20-enter-japanese-text-into-termux-from-hardware-keyboard.html)
explains that for Termux, the terminal's View specifies `InputType.TYPE_NULL`, so the IME does
no conversion and passes key events through.

**Termius turned out to be different.** Here is the `inputType` recorded on a device.

```
pkg=com.server.auditor.ssh.client inputType=TEXT(0x80001) sel=[-1,-1] composing=[-1,-1]
```

`0x80001` is `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS`. Not `TYPE_NULL`.

`TYPE_TEXT_FLAG_NO_SUGGESTIONS` tells the IME not to offer suggestions or autocorrect, and
Gboard appears to respond by skipping composition entirely and committing each keystroke.
Samsung Keyboard keeps composing even with the flag set. That is likely why the result differs
by IME (it also fits reports that ATOK works in Termux).

Lined up against other apps, the presence of `NO_SUGGESTIONS` matches whether Gboard breaks.

| App | inputType | NO_SUGGESTIONS (0x80000) | Japanese input with Gboard |
|---|---|---|---|
| Termius | `0x80001` | set | broken |
| Obsidian | `0x22c0a1` | not set | normal |
| Caretto test editor | `0x220001` | not set | normal |

This is correlation only. We have not confirmed that Gboard stops composing because of
`NO_SUGGESTIONS`; Gboard's implementation has not been examined.

### What this means for Caretto's design

`ContextResolver.isEditable()` treats only `TYPE_NULL` editors as non-editable and passes them
through. Termius returns `TYPE_CLASS_TEXT`, so this check does not exclude it.

**So the only thing excluding Termius is its registration under `Pass-through apps`; there is
no other defense.** The original assumption that automatic exclusion via `TYPE_NULL` would form
a second line of defense was wrong.

In an unregistered terminal app, Ctrl-based keys get remapped. Any terminal app you use has to
be registered explicitly.

### Reference

Where Japanese input is required, another option is an SSH client with a dedicated IME mode,
such as SSH Term.

## F8. Editors that return no surrounding text really do exist

In `dev.wadackel.termemu.debug` (a terminal emulator of my own), `Ctrl+H` came back
`[failed] surrounding text unavailable`. `getSurroundingText()` yields nothing.

A terminal emulator with its own `InputConnection` is right not to implement it; such editors
expect key events in the first place. Its `inputType` was `TEXT(0xa0001)`
= `TYPE_CLASS_TEXT | MULTI_LINE | NO_SUGGESTIONS`.

### The cause was a regression I introduced myself

When `EditPlanner` was extracted into pure functions, the path that falls back to key events
when surrounding text is unavailable got dropped. Before the extraction,
`deleteViaKeyEvent()` / `lineEdgeViaKeyEvent()` did that job.

### The fix

In editors where surrounding text is unavailable, fall back to the corresponding key event.

| Operation | Key sent |
|---|---|
| `Ctrl+F` / `Ctrl+B` | `DPAD_RIGHT` / `DPAD_LEFT` |
| `Ctrl+H` / `Ctrl+D` | `DEL` / `FORWARD_DEL` |
| `Ctrl+A` / `Ctrl+E` | `MOVE_HOME` / `MOVE_END` |

Along with that, in editors that return surrounding text but not its position in the document
(`offset`), the read window is now placed using the absolute position tracked through
`onUpdateSelection`. That case used to be given up on, so more editors can now use the proper
`setSelection` path.

The fallback path was confirmed working on a device for `Ctrl+H` `Ctrl+D` `Ctrl+E` `Ctrl+P`.
The log only guarantees that the key event was sent, though; whether the editor received it and
acted on it still has to be checked by eye.

### Remaining limitation

With `Use key event fallback` off, an operation that lands on this path consumes the key and
then does nothing, because whether surrounding text is available is only known after the key
has been consumed.

## F9. A real example of an editor that returns no surrounding text

Putting the target package in the log made it possible to identify which editor was falling back.

```
pkg=dev.wadackel.limenty.debug inputType=TEXT(0xa0001) sel=[0,0] composing=[-1,-1]
Ctrl+H -> DeleteBackward [ok] sendKeyEvent(KEYCODE_DEL) no surrounding text @dev.wadackel.limenty.debug
```

`0xa0001` = `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_NO_SUGGESTIONS`.

**`NO_SUGGESTIONS`, which correlated with Gboard's broken composition in F7, is set here too.**
Japanese input with Gboard in this editor is likely to break the same way as in Termius.

The difference from Termius is that this one reports the cursor position (`sel=[0,0]`) while
`getSurroundingText()` returns empty. Termius returns neither (`sel=[-1,-1]`). There are at
least two distinct ways an editor can withhold surrounding text.

In this editor Caretto degrades to KeyEvent. It works, but deletion by grapheme cluster,
`setSelection` that preserves composing, and logical line-edge calculation all go unused.
Implementing `getSurroundingText()` restores the proper path.

As a control, the proper path was confirmed working in an editor that does return surrounding
text.

```
Ctrl+H -> DeleteBackward [ok] deleteSurroundingText(1, 0) delete 1 before @md.obsidian
```

## F10. Ctrl+P / Ctrl+N work on a device

```
Ctrl+N -> MoveDown [ok] sendKeyEvent(KEYCODE_DPAD_DOWN) visual movement
```

Beyond sending the key event, the cursor was seen by eye moving to the line above or below in
the editor.

Whether `Ctrl+P/N` could be implemented with adequate compatibility through the KeyEvent
fallback was one of the most uncertain questions before the implementation. It is now settled:
yes.

Only one app was checked, though. How `sendKeyEvent()` is interpreted depends on the editor's
implementation, so compatibility with Chrome and other editors has to be examined editor by
editor.

## F11. Obsidian ignores forward deletion through deleteSurroundingText

`Ctrl+K` did nothing in Obsidian. The log says it succeeded, but the text on screen does not
change.

```
onUpdateSelection sel=[367,367]
Ctrl+K -> DeleteToLineEnd [ok] deleteSurroundingText(0, 29) kill to line end @md.obsidian
onUpdateSelection sel=[366,366]        ← the cursor only moved back by one
Ctrl+K -> DeleteToLineEnd [ok] deleteSurroundingText(0, 29) kill to line end @md.obsidian
```

If 29 characters had been deleted at position 367, there could not be 29 characters left on the
same line the next time. The same plan is being recomputed.

### Compared against a plain EditText

The same operation was run in Caretto's test editor and the on-screen text read directly with
`uiautomator`.

```
log:    deleteSurroundingText(0, 12) kill to document end @dev.wadackel.caretto
screen: text="test "  sel=[5,5] len=5
```

The 12 characters after cursor position 5 are gone and 5 remain. **The implementation is
correct.**

### Isolating the cause

| Call | Plain EditText | Obsidian |
|---|---|---|
| `deleteSurroundingText(1, 0)` (backward) | works | **works** |
| `deleteSurroundingText(0, N)` (forward) | works | **does nothing** |

Obsidian handles `beforeLength` but ignores `afterLength`. Presumably a difference in its
CodeMirror-based custom `InputConnection`.

**Impact**: in Obsidian, `Ctrl+D` (delete the next character), `Ctrl+K` (delete to end of line),
and `Alt+D` (delete the word forward) do nothing. Movement (via `setSelection`) and backward
deletion work.

### Workaround (implemented and confirmed on a device)

Select the range to delete with `setSelection` and replace it with `commitText("")`.

```
Ctrl+K -> DeleteToLineEnd [ok] setSelection(382, 418) + commitText('') kill to line end @md.obsidian
```

**`Ctrl+K` and `Ctrl+D` were confirmed working in Obsidian on a device.**

That `setSelection` works was already proven the moment movement worked. Only deletion failed,
and the range to erase was already computed correctly from `getSurroundingText`. What was
missing was not the range but the way of erasing it.

Why `commitText("")` and not `KEYCODE_DEL`: if the selection turns out to be empty, Backspace
deletes the character before the cursor while `commitText("")` does nothing. The failure leans
the safe way.

`Shift+End` followed by `Backspace` was considered, but it has two problems. Editors disagree on
whether `Shift+End` selects to the end of the wrapped line or the logical line. And pressing it
at the end of a line leaves the selection empty, so Backspace deletes the preceding character —
which is exactly what pressing `Ctrl+K` twice in a row hits.

It applies only to apps registered under `Select-then-delete apps`. The default is empty, and a
plain `EditText` still uses `deleteSurroundingText` as before. Backward deletion is unchanged.

### A note on verification method

The log only guarantees that the call was made. Whether the text actually changed can be read
straight off the screen with `uiautomator`.

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | tr '<' '\n' | grep -oE 'text="[^"]*"'
```

## F12. deleteSurroundingText deletes around the composing region

Pressing `Ctrl+H` before the composing text was committed deleted nothing. Observed in the test
editor, a plain `EditText`.

```
onUpdateSelection sel=[7,7] composing=[0,7]     ← composing, cursor at 7
Ctrl+H -> DeleteBackward [ok] deleteSurroundingText(1, 0)  ×4
onUpdateSelection sel=[7,7] composing=[-1,-1]   ← cursor still at 7
```

`BaseInputConnection.deleteSurroundingText` extends the delete range out past the composing
span, so with composing at `[0,7]` and the cursor at 7 it tries to delete one character before
position 0. Here that was the start of the text, so nothing happened.

**Had the composition been in the middle of the text, the character before the composing region
would have been deleted.** Worse behavior than deleting nothing.

This is the counterpart to F5, where `setSelection` does not break composing. Movement is safe;
deletion is not.

### Switching to KeyEvent failed too

The first fix fell back to `sendKeyEvent(KEYCODE_DEL)`, but Gboard undid it.

```
sel=[4,4] composing=[0,4]
Ctrl+H -> DeleteBackward [ok] sendKeyEvent(KEYCODE_DEL) composing
sel=[3,3] composing=[0,3]     ← shrinks for a moment
sel=[4,4] composing=[0,4]     ← restored right away (no physical key press in between)
```

The scope of the evidence, stated plainly: `logRawKey` records physical keys only, so screen
touches leave no trace. "No input in between" is strictly "no physical key press in between".
Switching the IME to Samsung Keyboard would settle whether this is Gboard-specific, but that
IME is not in daily use, so it was skipped. The causation remains an inference. It is a strong
one, though: this happens in a plain `EditText`, so there is no restore logic on the editor
side, and the only party that holds a model of the four-character composing string is the IME.

The deletion does reach the editor, but Gboard re-sets its own composing string and restores it.

**There is a structural reason.** With a physical Backspace the IME receives it first and
shrinks its own composing string. Caretto consumes the key ahead of the IME, so Gboard never
learns that a deletion happened. And `AccessibilityInputConnection` has no route to act on
the IME.

### The fix

**Do not intercept deletion while composing.** Return `false`, let it through, and leave the
decision to the IME. Only the IME can edit its own composing string, so this is the one correct
choice.

It also follows the rule of doing nothing when not certain
([architecture.md](./architecture.md#do-nothing-when-not-certain)).

Movement still uses `setSelection`, keeping the composing protection confirmed in F5. It changes
no text, so there is nothing for Gboard to undo — which is also why movement alone works.

**Consequence in use**: use the physical Backspace to delete before the composing text is
committed.

### Confirming against the API

`InputMethod$AccessibilityInputConnection` in `android.jar` (API 36) has exactly nine methods.

```
clearMetaKeyStates / commitText / deleteSurroundingText / getCursorCapsMode
getSurroundingText / performContextMenuAction / performEditorAction
sendKeyEvent / setSelection
```

None of `setComposingText` / `setComposingRegion` / `finishComposingText` exist.
`AccessibilityService.getSoftKeyboardController()` only controls visibility and switches IMEs;
it offers no way to edit. AccessibilityService has no key injection API.

**The only route that gets a key to the IME is pass-through via `return false`.**

### An additional hole closed

`EditorStateTracker` cleared composing in `onStartInput` even on a restart. As F3 shows,
WebView-based editors restart on every keystroke, so in exactly those editors composing tracking
was systematically false. It now keeps the state across a restart.

## F13. A key that was consumed gets no system repeat

Holding `Ctrl+H` down deleted only once.

Scanning the log, **all 1342 key events had `repeat=0`**. Not once was repeat > 0 observed —
not for the chords being consumed, and not for the ordinary character keys being passed through
either.

So Android's key repeat is generated by `InputDispatcher`, which sits **downstream of the
accessibility key filter**.

| Key | Path |
|---|---|
| Key not consumed | passes the filter → `InputDispatcher` generates the repeat → app. It does not pass the filter again |
| Key consumed | stops at the filter → never reaches `InputDispatcher` → **no repeat state is created** |

The repeat handling built on `KeyFilter.pendingOperation` was unreachable code from the moment
it was written.

### The fix

Start a timer when the key press is consumed and repeat the operation at
`ViewConfiguration.getKeyRepeatTimeout()` (first) and `getKeyRepeatDelay()` (thereafter). That
matches the system's own intervals.

`KeyPolicy.decide()` runs again every time. If composing starts or focus moves, the repeat stops
at the same moment a single press would stop being consumed.

The stop conditions are UP, capture starting, another key being pressed, the service shutting
down, and the decision changing, plus **a cap of 500 repeats, about 25 seconds**. If the
keyboard disconnects while held down and the UP is lost, only the timer survives and it would
go on editing forever.

Holding `Ctrl+H` down was confirmed to delete repeatedly on a device.

## F14. Synthetic input cannot fire the remap path on the phone

> Superseded by F16, which measures the reason rather than inferring it. Cite F16.

Result of checking whether automated verification is possible.

| Method | Result |
|---|---|
| `adb shell input keyevent` | `deviceId` comes out as -1, and the event never reaches `onKeyEvent` (F18) |
| writing to the real device node with `sendevent` | **SELinux denies it**. `shell` belongs to the `input` group and the node is `crw-rw---- system input`, but the `u:r:shell:s0` context cannot write to it |

There is no way to synthesize physical keyboard events from outside the phone. **On the phone,
remapping can only be verified with a physical keyboard.** An emulator is different: its built-in
keyboard is a physical one as far as Android can tell, and F20 drives the remap path with it.

What the physical-key check is there to keep out is Caretto's own `sendKeyEvent`, whose events
carry `KeyCharacterMap.VIRTUAL_KEYBOARD` (-1). Whether those events would reach `onKeyEvent` at all
has not been measured; they go to the editor through its input connection, and F18 found injected
events absent from the filter. The check was `deviceId > 0` here and is now
`deviceId >= 0`, which still rejects -1 and also admits id 0, the built-in keyboard (F20).

### What can be automated

- **Instrumentation tests on the device.** `TextBoundary`'s segmentation rules can be verified
  directly against Android's ICU
- **UI operation.** Coordinates come from `uiautomator dump` and `input tap` presses them.
  Reading and writing settings can be checked with `run-as`
- **Reading text off the screen.** `uiautomator dump` answers the question the log cannot:
  whether anything actually changed

## F15. Japanese word segmentation on a device is as expected

Confirmed with an instrumentation test.

```
これは日本語です  →  これ | は | 日本語 | です
```

The internal boundaries match exactly going forward and going backward, so a round trip does
not drift.

On Android `java.text.BreakIterator` delegates to ICU4J, so the quality of the segmentation
itself is unchanged by an implementation that skips `getRuleStatus()` and instead asks whether
a segment contains a letter or a digit. That also confirms the decision taken up through F13:
pick a shape that can be tested on the JVM.

Cluster lengths of 11 for the ZWJ family emoji and 4 for the flag are now pinned on the device
as well. The JVM tests had held back from asserting these, to allow for differences in ICU.

## F16. Synthetic keys cannot enter the remap path on the phone (deviceId is overwritten on injection)

An investigation to settle whether autonomous verification is possible on the phone. The answer
is **it is not**, and there is no workaround there. On an emulator there is one: F20.

The first guard in `onKeyEvent` was then `deviceId > 0 && isFromSource(SOURCE_KEYBOARD)` (now
`>= 0`, F20), which drops anything not from a physical keyboard. `adb shell input keyevent` builds
its events with `deviceId = -1` (VIRTUAL_KEYBOARD), so the guard would reject them; F18 later
found they never reach `onKeyEvent` at all.

From an instrumentation test, `UiAutomation.injectInputEvent()` does let you build an event with
a chosen `deviceId`. The id of the keyboard actually connected (54) was set, the event injected,
and an `OnKeyListener` attached to the receiving `EditText` to see what arrived.

```
injectedWithDevice=54  →  keysAtEditor=[code=36 device=-1 source=0x101 action=0]
```

**The framework overwrites `deviceId` at injection time.** Specifying the id of a device that
really exists changes nothing. `sendevent` is blocked by SELinux (F14), so that is out too.

### What can and cannot be verified autonomously on the phone

F20 moves most of the "Cannot" row to the emulator; what stays with the phone is listed there.

| | Method |
|---|---|
| Can | Layout, colors, folded state (`screencap`); accessibility labels (`uiautomator dump`); reading and writing settings (`run-as`); grapheme and word boundaries (instrumentation) |
| Cannot | Anything about remapping actually happening. Deletion, movement, word-wise operations, repeat, the pass-through decision, and **that nothing behind the capture dialog breaks while it is open** |

That last item hurts. Verifying `captureMode` means confirming that the service does not consume
the key, and a synthetic key that never reaches the service passes trivially. **Passing proves
nothing.**

### The service cannot be observed from instrumentation

From the test process, `CarettoService.isConnected` was always `false`. The accessibility
service is bound in a different process from the test runner, so they share no static state.
Reading `EventLog` to decide fails for the same reason.


## F17. Compose's TextField does not accept undo / redo through the context menu

On `Hyper+Z` not working in a Compose text field: the cause is settled, and it is Compose's
implementation.

The branch in `StatelessInputConnection.performContextMenuAction` only covers the range
`16908319` to `16908329`. Disassembling it shows only four ids are actually handled.

**Method.** The same three commands answer any other question about a dependency's behaviour, so
they are worth keeping. The AAR is in the Gradle cache; `classes.jar` is inside it.

```bash
find ~/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation -name '*.aar'
unzip -o -q <the aar> classes.jar && unzip -o -q classes.jar 'androidx/compose/foundation/text/**'
javap -p -c androidx/compose/foundation/text/input/internal/StatelessInputConnection.class
```

Reading the `tableswitch` in `performContextMenuAction` is what settles it: the case labels are the
ids handled, and everything else falls to `default`.

| id | Constant | What Compose does |
|---|---|---|
| 16908319 | `selectAll` | sets the selection to 0..length |
| 16908320 | `cut` | sends `KEYCODE_CUT` to itself |
| 16908321 | `copy` | sends `KEYCODE_COPY` to itself |
| 16908322 | `paste` | sends `KEYCODE_PASTE` to itself |

**`undo` (16908338) and `redo` (16908339) do not even fall inside the range of the branch; they
hit `default` and return `false`.**

Compose's key mapping, on the other hand, does have `UNDO` and `REDO`, bound to
`Ctrl+Z` / `Ctrl+Y` / `Ctrl+Shift+Z`. **The menu route does not reach it; the key route does.**

`TextView` is the reverse: the menu route reaches its undo stack, the key route does not.
`Ctrl+Z` is handled in `onKeyShortcut`, and injected key events do not pass through it.

### The fix

For undo and redo only, **send both the menu action and the key event**. There is no way to tell
which implementation is on the other side, so both go out and one of them lands.

Worth recording why it cannot be told apart: Compose reports the accessibility node's
`className` as `android.widget.EditText`, so the node type does not distinguish it from an
EditText.

### Result

Undo now works in both Compose text fields and Obsidian. It also undoes in an EditText.

**Double application is still unverified.** How many steps a single press undoes in an EditText
could not be judged by eye, because TextView's undo is chunked by groups of keystrokes.

Settling it takes one check: with undo sent as **the key event only**, does an EditText still
undo? If it does, the menu action is unnecessary and the room for double application disappears
entirely. If it does not, both are needed, and the risk of double application has to be accepted.


## F18. The accessibility key filter never receives injected events

F16 established that injection rewrites `deviceId`, and inferred from that why the remap path
could not be exercised. The inference was right but the reason was incomplete: **the events do not
reach `onKeyEvent` at all.**

Measured with the physical-key guard deliberately lifted, so nothing of ours could have rejected
them, and with verbose logging on so every arriving key would be recorded:

```
capture open when injecting: True
raw key lines seen by onKeyEvent: 0
```

Zero. Not rejected — absent. `adb shell input keyevent` and `input keycombination` produce events
that the app's own window receives (that is how the capture dialog used to be tested) but that the
accessibility filter never sees.

### What this costs

**On the phone, anything that reads keys through the service can only be tested with a physical
keyboard.** That now includes the capture dialog, which reads chords through the service so that
combinations the window manager takes — `Alt+Tab` among them — can still be bound. On an emulator
the built-in keyboard reaches the service (F20), but injection still does not: this entry is
unchanged there.

It also means lifting the `deviceId` guard buys nothing for injected events. It was added while
fixing this and removed again once measured: a guard cannot be the reason an event is missing when
the event never arrives. Loosening it to `>= 0` (F20) is about a different source, the built-in
keyboard, and changes nothing here.

### The trap

The old capture dialog read keys through Compose, so injection **did** reach it, and every
emulator test of capture passed. Those tests were measuring the app's window, not the service. A
test that passes against the wrong layer is worse than no test, because it reads as coverage.

## F19. PayPay refuses to run while Caretto is on, and nothing in Caretto's declaration changes that

PayPay 5.74.0 (updated 2026-11-20) shows a restriction screen when it opens:

```
Change Your Device's Accessibility Settings
For your security, your use of PayPay is temporarily restricted. ...
Apps to turn OFF: 1 apps
• Caretto
```

Each variant below was built as a separate application id (`.probe`) and enabled on its own, and
the screen was read with `uiautomator dump`. **Every one of them was restricted:**

| Variant | Result |
|---|---|
| As shipped | restricted |
| `android:isAccessibilityTool="true"` | restricted |
| Installed with `installerPackageName=com.android.vending` | restricted |
| No key filtering at all: `flagRequestFilterKeyEvents` removed, only `flagInputMethodEditor` | restricted |

So the check does not hinge on the capabilities the service asks for or on where it was installed.
It reads as a list of services PayPay accepts, and Caretto is not on it.

### When it checks

| What happened | Restricted? |
|---|---|
| Caretto enabled while PayPay was already in front | no |
| The notification shade opened and closed over it | no |
| Home, then PayPay opened again (process still alive) | yes |
| In DeX, PayPay left visible, another window focused, then PayPay focused again | yes |
| Restricted, Caretto turned off, PayPay opened again | no — the screen lifts |

It checks when it starts, resumes, or regains focus, and not in between.

### Turning off automatically loses a race

A probe that called `disableSelf()` on the first event from PayPay's package got through only
some of the time: 1 in 5 on resume in one run, 3 in 3 in another, and a restriction screen reading
`Apps to turn OFF: 0 apps` when it lost. That count is the race showing: the check saw the service,
and by the time it drew the list the service was gone. The event arrives after PayPay's window is
already up, so it cannot reliably be first.

Turning Caretto off **before** launching PayPay got through 9 times in 9. That is what the quick
settings tile does by hand, and why it was chosen over anything automatic.

### Writing the setting from the app

With `WRITE_SECURE_SETTINGS` granted from adb, the app can add itself to
`enabled_accessibility_services` and the service is bound about 12 ms later. Other entries in the
list survive as long as the write reads the list first and edits it, rather than replacing it. The
grant survives `adb install -r` and `am force-stop`.

## F20. The emulator's built-in keyboard reaches the remap path (deviceId 0)

Device: Android 36 emulator (Google APIs, arm64-v8a, `hw.keyboard=yes`), not the Fold.

F16 and F18 hold for the phone. On an emulator there is a keyboard Android treats as physical: the
built-in one, listed by `dumpsys input` as `qwerty2 (aka device 0 - built-in keyboard)`. A key sent
through the emulator's gRPC `sendKey` (Evdev codes, a separate `keydown` and `keyup` for each key)
enters the guest as a kernel input event from that keyboard, not as an injected event. It reaches
`onKeyEvent` with `deviceId=0`, `source=KEYBOARD` and the real modifier meta state:

```
key DOWN CTRL_LEFT code=113 scan=29 meta=0x3000 device=0 repeat=0
key DOWN A code=29 scan=30 meta=0x3000 device=0 repeat=0
```

The only thing that stopped it was the physical-key guard, then `deviceId > 0`. Measured in the
test editor by typing `abc` and then `Ctrl+A`, `X`, `Ctrl+E`, `Ctrl+H`:

| Guard | Single-line EditText | Compose TextField | Caretto's log |
|---|---|---|---|
| `deviceId > 0` | `X` (the field's own `Ctrl+A` selected all) | empty | no key lines at all |
| `deviceId >= 0` | `Xab`, `sel=[3,3]` | `Xab`, `sel=[3,3]` | the lines below |

```
Ctrl+A -> MoveLineStart [ok] setSelection(0) document start @dev.wadackel.caretto
Ctrl+E -> MoveLineEnd [ok] setSelection(4) document end @dev.wadackel.caretto
Ctrl+H -> DeleteBackward [ok] deleteSurroundingText(1, 0) delete 1 before @dev.wadackel.caretto
```

The text was read from the editors, not from Caretto's log: the single-line field on screen, the
Compose field from a `uiautomator dump` taken after the last chord, and both selections from the
test editor's own `sel=` line.

### The guard is now `deviceId >= 0`, in every build

The guard is there to keep out Caretto's own `sendKeyEvent` (whether those would reach the filter
at all is unmeasured, F14). They carry `KeyCharacterMap.VIRTUAL_KEYBOARD` (-1), so `>= 0` still
rejects them. The only id newly admitted
is 0, the built-in keyboard. The Fold has none: `dumpsys input` there lists -1 (`Virtual`) and the
phone's own devices with ids above 0, and the external keyboard came in as 54 (F16). On a device
that does have a built-in keyboard, it is a physical keyboard Caretto should remap.

Loosening it in debug builds only was rejected: emulator tests would then pass through a branch
release builds do not have, which is the trap F18 describes.

### Injection is unchanged

On the same emulator with the new guard and verbose logging on, `adb shell input keyevent` and
`input keycombination` reached the editor (its own `Ctrl+A` selected all) and Caretto logged no key
line. Injected events carry -1 (F16), so the guard would reject them anyway and this run cannot tell
absence from rejection; F18 is the measurement that they are absent.

### Sending keys

- **gRPC `sendKey` works.** Started with `-grpc <port> -grpc-use-token`, the endpoint listened on
  127.0.0.1 only and answered a call without the token with `Unauthenticated`. The token is
  `grpc.token` in the emulator's discovery file, which `adb emu avd discoverypath` names. Started
  with a plain `-grpc <port>`, it listens on every interface with no authentication, so do not
  start it that way. `tools/emu` sends chords this way ([debugging.md](./debugging.md), "The dedicated emulator").
- **`adb emu event send` does not.** It returns `OK` and produces no kernel event.
- **`adb emu event text` reaches the kernel** but cannot hold a modifier, so it cannot make a chord.

### What the emulator covers, and what still needs the phone

The emulator now covers what Caretto does with a chord from a physical keyboard: whether it is
remapped or passed through, and what the operation does to an editor's text and selection.

Still on the phone:

- Anything that depends on the IME: Gboard and Samsung Keyboard, Japanese composition (F1, F5, F12)
- Anything specific to the Fold: folding, split screen, DeX
- How it feels with a Bluetooth or USB keyboard, including whether the change to the guard left that
  unchanged
- The `TextBoundary` instrumentation tests, which are pinned to the phone's ICU (F15)
- A real Hyper chord: the emulator's keyboard has no Meta key (F21)

Key repeat (F13) and the capture dialog were measured on the emulator afterwards and are covered
there (F21).

## F21. What the emulator scenarios measured

Device: the `caretto_e2e` emulator of F20 (Android 36, Google APIs, arm64-v8a), driven by
`tools/e2e`, not the Fold.

### The built-in keyboard has no Meta key, so a Hyper chord cannot be pressed

`tools/emu press hyper+a` sends evdev 125 (`KEY_LEFTMETA`) with the other three modifiers, and it
arrives as no key at all:

```
key DOWN UNKNOWN code=0 scan=125 meta=0x3053 device=0 repeat=0
Ctrl+Alt+Shift+A pass-through (no binding) @dev.wadackel.caretto
```

The keyboard's layout, `/vendor/usr/keylayout/qwerty2.kl` (read with `adb root`), has no line for
125 or 126. It also maps several keys to something other than a PC keyboard: evdev 1 (Esc) to
`BACK`, 107 (End) to `ENDCALL`, 102 (Home) to `HOME`, 59 (F1) to `MENU`. Three ways round it failed:

| Tried | Result |
|---|---|
| `hw.keyboard.charmap=Generic` in the AVD | the device still comes up as `qwerty2` with the same layout |
| As root, a layout in `/data/system/devices/keylayout/` (by vendor and product, and named in an `.idc` under `/data/system/devices/idc/`) | not read: after a framework restart `EventHub` still opened `/vendor/usr/keylayout/qwerty2.kl` with `configuration=''` |
| A second keyboard, `-qemu -device virtio-keyboard-pci` | it is also named `qwerty2` and gets the same layout |

So the scenarios press each Hyper operation on a stand-in chord: the same key with Ctrl+Alt+Shift,
bound in addition to the Hyper defaults for those scenarios only (`hyper-standin-*`). That checks
select all, the clipboard through the context menu, undo and redo sent both ways (F17), and going
home, end to end in all four editors. **Whether Caretto matches the Meta bit of a real Hyper chord
is not checked on the emulator.** It rests on `MappingEngineTest` and on the phone.

### Key repeat reproduces

Holding Ctrl+H (keydown, 1.5 s, keyup through gRPC) at the end of `abcdefghij` deleted all ten
characters in every editor. The filter saw one `DOWN` and one `UP`, all with `repeat=0`, as on the
phone (F13). The deletions are Caretto's own timer: the first repeat came 406 ms after the press,
the next 51 ms after that. With `startRepeating` removed, the same hold deleted one character.

The repeat also stops on release. A 0.7 s hold at the end of 36 characters deleted 9 in every
editor in one run, and the text then stayed as it was for 1.5 s with no key pressed. With the
release no longer stopping the repeat, the text went on to empty.

### The capture dialog works with a key from the built-in keyboard

With the key map's Add binding dialog open, Ctrl+H (bound to delete backward) was taken by the
dialog, and the operation picker opened for it. Picking Move forward then made Ctrl+H move forward
in the test editor. With `offerToCapture` made to refuse, the picker never opened. No editor has focus behind the dialog, so an
operation run on the captured chord as well would change no text. The scenario therefore checks
Caretto's own log for an operation line for that chord: with the service made to run it as well as
capture it, `Ctrl+H -> DeleteBackward [failed] no InputConnection` was logged and nothing else showed it.

### Compose deletes on Ctrl+H by itself

With remapping off, or with the app in the pass-through list, Ctrl+H reaches both Compose
editors and deletes a character there, as Caretto's binding would. The EditTexts do nothing with
it. An editor's text alone therefore cannot show that Caretto handled Ctrl+H in Compose. The
scenarios also check that a remapped chord never reaches the editor as a key, which the test
editor reports. Ctrl+A tells the two apart everywhere: the editors' own handling selects all.

### `adb emu event text` drops long text

A text of 16 characters or more is dropped whole, with `OK`; 15 arrives. `tools/emu type` sends
text in pieces of 15.
