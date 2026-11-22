# Using Caretto

What Caretto needs, how to turn it on, and what each binding and setting does. Building the APK is
in [development.md](./development.md#building); how a chord becomes an edit is in
[architecture.md](./architecture.md).

## Requirements

- **Android 13 or later** (`minSdk 33`). The Accessibility Input Method behind the editing
  operations arrived in API level 33
- A physical keyboard. On-screen keyboards are out of scope
- The seven Hyper bindings (clipboard and home) need a **keyboard that can send
  `Ctrl+Alt+Shift+Meta` together from a single key**. Custom keyboard firmware (ZMK, QMK) can do it.
  If your keyboard cannot, the other 13 still work

## Installing and enabling

Build the debug APK ([development.md](./development.md#building)), then install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch Caretto
2. Press `Open settings` on the status row and enable Caretto in accessibility settings
3. Check that the status row in the app reads `Remapping`

The switch on the status row pauses remapping without turning the service off: every key then
passes through until you switch it back on.

An accessibility service does nothing until the person enabling it grants it explicitly.

### Switching it from quick settings

Some banking apps refuse to run while an accessibility service they do not recognise is on. PayPay
is the confirmed one: it shows a restriction screen naming Caretto, and lifts it once Caretto is off
and the app is opened again. No setting on Caretto's side gets it past that check, so the way
through is to turn Caretto off for as long as the app is open. [findings.md](./findings.md) F19 has the
experiments.

A quick settings tile makes that one tap. It needs a permission only adb can grant:

```bash
adb shell pm grant dev.wadackel.caretto android.permission.WRITE_SECURE_SETTINGS
```

Then add the Caretto tile from the quick settings editor. Until the grant is in place the tile shows
as unavailable. A reinstall with `adb install -r` keeps the grant; uninstalling drops it, and so
does a bare `connectedDebugAndroidTest`, which uninstalls the app when it finishes;
`tools/device-tests` puts it back.

Turning Caretto on from a locked device asks for the device to be unlocked first.

## Key map

These are the presets. **You can change any of them inside the app.**

| Input | Operation |
|---|---|
| `Ctrl+B` / `Ctrl+F` | Move backward / forward |
| `Ctrl+P` / `Ctrl+N` | Move up / down |
| `Ctrl+A` / `Ctrl+E` | Move to line start / line end |
| `Ctrl+H` / `Ctrl+D` | Delete backward / forward |
| `Ctrl+K` | Delete to line end. At the end of a line, delete the newline |
| `Alt+B` / `Alt+F` | Move backward / forward by word |
| `Alt+H` / `Alt+D` | Delete word backward / forward |

Word-wise operations sit on Alt, following Emacs. `Alt+H` is the exception: it is not Emacs's
`M-DEL` but a binding of our own, chosen to match `Ctrl+H` being Backspace here.

### The Hyper bindings

Sending `Ctrl+Alt+Shift+Meta` together from a single key produces a modifier Android does not use
itself. Your hand keeps the same shape it has for `Cmd` on macOS, and nothing collides with a system
shortcut. As Requirements says, you need a keyboard that can send it.

| Input | Operation |
|---|---|
| `Hyper+C` / `Hyper+X` / `Hyper+V` | Copy / Cut / Paste |
| `Hyper+A` | Select all |
| `Hyper+Z` / `Hyper+Y` | Undo / Redo |
| `Hyper+H` | Go home |

Hyper is all four modifiers, Shift included, so redo cannot be `Cmd+Shift+Z` the way it is on macOS.
Shift is already spent on Hyper and is not left to tell chords apart. Redo is on `Hyper+Y` instead.

The clipboard operations are handed to the editor's own context menu. It is the same route Copy and
Paste take from the text selection toolbar.

**Going home does not care whether an editor is there.** It works in a terminal, and on a screen
with no text field at all. Show recent apps, Back and Show all apps (the app drawer) are available
too, but nothing is bound to them by default.

**Use `Alt+Tab` as it is for switching apps.** Android handles it natively, so this app does nothing
with it. Routing it through Hyper produces a different chord, and switching stops working.

Press the round + button at the bottom right of the home screen, press the combination you want,
then pick the operation. Tapping a binding opens a sheet to change its key or its operation, or to
remove it. Removing a binding, taking a key from another operation, and `Reset to defaults` at the
end of the list all happen at once and offer Undo.
**One operation can answer to several keys** (both `Ctrl+B` and `Alt+←` on Move backward, say). A
key always points at a single operation, so binding a combination that is already in use takes it
away from the operation it had.

Every binding needs one of `Ctrl`, `Alt` or `Meta`. A binding with no modifier would eat ordinary
typing.

None of this is remapped in terminal apps; the keys pass through.

Keep using Gboard as your IME. Caretto does not replace the IME.

## Pass-through apps

An app that needs raw Control sequences of its own, Termius being the obvious one, is excluded
whole. Four are set by default, and you can add or remove them under the gear at the top right of
the home screen, in `Pass-through apps`. `Add app` searches the list of installed apps, so you never
have to look a package name up and type it in.

- `com.server.auditor.ssh.client` (Termius)
- `com.termux`
- `com.sonelli.juicessh`
- `org.connectbot`

## Use Samsung Keyboard when typing Japanese in Termius

With Termius, Gboard and a physical keyboard together, the IME's intermediate state is typed out
before it is committed.

```
Typing 「これは日本語」
→ ここｒこれこれｈこれはこれはｎににほにほｎにほんにほんｇにほんご日本語日本語
```

This has nothing to do with Caretto, and it reproduces just the same with Caretto stopped
completely from accessibility settings. F1 in [findings.md](./findings.md) has the results of
isolating the cause on a device.

Switching the IME to Samsung Keyboard types correctly. Switch to it when you need Japanese in
Termius. Outside Termius (Chrome, Obsidian, a plain EditText) Gboard is fine as it is.

## Known limitations

- `Ctrl+P` / `Ctrl+N` follow the app's own reading of `DPAD_UP` / `DPAD_DOWN`, not the wrapped lines
  on screen.
- `Ctrl+A` / `Ctrl+E` work on logical lines (`\n` boundaries). Not the start and end of a wrapped
  line.
- Deletion with a selection cannot be expressed through `deleteSurroundingText()`, so it relies on a
  KeyEvent.
- **Deletion keys are not intercepted while text is composing**, in Japanese or any other IME. Only
  the IME can edit composing text, and there is no way to reach it from here. Use the physical
  Backspace to delete before committing. Movement works while composing as it always did.
- Moving to the start or end of a line reads 4096 characters either side of the cursor to find the
  boundary. When the line does not fit in that window it switches to the KeyEvent fallback (which
  can happen even for a line shorter than 4096 characters, depending on where the cursor sits).
- Some editors ignore forward deletion through `deleteSurroundingText()`. Registering such an app
  under `Select-then-delete apps` switches forward deletion alone to selecting the range and
  replacing it with an empty string, which works. Obsidian is the confirmed case and is registered
  by default. F11 in [findings.md](./findings.md) has the details.
- Excluding terminal apps rests entirely on registering them under `Pass-through apps`. Termius
  returns `TYPE_CLASS_TEXT` for its `inputType`, so it cannot be told apart by the kind of editor it
  is. Register the package name of any terminal app that is not in the defaults.
