# Architecture

How Caretto turns a chord into an edit, and the reasons behind that which cannot be read off the
code. Building, testing and the directory layout are in [development.md](./development.md); what
running it on a device revealed is in [findings.md](./findings.md).

## How it works

`AccessibilityService.onKeyEvent()` narrows the keys down and consumes only the ones to be remapped.
Everything else returns `false` and continues along Android's standard input path, so ordinary
typing is never regenerated.

A remapped key is not a substituted KeyEvent. It runs as an editing operation against the
`AccessibilityInputConnection` that the Accessibility Input Method has provided since API level 33.

There are three routes.

**1. Compute the edit from the surrounding text.** Read around the cursor, decide how many
characters to delete or where to move, then apply.

| Operation | How |
|---|---|
| `Ctrl+F` / `Ctrl+B` | `setSelection()` |
| `Alt+F` / `Alt+B` | Find the word boundary, then `setSelection()` |
| `Ctrl+A` / `Ctrl+E` | Find the logical line (`\n` boundaries), then `setSelection()` |
| `Ctrl+H` / `Ctrl+D` (no selection) | `deleteSurroundingText()` |
| `Alt+H` / `Alt+D` | `deleteSurroundingText()` up to the word boundary |
| `Ctrl+K` | Measure the distance to the end of the line, then `deleteSurroundingText()` |

**2. Hand it to the editor's own context menu.** Nothing needs to be read, and it is the same route
Copy and Paste take from the text selection toolbar.

| Operation | How |
|---|---|
| `Hyper+C` / `Hyper+X` / `Hyper+V` | `performContextMenuAction(copy / cut / paste)` |
| `Hyper+A` | `performContextMenuAction(selectAll)` |
| `Hyper+Z` / `Hyper+Y` | `performContextMenuAction(undo / redo)` and `Ctrl+Z` / `Ctrl+Y`, both |

Undo and redo are the only pair sent both ways, because the receiving side is split between two
implementations. F17 in [findings.md](./findings.md) has the details.

**3. Fall back to a KeyEvent.** Only where no editing operation exists.

| Operation | How |
|---|---|
| `Ctrl+P` / `Ctrl+N` | `sendKeyEvent(DPAD_UP / DPAD_DOWN)` |
| Deletion (with a selection) | `sendKeyEvent(DEL / FORWARD_DEL)` |
| `Ctrl+A` / `Ctrl+E` (outside the read window) | `sendKeyEvent(MOVE_HOME / MOVE_END)` |

`Hyper+H` alone does not address an editor. It goes to the system through `performGlobalAction()`,
so it works on a screen with no text field.

`sendKeyEvent()` is confined to `EditorBridge` and can be turned off in one move from the app's
settings. With it off, `Ctrl+P` / `Ctrl+N` and deletion with a selection are not remapped and reach
the app as they are. The one exception is `Ctrl+A` / `Ctrl+E` dropping to the fallback because the
line does not fit in the read window: that is only discovered after the key has been consumed, so
nothing happens and the log records it.

## Editing operations, not replacement key events

The first idea was to consume `Ctrl+H` and send a Backspace KeyEvent in its place. Two things in
the platform ruled that out as the main route:

- `onKeyEvent()` is defined for filtering. Returning `true` consumes the event and returning
  `false` delivers it onward; there is no way to rewrite a received event and pass the changed one
  on.
- `sendKeyEvent()` exists on the accessibility InputConnection, but Android's documentation does
  not recommend it for normal operation and describes it as mainly for `InputType.TYPE_NULL`
  editors.

So a chord becomes the editing operation it stands for, and `sendKeyEvent()` stays the fallback
for the few operations that have none, isolated so that it can be switched off.

The same choice rules out the other way remappers are built: grabbing the whole input device,
intercepting at the evdev level, regenerating every KeyEvent, or anything like Key Mapper's Expert
mode, which handles physical input at a low level. Before the implementation, the doubled Japanese
input in Termius was blamed on that mode; F1 in [findings.md](./findings.md) showed it reproduces
with no remapper running at all. The rule stands on its own, not on that diagnosis: the
accessibility service already receives every key ahead of the IME and the app, so taking the
device over is unnecessary, and regenerating keys that are not remapped is fragile. That the
diagnosis was wrong is no reason to grab the device after all.

## Why not an existing remapper, or the keyboard

- **One UI's physical keyboard settings** cannot remap an arbitrary chord, such as `Ctrl+H` to
  Backspace.
- **Key Mapper** came close, but keeping Gboard as the IME needed its Expert mode, which is the
  low-level design ruled out above.
- **The keyboard's firmware** (ZMK here) could turn `Ctrl+H` into Backspace easily, but from that
  moment on Backspace is all Android ever receives. In something like Termius → SSH → Neovim, the
  real `Ctrl+H` is gone.

**Which of the two meanings you want depends on what is in the foreground.** Android is the only
side that holds that information, so the remapping happens there. The keyboard keeps the physical
layout, layers, tap and hold, the modifiers, the Bluetooth profiles and `LANG1` / `LANG2`, and
sends the Emacs chords as they are.

The other way round, **`Alt+Tab` for switching apps is deliberately left to the firmware and
Android.** Android handles it natively and handles it better, and there is no reason to step in.
The principle is not "always on Android" but "wherever the context is".

## Pass-through apps

A terminal app such as Termius is excluded whole. From Android's side, Termius → shell, Termius →
tmux and Termius → Neovim look the same, so there is no telling whether a `Ctrl+H` in it is meant
for the shell or for an editor; the only safe answer is to leave every key to the app.

The list is kept by package name because nothing else tells a terminal apart: Termius reports
`TYPE_CLASS_TEXT` for its `inputType`, like any text field. `ContextResolver` checks both the
current editor's package and the foreground package against the list, so a terminal is excluded
even while it has no editor session.

## Do nothing when not certain

An incorrect remap destroys input the user could otherwise perform; a missed remap only loses a
convenience. So every case that cannot be judged passes the key through:

- no Accessibility Input Method, or no InputConnection
- no editor, or an editor reporting `TYPE_NULL`, which Android documents as the kind that expects
  raw key events
- the app is on the pass-through list
- the chord has no binding
- the operation changes text while the IME is composing, since only the IME can edit its own
  composing text (F12 in [findings.md](./findings.md))
- the operation needs the KeyEvent fallback and the fallback is off

Remapping happens only while a text editor is active, not per app. In Chrome, `Ctrl+F` on a page
stays Chrome's own Find, and in a text field it moves the cursor forward. The global actions are the
one exception: going home has no editor to ask about, and a binding for it would be pointless if
it stopped working whenever nothing is focused. `KeyPolicy` holds the whole decision as one pure
function, so each combination is laid out in its test.
