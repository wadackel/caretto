# Development

How Caretto is built, tested and checked on a device. Everything here assumes the owner's setup: a
Galaxy Z Fold, and the Android SDK outside Nix at `ANDROID_HOME`. Pull requests from outside are
not accepted, so the scripts are not made to work anywhere else; read them as a record of how
this repository is worked on. Rules for an AI agent working here are in [AGENTS.md](../AGENTS.md),
how the code is put together is in [architecture.md](./architecture.md), and how to observe
anything on a device, with the traps, is in [debugging.md](./debugging.md).

## Building

JDK 17 and the Android SDK (platform 36 / build-tools 36.0.0) are required.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # cursor movement, deletion, line-end calculation
./gradlew :app:lintDebug
tools/device-tests run <serial>     # grapheme and word boundaries on the device ICU
```

The last one runs `:app:connectedDebugAndroidTest` on the named device and puts back what that
task's uninstall takes: the preferences, the accessibility grant and the tile ([debugging.md](./debugging.md) recipe 1).

The UI is Jetpack Compose and Material 3, and the dependencies stop there plus `androidx.activity` /
`androidx.lifecycle`. `core/` holds no Android IPC, so it can be tested on the JVM with nothing
installed.

`sdk.dir` in `local.properties` has to point at the SDK.

The version name carries the commit the build came from, `0.1.0+e984f34`, with `-dirty` added when
the tree had uncommitted changes, so a phone can be matched to the tree it runs: About shows it,
and so does `adb shell dumpsys package dev.wadackel.caretto | grep versionName`.

About's open-source list is generated at build time by the AboutLibraries Gradle plugin from the
variant's dependencies, so adding or removing one changes the list on the next build. The plugin
runs offline: licence texts come from `app/aboutlibraries/licenses/`, and
`OpenSourceNoticesTest` fails when a dependency brings in a licence with no text there. Add the
text as `<SPDX id>.json` beside `Apache-2.0.json`. The test also fails when a dependency brings an
artifact into a group whose row has a name picked by hand (`GroupNames` in
`ui/about/OpenSourceNotices.kt`): add the artifact there if the name still covers it, or pick one
that does.

Where `java` is not on `PATH`, run Gradle as `./gw` with the same arguments. It enters the Nix
devShell in `flake.nix`, which provides JDK 17 and puts the SDK's tools on `PATH`.

CI runs the first three commands on every push. Lint runs with `warningsAsErrors`, so a new warning
fails the build.

## The dedicated emulator and the scenarios

`tools/emu start` brings up an emulator of its own (`caretto_e2e`, on port 5600) with Caretto
installed and enabled, and `tools/emu press ctrl+a` sends a chord to it from its built-in keyboard.
Those keys come in as a physical keyboard's, so Caretto remaps them
([findings.md](./findings.md) F20). It never touches another AVD or a phone: the emulator is on an
adb server of its own, which nothing else is on, so plain `adb` does not see it; reach it with
`tools/emu adb <args>`.

```bash
tools/emu start           # create the AVD if missing, boot it, install Caretto and enable it
tools/emu press ctrl+a    # press and release a chord
tools/emu adb shell ...   # adb on that emulator, through its own adb server
tools/e2e                 # run every scenario in every test editor it applies to
tools/e2e --list          # the scenario ids; --scenario <id> and --editor <name> narrow a run
```

`tools/e2e` builds and installs the current debug build, checks every default binding, key repeat,
the capture sheet, the pass-through list and remapping switched off, and ends with one line,
`e2e: PASS …` or `e2e: FAIL …`, and an exit code to match. The emulator's keyboard has no Meta
key, so the Hyper operations run there on `Ctrl+Alt+Shift` stand-ins
([findings.md](./findings.md) F21). The settings that let it run beside another emulator, how a
chord gets in, and how to add a scenario are in [debugging.md](./debugging.md), "The dedicated
emulator".

## Verifying on a device

Use `Test editor` and `Log`, both under the gear at the top right of the home screen. Turn
`Verbose logs` on there and the log also records editor start and finish, cursor position updates,
and the reason behind every pass-through decision.

[debugging.md](./debugging.md) covers connecting a device and reading state off it, including the
traps — a key injected with `adb shell input` cannot reach the remap path at all, so a test built
on one passes for the wrong reason. An emulator's built-in keyboard can, through its gRPC `sendKey`
([findings.md](./findings.md) F20).

On the phone, these are the checks that came before everything else, in this order. The first is
the condition that decides whether the app can be used at all; the later ones each take one group of
operations. [findings.md](./findings.md) records what they showed.

**Phase 1 — pass-through safety**
Switch remapping off on the status row and check with the service alone enabled.

- Gboard Japanese input does not double up in Termius
- Typing is unchanged in Chrome, a plain EditText, and a Compose TextField

This is the Go/No-Go condition that comes before every other one.

**Phase 2 — deciding which apps are not remapped**
With remapping on, check that `Ctrl+H` and the rest reach Termius as they are. Watch whether the
decision survives switching apps, moving focus, split screen, and folding or unfolding the device.

**Phase 3 — `Ctrl+F` / `Ctrl+B`**
Check ASCII, committed Japanese, Gboard composing text, emoji, with and without a selection, single
line and multi-line.

**Phase 4 — `Ctrl+H` / `Ctrl+D`**
The same conditions, plus the behaviour at the start and end of the text, and that deleting while
composing does not break the composition.

**Phase 5 — KeyEvent fallback**
Check `Ctrl+P` / `Ctrl+N`, and `Ctrl+H` with a selection in place. Toggling `Key event fallback`
lets you compare compatibility app by app.

**Through Termius and beyond**
Check that Control sequences reach all the way through Termius → SSH → tmux → Neovim, and that
Gboard Japanese input is healthy at the same time.

## Project layout

```
app/src/main/java/dev/wadackel/caretto/
├── core/
│   ├── KeyChord.kt            chord representation of a physical key press
│   ├── KeyFilter.kt           DOWN / UP pairing and key repeat
│   ├── MappingEngine.kt       KeyChord → MappedAction
│   ├── KeyBindings.kt         binding persistence, and the cache for the hot path
│   ├── BindingCodec.kt        storage format for bindings
│   ├── EditorOperation.kt     what the editing operations mean
│   ├── MappedAction.kt        everything a binding can point at (editor and global operations)
│   ├── EditPlan.kt            the edit to apply, and a snapshot of the surrounding text
│   ├── InputContext.kt        classification of whatever is in the foreground
│   ├── ContextResolver.kt     pass-through app / editor present / unknown decision
│   ├── EditorStateTracker.kt  cache of the editor session and the selection
│   ├── EditPlanner.kt         pure logic deciding the concrete edit from the surrounding text
│   ├── EditorBridge.kt        applying it to AccessibilityInputConnection
│   ├── KeyPolicy.kt           intercept or pass through (pure logic)
│   ├── TextBoundary.kt        grapheme cluster boundaries
│   ├── EventLog.kt            ring buffer behind the on-screen log
│   └── Prefs.kt               settings persistence
├── service/
│   ├── CarettoService.kt      the AccessibilityService itself
│   └── RemapInputMethod.kt    watching the editor session
└── ui/
    ├── MainActivity.kt        the Compose host. Final captureMode cleanup lives here too
    ├── Components.kt          spacing scale, row groups, chord keys, page bar
    ├── UiCommon.kt            labelling chords and operations
    ├── ServiceStatus.kt       reading and switching whether the service is enabled
    ├── ServiceTileService.kt  the quick settings tile that switches it
    ├── theme/Theme.kt         palette, IBM Plex type, Material 3 theme
    ├── about/                 About: build, source, licences and the generated notices
    ├── main/                  home, settings pages, ViewModel, key map and app picker sheets
    ├── TestEditorActivity.kt  editor for checking on a device
    └── testeditor/            four kinds of field (two EditText, two Compose TextField)
```

`EditPlanner` holds no Android IPC, so cursor distances, deletion lengths, and line-end calculations
can be tested without a device (`app/src/test/`).
