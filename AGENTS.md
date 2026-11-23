# Caretto

An Android accessibility service that reads physical-keyboard chords as editing operations.

## Before verifying anything on a device

Read [docs/debugging.md](./docs/debugging.md). It is written
for a reader with no memory of the previous session and covers the traps that have already cost
time — among them that force-stop silently revokes the accessibility grant, that the preferences
file is stale for a moment after any UI action, and that a `uiautomator dump` makes the service
disconnect and reconnect.

Two constraints shape every verification here:

- **Injected keys cannot exercise remapping.** `adb shell input` and other injected keys never
  reach the service, so a test built on one passes for the wrong reason
  ([docs/findings.md](./docs/findings.md) F16, F18). On an emulator, the built-in keyboard counts:
  a chord sent with `tools/emu press`, through its gRPC `sendKey`, is remapped (F20), and `tools/e2e`
  checks every default binding, key repeat, the capture sheet, the pass-through list and
  remapping off there (F21). The phone is still needed for anything that depends on the IME,
  anything specific to the Fold, how a Bluetooth or USB keyboard feels, and a real Hyper chord:
  the emulator's keyboard has no Meta key, so the Hyper operations run there on Ctrl+Alt+Shift
  stand-ins, and matching the Meta bit rests on `MappingEngineTest` and the phone.
- **The log proves a call was made, not that it had an effect.** Several editor APIs return
  nothing. Confirm on screen, or by observing a later operation that depends on the first having
  worked — recipe 9 there.

## What the documents are for

| File | Holds |
|---|---|
| [README.md](./README.md) | For a first-time visitor: what the app is, its features and limitations, building from source. |
| [SECURITY.md](./SECURITY.md) | How to report a vulnerability. |
| [docs/usage.md](./docs/usage.md) | Requirements, installing, the key map, pass-through apps, known limitations. |
| [docs/architecture.md](./docs/architecture.md) | How a chord becomes an edit, and the reasons the code does not show. |
| [docs/development.md](./docs/development.md) | Building, the dedicated emulator and the scenarios, the project layout. |
| [docs/findings.md](./docs/findings.md) | What running it on a device revealed, F1–F21. |
| [docs/debugging.md](./docs/debugging.md) | How to observe anything on a device, and the traps. |
| [docs/security.md](./docs/security.md) | What the app reads, logs and keeps, and what a backup carries. |
| [docs/design.md](./docs/design.md) | The name, the mark, and the interface: palette, type, rows, keys. |

## Build

JDK 17 and the Android SDK. `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
is the gate; the unit tests cover the pure planning layer and must stay green. Lint runs with
`warningsAsErrors`, so a new warning fails the build rather than scrolling past.

CI runs that gate on every push, beside a check that `.github/workflows/actions.lock` matches the
workflows. What it cannot cover is anything read
through the accessibility service, which needs a key from a physical keyboard: the phone's, or an
emulator's built-in one (docs/findings.md F20).

Where `java` is not on `PATH`, run Gradle as `./gw` instead of `./gradlew`, with the same
arguments: it enters the Nix devShell in `flake.nix`, which provides JDK 17 and puts the SDK's
tools on `PATH`. The SDK itself stays outside Nix, at `ANDROID_HOME`.

**`tools/emu start` brings up a dedicated emulator with Caretto installed and enabled**, and
`tools/emu press ctrl+a` sends a chord to it as kernel input from its built-in keyboard, with
`deviceId=0`, which `KeyFilter.isPhysicalKeyboardEvent` admits, so Caretto remaps it. It never
touches another AVD or the phone: the emulator is on an adb server of its own, which plain `adb`
does not reach, so run adb on it as `tools/emu adb <args>`. docs/debugging.md, "The dedicated emulator", covers the
commands, the settings that let a second emulator run beside it, and reading the test editor's
state from logcat.

**`tools/e2e` runs the emulator scenarios**, and every change the agent merges waits for it to
pass, whatever the change touches (the merge rule, under "The owner's phone").

```bash
tools/e2e
```

It boots the emulator if needed, builds and installs the current debug build, runs every scenario
in every editor it applies to, and ends with one line on stdout, `e2e: PASS …` or `e2e: FAIL …`,
and an exit code to match. Each failure names the scenario, the editor, the step, the expected and
actual text and selection, and the line of `build/e2e.log` where the case starts.
`--scenario <id>` and `--editor <name>` narrow a run while iterating, and `--list` shows the ids.
It resets the emulator's bindings and settings before every case and back to the defaults at the
end, and never reaches the phone.
docs/debugging.md, "Running the scenarios", covers adding one.

**After changing `core/TextBoundary.kt`, run the instrumentation tests on the phone:**

```bash
tools/device-tests run <serial>     # the serial as adb devices lists it
```

They check segmentation against the device's own ICU, which is the whole reason they are not JVM
tests. They are deliberately kept out of CI — their expected values are pinned against one phone,
so a different emulator image would fail them without anything being wrong (on the dedicated
emulator's Android 36 image they have passed, which is no evidence for the phone). Do not run the
bare `connectedDebugAndroidTest`: it uninstalls the app when it finishes, which deletes the
bindings, the pass-through list and the settings, drops the accessibility grant and the tile's
permission, and can take the tile out of quick settings. `tools/device-tests` saves those first,
refuses to run when the preferences cannot be read, and puts everything back whether or not the
tests pass, except verbose logging, which comes back off. It acts only on the serial it is given, never on a device it picks. docs/debugging.md
recipe 1 has the details.

## The owner's phone

The phone (SM_F971Q) is the owner's own, in use every day. The dedicated emulator takes whatever a
chord sent to it can show; the phone is for what only it shows: the instrumentation tests above,
Samsung Keyboard and Gboard, Japanese IME composition, folding, and how something feels or looks.

- **Ask the owner before installing on the phone or driving it**: `adb install`, `input`, opening
  screens, `tools/device-tests` (which leaves this checkout's debug build installed), and any write
  to the phone's state — the preferences file, `settings put`, `pm grant`, `am force-stop`. A yes
  covers the rest of that piece of work; the next piece asks again. Reading changes nothing on the
  phone and needs no question, for example `dumpsys accessibility`,
  `dumpsys package dev.wadackel.caretto`, the preferences file through `run-as cat`, and Caretto's
  own log (`logcat -s Caretto:D`).
- **Turn verbose logging off as soon as the measurement is done.** It records every key press in
  every app, ordinary typing included (docs/debugging.md recipe 8).
- **The agent merges once CI and `tools/e2e` pass; the phone is tried after the merge.** Both
  have to pass for every change the agent merges, whatever it touches: the service, the UI, the
  tools, the tests or only the documents. The run that counts is a full one, without `--scenario`,
  `--editor` or `--no-build`, on the commit being merged. A change that has to be tried on the phone
  (IME-dependent behaviour, anything specific to the Fold, how something feels, how the UI looks)
  is merged the same way: it does not wait for the owner.
  The owner tries it by installing the merged `main` on the phone, so the PR body of such a change
  says what to try there; when unclear whether a change needs the phone, say what to try. The
  instrumentation tests a change to `core/TextBoundary.kt` needs are among these: name them in the
  PR body, and they run on the merged `main`, after the owner's yes. A problem found on the phone is
  fixed by a revert or a follow-up PR, not by holding the merge.
