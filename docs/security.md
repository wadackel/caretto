# Security design

What Caretto can see, where it keeps what it keeps, and what never leaves the device. This is a
design document; for reporting a vulnerability see [SECURITY.md](../SECURITY.md).

## Threat model

Caretto is an accessibility service that reads every key pressed on a physical keyboard, and it
reads the text around the cursor in the field being edited. Those are strong capabilities and the
reason to be sceptical of any app that has them, so the shape of the answer matters more than the
assurance. The concerns, in order:

1. **The app itself sending what it sees somewhere.** Answered by what the app cannot do at all,
   rather than by what it promises not to do: it has no network permission.
2. **A leak through diagnostics.** The log goes to `logcat`, which anyone with adb can read and
   which is shared more casually than what was typed. With verbose logging on, it holds every key
   pressed.
3. **A setting that outlives the moment it was meant for.** Verbose logging switched on for a
   measurement and then carried to a new phone by a backup.
4. **The one permission that reaches beyond the app.** `WRITE_SECURE_SETTINGS`, which the quick
   settings tile needs.
5. **Another app on the device driving Caretto.** Only debug builds expose anything that could.

Out of scope: someone who already has adb or root on the device. They can read `logcat` and the
app's files directly, and nothing in the app could stop them.

## What it reads

- **Key events from a physical keyboard** (`flagRequestFilterKeyEvents`). `KeyFilter` drops
  everything that does not come from a keyboard device before anything else looks at it, so keys
  injected by software are not examined. Keys that are not remapped return `false` at once and
  reach the IME and the app unchanged.
- **The text around the cursor**, only when a bound editing operation runs in an editor: 64
  characters either side, or 4096 for the operations that look for a line or word boundary
  (`EditorBridge`). It is read through `getSurroundingText()` to compute one edit, kept in memory
  for that edit only, and never stored or logged. In a pass-through app, or with no editor, it is
  not read at all.
- **The editor's package name, input type and cursor position**, from the Accessibility Input
  Method, to decide whether a key is remapped.
- **The package name of the app in the foreground**, from window-state and focus events. The
  service declares `canRetrieveWindowContent="false"`, so it cannot read what is on the screen.
- **The list of installed apps**, only to populate the app picker, and only launchable apps are
  visible to it (`<queries>` in the manifest, not `QUERY_ALL_PACKAGES`).

It does not read the clipboard. Copy, cut and paste are handed to the editor's own context menu,
and the only time Caretto writes to the clipboard is when you press Copy on its Log page.

## What it cannot do

- **It declares one permission, and it is not `INTERNET`.** An Android app without `INTERNET`
  cannot open a network connection, so nothing here can leave the device even by accident.
- **There is no networking code.** No HTTP client, no socket, no analytics or crash reporting
  SDK. The dependencies are Jetpack Compose, Material 3, `androidx.activity` and
  `androidx.lifecycle`.
- **It does not replace the IME** and does not grab the input device, so it never sees what the
  on-screen keyboard or the IME produce, only the physical keys
  ([architecture.md](./architecture.md#editing-operations-not-replacement-key-events)).

## The permission it declares

`WRITE_SECURE_SETTINGS` lets the quick settings tile switch the service on and off, for apps that
refuse to run while it is on ([usage.md](./usage.md#switching-it-from-quick-settings)). Android
grants it only from adb (`pm grant`), never from a dialog, and it is the most powerful thing here:
it can change any secure setting, so it could switch any accessibility service or the default IME,
not only Caretto.

What Caretto writes with it is two settings: `enabled_accessibility_services`, to which it adds or
from which it removes its own entry and leaves every other entry as it was, and
`accessibility_enabled`, which follows whether anything is left in that list, the way the Settings
app writes it (`ServiceStatus.kt`). Leave the permission ungranted and the tile is simply
unavailable; everything else works.

## Where things are kept

Nothing is written outside the app's own storage, apart from the two settings above.

| What | Where |
|---|---|
| Bindings, remapping on or off, the KeyEvent fallback, the pass-through and select-then-delete app lists | `shared_prefs/caretto.xml` |
| Whether verbose logging is on | `shared_prefs/caretto_local.xml` |
| The log | A ring buffer of the last 300 lines in memory, gone when the process ends, and `logcat` under the tag `Caretto` |

`logcat` is a developer channel on the device: adb and the system can read it, ordinary apps
cannot.

## What is logged

Without verbose logging, the log holds the service connecting and disconnecting, and for each chord
that has a binding: the chord, the operation, whether it ran or why it passed through, the API
call with its character counts and positions, and the package of the editor it was meant for. It
never holds the text of a field.

**Turning on Verbose logs puts every physical key press and release into `logcat`**, with
its key code, so ordinary typing in every app can be read back from it letter by letter. It also
adds each change of the foreground app, each editor session starting and finishing, and each
cursor movement. It is meant to be on for a measurement and off again straight after.

## Backup and device transfer

`android:allowBackup` is on, and `data_extraction_rules.xml` includes `caretto.xml` alone, for
both cloud backup and device-to-device transfer. So a new phone gets the bindings, the remapping
and fallback switches and both app lists, and nothing has to be set up again. That file holds no
secret; what it does reveal is the package names of the apps you put in the lists.

`caretto_local.xml` is in neither, so **verbose logging always starts off on a new device**, or
after a restore, however it was set when the backup was taken. Otherwise it could stay on there
without its owner remembering it was ever switched on. The rules apply from Android 12, and the app
needs Android 13, so there is no older backup path that would take the whole data directory.

## Debug builds

Debug builds carry three things for testing that release builds do not:

- **The test editor reports its text to `logcat`.** Whatever is typed into Caretto's own test
  editor, and each key that reaches it, is written under the tag `TestEditor`, for the emulator
  scenarios to read. Release builds get an empty stand-in from their own source set.
- **The test editor is exported**, so `adb shell am start` can open it.
- **`ScenarioReceiver`** resets the bindings and settings, verbose logging included, for each
  emulator scenario. It requires the `DUMP` permission, which only adb can grant, and it does
  nothing unless the device is an emulator (`Build.HARDWARE` is `ranchu` or `goldfish`), so an app
  granted `DUMP` on a phone running a debug build gets nothing from it.

How the scenarios use them is in [debugging.md](./debugging.md#the-dedicated-emulator).
