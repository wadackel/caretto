# Debugging on a device

How to get evidence out of a phone running Caretto, and the traps that have already cost time.

The checklist this used to accompany has been retired: the items it tracked are either verified —
the evidence is in [findings.md](./findings.md) — or filled in during everyday use. What survives
here is the part that does not expire, which is how to observe anything in the first place.

## Setup

### Connecting the device

**Wireless debugging is the default.** Some items involve folding and unfolding the phone or using split
screen, and a cable gets in the way. USB was unstable and dropped repeatedly during verification.

Connect over Tailscale. As long as the phone and the Mac are on the same tailnet, the same address reaches it
at home or away.

```bash
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
adb connect <device Tailscale address>:5555
adb devices -l
```

Find the phone's Tailscale address in the Tailscale app or with
`adb shell ip -4 addr show tailscale0`.

#### When the connection stops working

The listener on port 5555 is the system property `service.adb.tcp.port`, and **it disappears when the phone
reboots**. Plug in USB once to set it up again.

```bash
adb -s <USB serial> tcpip 5555     # restart the listener
# unplug the cable
adb connect <device Tailscale address>:5555
```

Check the current state with this. An empty result means the listener is down.

```bash
adb shell getprop service.adb.tcp.port
```

#### When more than one device is connected

With an emulator running, `adb` cannot decide where to send, and commands without `-s` fail with
`more than one device`. The device's serial changes with how it is connected — the phone's own serial over
USB, `<Tailscale address>:5555` over wireless — so looking it up by model name is reliable.
This repository's own emulator is not among them: it is on an adb server of its own (see "The
dedicated emulator").

```bash
S=$(adb devices -l | grep SM_F971Q | awk '{print $1}' | head -1)
adb -s "$S" shell ...
```

#### mDNS does not work

Pair device with pairing code, on Android 11 and later, finds the other end over mDNS, but Tailscale crosses
broadcast domains, so `adb mdns services` shows nothing. Use the `adb connect` above directly.

### Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enabling the service

Launch Caretto → `Open settings` on the status row → turn Caretto on.
When the status row in the app reads `Remapping`, you are ready.

It can also be enabled from adb, if you would rather not go through the One UI settings screens:

```bash
adb shell settings put secure enabled_accessibility_services \
  dev.wadackel.caretto/dev.wadackel.caretto.service.CarettoService
adb shell settings put secure accessibility_enabled 1
```

### Watching the log

```bash
adb logcat -c && adb logcat -s Caretto:D
```

The in-app log (gear → `Log`) shows the same thing. Turn on `Verbose logs` and it also reports
editors starting and finishing, cursor updates, and the reason behind each pass-through decision.


## The dedicated emulator

An emulator of this repository's own, driven from the command line with nothing but the Android
SDK and Nix installed. `tools/emu` enters the Nix devShell (`flake.nix`, which brings JDK 17,
`bun` and `grpcurl` and puts the SDK's tools on `PATH`) and runs `tools/emu.ts`.

```bash
tools/emu start          # create the AVD if missing, boot headless, build, install, enable
tools/emu editor         # open the test editor (debug builds export it for this)
tools/emu press ctrl+a   # press and release a chord; several chords go in turn
tools/emu type hello     # plain text, no modifiers, printable ASCII only
tools/emu install        # after changing the app: rebuild, reinstall, enable again
tools/emu adb shell ...  # any adb command, on this emulator through its own adb server
tools/emu stop           # also stops that adb server
```

`start` ends with one line, `caretto_e2e ready on emulator-5600 (gRPC 8600): Caretto installed and
enabled`, once the service is enabled and filtering keys (recipe 2's `KeyboardInterceptor`).
The emulator's own output goes to `build/emulator-<avd>.log`. It boots cold every time, because no
snapshot is saved, but its data partition survives a restart: the app, its preferences and the
grant are still there next time.

**It touches only its own AVD.** The name must start with `caretto_`, so a typo cannot reach
`limenty_fold` or another project's AVD, and before installing or sending anything the tool asks
the emulator on its port which AVD it is running.

**It has an adb server of its own.** The shared server on 5037, which the phone and other
projects' emulators are on, is restarted now and then by other tools on the owner's Mac, and a
restart cuts whatever runs through it. So the emulator registers with a server on its console port + 10000 (15600 by default) and
every `adb` call of the tools goes there, through one function in `tools/emu.ts` that `run` refuses
to bypass. That server is started with no scan of ports 5555–5585 (`ADB_LOCAL_TRANSPORT_MAX_PORT`
below 5555), no mDNS and no USB, so `tools/emu adb devices` lists this emulator and nothing else,
and the console port is outside 5555–5585, so the shared server does not find the emulator either.
When its own server restarts, the emulator registers with it again within a second or so. Run your
own commands the same way: `tools/emu adb <args>` is `adb -s <serial> <args>` on that server, with
its output and exit status. Plain `adb` does not see this emulator, and neither does
`tools/device-tests`, which stays on the shared server for the phone.

An emulator that an older checkout started on 5570 is on the shared server, and the tool no longer
accepts that port, so stop it once by hand before the first `tools/emu start`:
`adb -s emulator-5570 emu kill`. Otherwise the new start tries to boot the same AVD a second time.

### Settings

Everything has a default that the environment overrides, so a second emulator can run beside the
first, and a Linux x86_64 machine picks the matching system image.

| Variable | Default |
|---|---|
| `CARETTO_AVD` | `caretto_e2e` |
| `CARETTO_EMU_PORT` | `5600` (console port, even, 5586–5682; the serial is `emulator-<port>`, its adb server is on the port + 10000) |
| `CARETTO_GRPC_PORT` | console port + 3000 |
| `CARETTO_SYSTEM_IMAGE` | `system-images;android-36;google_apis;<host abi>`, installed with `sdkmanager` when missing |
| `CARETTO_AVD_DEVICE` | `pixel_7` |
| `CARETTO_BOOT_TIMEOUT` | `600` seconds |
| `ANDROID_HOME` | `~/Library/Android/sdk` on macOS, `~/Android/Sdk` elsewhere |

```bash
CARETTO_AVD=caretto_other CARETTO_EMU_PORT=5620 tools/emu start
```

The AVD is created with `hw.keyboard=yes`, and `start` writes that again into an existing one.

### How a chord gets in

`press`, `keydown` and `keyup` go through the emulator's gRPC `sendKey` with Evdev codes, one call
per key transition: modifiers down, the key down and up, modifiers up. The guest sees them as
kernel input from its built-in keyboard, `deviceId=0` with `source=KEYBOARD` and the real meta
state, not as injected events. `KeyFilter.isPhysicalKeyboardEvent` admits `deviceId >= 0`, so
Caretto remaps these keys as it would a physical keyboard's ([findings.md](./findings.md) F20).

Two lookalikes do not work. `adb emu event send` returns `OK` and produces no kernel event at all.
`adb shell input keyevent` is injected and is rewritten to `deviceId=-1` (recipe 3). `type` uses
`adb emu event text`, which does reach the kernel but cannot hold a modifier, so it is for typing
text only; it silently drops a text of 16 characters or more, so `type` sends it in pieces.

**There is no Meta key.** The built-in keyboard's layout maps no key to evdev 125 or 126, so
`meta+…` and `hyper+…` arrive with an `UNKNOWN` key in place of Meta, and a Hyper chord cannot be
pressed on the emulator at all. Some other keys are not what a PC keyboard sends either: Esc is
`BACK`, End is `ENDCALL`, Home goes home ([findings.md](./findings.md) F21).

The gRPC endpoint is started with `-grpc-use-token`. Started with a bare `-grpc <port>`, the
emulator listened on every interface with no authentication, which lets anyone on the network type
into it. With the flag it listens on `127.0.0.1` only and answers a call without its token with
`Unauthenticated`. The emulator generates that token at every boot and writes it, with the gRPC port
it was given, to its discovery file, which `adb emu avd discoverypath` names. The tool reads both
from there before each chord and sends to `127.0.0.1` only, and only when the process listening
there is the emulator the discovery file belongs to (checked with `lsof`). That matters because an
emulator that finds its gRPC port taken still boots and listens on `::1` instead; `start` also
refuses to boot when the port is already taken. The token goes to `grpcurl` through the environment
rather than the command line.

Focus an editor by tapping it. Injected touches are fine, since only keys are checked for coming
from a device; on the default `pixel_7` profile, with the test editor scrolled to the top,
`tools/emu adb shell input tap 540 700` focuses the multi-line EditText. Straight after
`tools/emu editor` the first tap has been seen not to take, so type something and look for that
editor's line (below) before relying on the focus.

### Reading what the editor did

In debug builds each of the test editor's four editors writes a logcat line tagged `TestEditor` when
it appears, with its starting state, and another for every change to its text or selection after
that. Each line is JSON, because the text can contain newlines:

```
D/TestEditor( 5603): {"editor":"edittext-multiline","text":"xab","selection":[3,3]}
```

The editors are `edittext-multiline`, `edittext-singleline`, `compose-state` and `compose-value`.
The selection is the pair each toolkit reports, in UTF-16 units, and is not sorted: Shift+Left from
the end of `xab` reads `[3,2]`. The two Compose editors report once per frame, so changes that land
in the same frame, such as the characters of one `type`, arrive as one line. Read it beside
Caretto's own lines:

```bash
tools/emu adb logcat -v brief Caretto:D TestEditor:D "*:S"
```

Each key press that reaches an editor gets a line of its own, written in `tools/emu`'s chord
syntax, such as `{"editor":"compose-state","key":"ctrl+h"}`. A chord Caretto remaps never gets
one, because the service consumes it before any window sees it. That is how a scenario tells
Caretto's edit from the editor's own handling of the same chord.

This is the editor's account, not Caretto's, and it needs no `uiautomator dump` (recipe 5). Release
builds do not contain it: the probe is an empty object in the release source set.

### Running the scenarios

`tools/e2e` runs the scenarios in `tools/scenarios.ts` against this emulator.
[AGENTS.md](../AGENTS.md) says when to run it.

```bash
tools/e2e                                   # every scenario in every editor it applies to
tools/e2e --scenario ctrl-k --editor compose-state
tools/e2e --list                            # the scenario ids
tools/e2e --no-build                        # reuse the APK already built
```

A failure reads like this, and the log line it points to starts that case in `build/e2e.log`,
which holds the runner's step markers between Caretto's verbose lines and the editors' lines:

```
FAIL ctrl-p [edittext-multiline] step 1 (press ctrl+p) — Ctrl+P moves up a line
      expected text "abc\ndef" selection [2,2]
      actual   text "abc\ndef" selection [6,6]
      log: build/e2e.log from line 186
```

Before each case the runner sends a broadcast to `ScenarioReceiver`, which exists in debug builds
only. It restores the default bindings, turns remapping and the KeyEvent fallback on, takes the
test editor out of the pass-through list, turns verbose logging on and clears the clipboard. It
then sets the case's editor to its starting text and selection and focuses it. At the end of a
run it is sent once more, leaving the defaults with verbose logging off. Because it can
rewrite the bindings and turn verbose key logging on, only holders of `DUMP` (the shell, or an
app someone granted it from adb) can send to it, and it does nothing outside an emulator: on a
phone it answers `error: not an emulator`. By hand:
`tools/emu adb shell am broadcast -n dev.wadackel.caretto/.ui.testeditor.ScenarioReceiver --es setup <base64 JSON>`;
the class comment lists the fields.

A step passes when the editor reports the expected state and keeps it for a moment. A step that
should change nothing waits a little over a second instead. For each `press` or `hold` the
runner also checks the chord's key line: absent, because Caretto took it, unless the step says the
chord reaches the editor. The capture step opens the key map, finds the add button and then the
operation in the picker with `uiautomator dump`, and waits for the service to reconnect before
sending the next chord. The dumps only find controls, never a result, and they are what lets the
step press the real capture sheet rather than one opened by a debug-only route. It and the cover
step are the only checks that read Caretto's own log: no editor is focused behind the sheet, so the
only trace of a captured chord that also ran is Caretto's line for the operation. The cover step
opens the sheet the same way, covers it with Recents or the notification shade, and fails unless
Caretto logs `capture mode off` then and not before, and then logs the chord pressed over the cover
as a pass-through.

A scenario is a start state, written `ab|c` for a caret or `[abc]` for a selection, and a list of
steps with what to expect after each. It runs in all four editors unless it names some, and a step
can expect something else in one editor (`expectIn`). Compose's own Ctrl+H deleting is the case
that needs it so far. The `hyper-standin-*` scenarios bind the Hyper operations a second time on
Ctrl+Alt+Shift and the same key, because Meta cannot be sent. Their pass checks the operations,
not the Hyper chord, and the summary line counts them separately for that reason.

A restart of the shared adb server no longer reaches the emulator, but its own server can still go
down (a `killall adb`) or drop the emulator for a moment. The runner follows logcat again when its stream ends, and a case that failed on adb itself rather than on a
step runs once more; both leave a `~~~~~` line in the log. When logcat has still not come back
after 30 seconds, twice, the run stops there and the summary counts the rest as not run.

When adding one, break the behaviour it covers and watch it fail for that reason: unbind the
chord, turn remapping off, or remove the line it depends on.


## Getting evidence off the device

This section is for a reader with no memory of the last session — an agent picking the work up, or
the author some months from now. Each entry starts from what you will **observe**, because that is
how you will arrive here: mid-task, looking at something unexpected. Commands are complete; copy
them rather than paraphrasing.

### 1. force-stop clears the accessibility grant

`adb shell am force-stop dev.wadackel.caretto` resets `enabled_accessibility_services` to `null`.
The service is then dead and every chord passes through, which looks exactly like a remapping bug.
Several recipes below force-stop the app, so this is the one to remember.

```bash
adb shell settings put secure enabled_accessibility_services \
  dev.wadackel.caretto/dev.wadackel.caretto.service.CarettoService
adb shell settings put secure accessibility_enabled 1
```

`./gradlew :app:connectedDebugAndroidTest` does worse, for a different reason: it uninstalls the
app when it finishes. That deletes the app's data — the bindings, the pass-through list and every
setting — and drops the grant, the permission behind the quick settings tile (which force-stop
leaves alone), and on the emulator it has also taken the tile out of quick settings. So do not run
it bare. Run the instrumentation tests through this instead, which needs a debug build already
installed on the device:

```bash
tools/device-tests run <serial>                       # the serial as adb devices lists it
tools/device-tests run <serial> -Pandroid.testInstrumentationRunnerArguments.class=dev.wadackel.caretto.core.TextBoundaryDeviceTest
```

It builds the debug and test APKs, then saves `shared_prefs/caretto.xml` (read with `run-as`, its
checksum compared with the device's; verbose logging is kept apart in `caretto_local.xml`, which
is not saved, so it comes back off), whether the service is enabled, whether the tile's permission
is granted, and the quick settings tile list, to
`~/.local/state/caretto/device-tests/<serial>-<time>/`. **It refuses to run when the preferences
cannot be read** — the app missing, a release build (no `run-as`), or no preferences written yet
(launch Caretto once) — so nothing is uninstalled that cannot be put back. After Gradle, whether
the tests passed or failed, it installs this checkout's debug build (which then stays on the
device, in place of whatever build was there), writes the preferences back, grants the permission,
enables the service beside the other services the device has enabled, and puts the tile back where
it was — each only if it was so before — and checks each of those. The last line says whether the
tests passed and whether everything was restored; the exit status is 0 only when both hold.
Arguments after the serial go to Gradle. Ctrl+C does not stop the tests: Gradle runs in its own
process group and finishes, because a cancelled run uninstalls on its way out and could do so
after the restore.

It never picks a device: the serial is required, and `ANDROID_SERIAL` is set from it for Gradle,
so with the phone and emulators attached at once only the named one is touched. It reaches only
devices on the shared adb server, so not `tools/emu`'s emulator, which is on a server of its own;
an emulator for it has to be booted some other way. If the process is
killed before it restores, the backup is still there, and `restore` puts it back. Gradle runs on
after such a kill and uninstalls when the tests end, so run `restore` once Gradle has finished; it
refuses while the test package is still installed (when no Gradle run is left and the package
stayed, `adb -s <serial> uninstall dev.wadackel.caretto.test`). It installs this checkout's existing debug build and does not build one; run
`./gw :app:assembleDebug` first after a clean. Preferences on the device that differ from the
backup are saved to a new backup before being overwritten. A Ctrl+C during a restore can cut it
short; running the same `restore` again finishes it, and `run` prints that command whenever its
restore is incomplete.

The tile is put back through `sysui_qs_tiles` and `cmd statusbar set-tiles`, which is how AOSP
keeps the tile list and what the emulator was checked with. On the phone's One UI this has not
been tried; when the command reports the tile restored, look at quick settings once.

```bash
tools/device-tests restore <serial> ~/.local/state/caretto/device-tests/<serial>-<time>
```

**Run it after changing `core/TextBoundary.kt`.** Nothing else will: the instrumentation tests are
kept out of CI, because their expected values are pinned against this phone's ICU and a different
emulator image would fail them without anything being wrong. That makes this the only thing
standing behind them.

Uninstalling by hand drops the tile's permission too. Grant it again:

```bash
adb shell pm grant dev.wadackel.caretto android.permission.WRITE_SECURE_SETTINGS
```

### 2. Proving whether Caretto is in the input path at all

Every negative result in findings.md ("this reproduces with Caretto stopped") rests on being able to
show the service is not filtering keys. Two markers say it is:

```bash
adb shell dumpsys accessibility | grep -o 'requestFilterKeyEvents=[a-z]*'
adb shell dumpsys accessibility | grep 'Enabled features of Display'
```

`requestFilterKeyEvents=true` means the service asked for keys. `[KeyboardInterceptor]` in the
enabled features means the framework is actually routing them through a filter. **When the service
is off, `KeyboardInterceptor` disappears from that line** — that absence is the evidence, not the
app's own log going quiet, which would also happen if it had merely crashed.

### 3. `adb shell input` cannot exercise the remap path

Do not reach for `adb shell input keyevent` to test a chord. Injected events never reach the
service ([findings.md](./findings.md) F18), and the framework rewrites their `deviceId` to `-1` —
even when the event is built with a real attached keyboard's id — which the service's first guard
would reject anyway. On the phone `sendevent` is blocked by SELinux, so a physical keyboard is the
only way in there. This is measured, not assumed: see [findings.md](./findings.md) F16.

The trap is that a test built on injection **passes for the wrong reason**. An injected key never
reaches the service, so "capture mode did not swallow it" proves nothing at all.

`onKeyEvent` does not see injected events at all — not rejected, absent ([findings.md](./findings.md)
F18). So this covers the capture sheet too, which reads chords through the service. Injection
reaches the app's own window, which is why testing capture that way used to appear to work.
Injection is still fine for anything that reads keys in the app's own window rather than through
the service.

#### On an emulator, the built-in keyboard does reach it

An emulator's built-in keyboard has `deviceId` 0, which the guard accepts, and a chord sent
through the emulator's gRPC `sendKey` arrives from it as a real key, modifiers included
([findings.md](./findings.md) F20). `tools/emu press` sends chords that way; "The dedicated
emulator" above covers the commands and how the gRPC endpoint is protected.

### 4. Where log lines come from, and what an observation needs with it

`EventLog.add` mirrors to logcat unconditionally, so the in-app Log view and this show the same
lines:

```bash
adb logcat -c && adb logcat -s Caretto:D
```

**Record which IME was active alongside any result.** F1 exists because the same keystrokes give
different output under Gboard and Samsung Keyboard; an observation that does not name the IME
cannot be compared with anything.

### 5. `service disconnected` / `service connected` right after a UI dump

`uiautomator dump` registers itself as an accessibility client, and the framework rebinds the
others when it does. Every dump therefore produces a disconnect/connect pair in the log a moment
later.

**Do not report this as a defect, and do not dump while timing key behaviour** — the service is
briefly not there. It also means dumps are not free, which is why reading state out of the
preferences file (recipe 7) is usually the better route.

### 6. The preferences file is stale immediately after a UI action

Every write goes through `SharedPreferences.apply()`, which updates memory at once and the file on
a background thread. Reading the file straight after tapping something shows the **previous**
contents.

This has produced a wrong conclusion twice: a feature looked broken when it had worked. Re-read
before concluding anything, and treat a single disagreeing read as a timing artefact rather than a
result.

### 7. Reading the bindings out of the preferences file

```bash
adb shell run-as dev.wadackel.caretto \
  cat /data/data/dev.wadackel.caretto/shared_prefs/caretto.xml
```

Each binding is stored as `keycode:modifiers=action`, where the modifier bits are
Ctrl 1, Alt 2, Shift 4, Meta 8. So `36:1=DeleteBackward` is Ctrl+H, and `36:15=Home` is Hyper+H,
15 being all four. `BindingCodec.kt` and `KeyChord.kt` are the source of truth for the format.

This is how nearly every binding change gets verified. It is faster than the UI and, unlike a
screenshot, it says exactly what was stored.

### 8. Turning verbose logging on and off without the UI

The switch is a preference of its own, in `shared_prefs/caretto_local.xml`, kept apart from the
rest so that no backup carries it ([security.md](./security.md#backup-and-device-transfer)). That
makes it one file to write or delete, without driving the screen. Force-stop the app first, or the
running process will write its own copy back over yours.

```bash
adb shell am force-stop dev.wadackel.caretto
# on
adb shell "run-as dev.wadackel.caretto sh -c 'mkdir -p shared_prefs && echo \"<map><boolean name=\\\"verbose_log\\\" value=\\\"true\\\" /></map>\" > shared_prefs/caretto_local.xml'"
# off: with the file gone, the setting is back at its default
adb shell run-as dev.wadackel.caretto rm -f shared_prefs/caretto_local.xml
# then re-grant, per recipe 1
```

A `verbose_log` entry in `caretto.xml` is left over from builds that kept the switch there, and
nothing reads it.

**Verbose logging records every physical key press and release, including ordinary typing in other
apps.** Without it only chords that have a binding are logged. On a phone someone actually uses,
turn it off the moment the measurement is done.

### 9. Proving a call that returns nothing actually did something

`performContextMenuAction` reports no result, so "copy" and "did nothing" produce identical logs.
The way through is to observe a **later** operation that depends on the first having worked.

Select-all was confirmed this way: four seconds after `Hyper+A`, a `Ctrl+H` reported
`setSelection(0, 8) + commitText('') delete selection`. That range came from the editor through
`getSurroundingText`, so the editor really did have eight characters selected. The select-all
happened — not merely got called.

Reach for this whenever an API is fire-and-forget. It is the only kind of proof available there,
and it is stronger than a screenshot because it comes from the editor's own state.


# What to verify

Ordered by risk. Working from the top down is the efficient way through.

Follow the log for each item with this.

```bash
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
S=$(adb devices -l | grep SM_F971Q | awk '{print $1}' | head -1)
adb -s "$S" logcat -v brief Caretto:D "*:S"
```

**Injected keys cannot check remapping.** Synthetic keys from
`adb shell input keyevent` never reach the service, and writing to the phone's real device with `sendevent`
is denied by SELinux ([findings.md](./findings.md) F16, F18). An emulator's built-in keyboard does count:
chords sent through its gRPC `sendKey` are remapped, so what Caretto does with a chord can be checked there
("The dedicated emulator", F20). What still has to be pressed by hand on the phone: anything that depends on the IME (Gboard,
Samsung Keyboard, Japanese composition), anything specific to the Fold (folding, split screen, DeX), how
it feels with a Bluetooth or USB keyboard, and a real Hyper chord, since the emulator's keyboard has no Meta
key. Key repeat and the capture sheet are covered by `tools/e2e` ("Running the scenarios", F21).

**The log only proves that a call was sent.** Whether the text actually changed has to be seen on screen, or
read out of a plain `EditText` with this.

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | tr '<' '\n' | grep -oE 'text="[^"]*"'
```
