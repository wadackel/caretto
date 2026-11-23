/**
 * Runs the instrumentation tests on a named device without costing it Caretto's setup.
 * `connectedDebugAndroidTest` uninstalls the app when it finishes, which deletes its preferences
 * and drops the accessibility grant and the tile's WRITE_SECURE_SETTINGS grant; SystemUI then
 * takes the tile out of quick settings. This saves the preferences and how the service, the grant
 * and the tile stood first, refuses to run when that fails, and afterwards reinstalls the debug
 * build and puts all of it back as it was, whether or not the tests passed.
 * `tools/device-tests` is the entry point; it enters the devShell and runs this file.
 */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import {
  type Device,
  type RunResult,
  PACKAGE,
  REPO_ROOT,
  SERVICE,
  adb,
  adbDevices,
  adbRun,
  enableService,
  expandEntry,
  grantTilePermission,
  must,
  serviceEntries,
} from "./emu.ts";

const PREFS = "shared_prefs/caretto.xml";
const TEST_PACKAGE = `${PACKAGE}.test`;
const GRANTED = "android.permission.WRITE_SECURE_SETTINGS: granted=true";
/** Caretto's entry in `sysui_qs_tiles`, `custom(<component>)` with the component in either form. */
const isCarettoTile = (entry: string) => entry.startsWith(`custom(${PACKAGE}/`);
const APK = join(REPO_ROOT, "app/build/outputs/apk/debug/app-debug.apk");

/** What the run takes away, as it was before. Written to the backup directory as JSON. */
export type Saved = {
  serial: string;
  /** Which device this is, from {@link deviceIdentity}. */
  identity: string;
  prefs: string;
  prefsSha256: string;
  /** The other services are restored as the device has them then; this keeps the form they were in. */
  enabledServices: string;
  serviceEnabled: boolean;
  tilePermission: boolean;
  quickSettingsTiles: string;
};

function log(message: string): void {
  console.error(`device-tests: ${message}`);
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const sha256 = (text: string) => createHash("sha256").update(text).digest("hex");

/** `adb shell` keeps the remote exit status, which `exec-out` loses; no tty, so no CRLF either. */
async function shell(device: Device, ...args: string[]): Promise<RunResult> {
  return adbRun(device, ["shell", ...args]);
}

async function deviceSha256(device: Device): Promise<string> {
  const result = await shell(device, "run-as", PACKAGE, "sha256sum", PREFS);
  if (result.code !== 0) throw new Error(`run-as sha256sum failed: ${(result.stderr || result.stdout).trim()}`);
  return result.stdout.trim().split(/\s+/)[0] ?? "";
}

async function secureSetting(device: Device, name: string): Promise<string> {
  return (await adb(device, "shell", "settings", "get", "secure", name)).trim();
}

/**
 * `ro.serialno`, which stays when a wireless connection comes back on another port, and on an
 * emulator its AVD name too, since every emulator of one build reports the same serialno.
 */
async function deviceIdentity(device: Device): Promise<string> {
  const serialno = (await adb(device, "shell", "getprop", "ro.serialno")).trim();
  const avd = (await adb(device, "shell", "getprop", "ro.boot.qemu.avd_name")).trim();
  return avd === "" ? serialno : `${serialno}/${avd}`;
}

async function serviceEnabled(device: Device): Promise<boolean> {
  return serviceEntries(await secureSetting(device, "enabled_accessibility_services"))
    .map(expandEntry)
    .includes(SERVICE);
}

async function tilePermission(device: Device): Promise<boolean> {
  return (await adb(device, "shell", "dumpsys", "package", PACKAGE)).includes(GRANTED);
}

async function installed(device: Device, pkg: string): Promise<boolean> {
  return (await adb(device, "shell", "pm", "list", "packages", pkg)).split(/\r?\n/).includes(`package:${pkg}`);
}

/**
 * Only a device the caller named is acted on. With a phone and emulators attached at once, adb's
 * own choice, or ANDROID_SERIAL left over in a shell, would put the uninstall somewhere else.
 */
async function requireAttached(device: Device): Promise<void> {
  const state = (await adbDevices(device)).get(device.serial);
  if (state !== "device") {
    throw new Error(`${device.serial} is ${state ? `in state ${state}` : "not attached"}; see adb devices`);
  }
}

/**
 * Reads the preferences and how the service, the tile's grant and the tile stand; throws when the
 * preferences cannot be read whole.
 */
export async function save(device: Device): Promise<Saved> {
  const read = await shell(device, "run-as", PACKAGE, "cat", PREFS);
  if (read.code !== 0) {
    // run-as says why: the app is missing, not a debug build, or has not written preferences yet.
    throw new Error(`cannot read ${PREFS}: ${(read.stderr || read.stdout).trim()}`);
  }
  const prefs = read.stdout;
  if (!prefs.startsWith("<?xml") || !/<map\s*\/>|<\/map>\s*$/.test(prefs)) {
    throw new Error(`${PREFS} does not read as a whole preferences file:\n${prefs.slice(0, 200)}`);
  }
  const prefsSha256 = sha256(prefs);
  // A copy that decoded differently from the bytes on the device would restore something else.
  if ((await deviceSha256(device)) !== prefsSha256) throw new Error(`${PREFS} read back with another checksum`);

  return {
    serial: device.serial,
    identity: await deviceIdentity(device),
    prefs,
    prefsSha256,
    enabledServices: await secureSetting(device, "enabled_accessibility_services"),
    serviceEnabled: await serviceEnabled(device),
    tilePermission: await tilePermission(device),
    quickSettingsTiles: await secureSetting(device, "sysui_qs_tiles"),
  };
}

/** Outside the repository, so a clean build or a removed worktree does not take the only copy. */
function backupDir(serial: string): string {
  const state = process.env.XDG_STATE_HOME || join(homedir(), ".local", "state");
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  return join(state, "caretto", "device-tests", `${serial.replace(/[^\w.-]/g, "_")}-${stamp}`);
}

function writeBackup(saved: Saved): string {
  const dir = backupDir(saved.serial);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "caretto.xml"), saved.prefs);
  writeFileSync(join(dir, "saved.json"), `${JSON.stringify(saved, null, 2)}\n`);
  return dir;
}

export function readBackup(dir: string): Saved {
  const saved = JSON.parse(readFileSync(join(dir, "saved.json"), "utf8")) as Saved;
  if (sha256(saved.prefs) !== saved.prefsSha256) throw new Error(`${dir}/saved.json does not match its checksum`);
  return saved;
}

/**
 * The current tile list with Caretto's tile put back where it was saved, or undefined when it needs
 * no change. Only Caretto's entry is touched, so a tile moved in the meantime stays where it went.
 */
export function tilesToRestore(saved: string, current: string): string | undefined {
  const before = saved.split(",");
  const index = before.findIndex(isCarettoTile);
  const now = current === "null" || current.trim() === "" ? [] : current.split(",");
  if (index === -1 || now.some(isCarettoTile)) return undefined;
  now.splice(Math.min(index, now.length), 0, before[index]!);
  return now.join(",");
}

/**
 * Puts the tile back and waits until two reads a second apart both show it, since SystemUI applies
 * `set-tiles`, and drops an uninstalled package's tile, on its own schedule. Returns a problem or
 * undefined.
 */
async function restoreTile(device: Device, saved: Saved): Promise<string | undefined> {
  const deadline = Date.now() + 15_000;
  let seen = 0;
  while (seen < 2) {
    if (Date.now() > deadline) return "the Caretto tile is not in quick settings; add it in the editor";
    const tiles = tilesToRestore(saved.quickSettingsTiles, await secureSetting(device, "sysui_qs_tiles"));
    if (tiles === undefined) {
      seen += 1;
    } else {
      seen = 0;
      log("putting the Caretto tile back in quick settings");
      // The entries are `custom(...)`, which the device's shell would read as a subshell.
      if (tiles.includes("'")) return `cannot write ${tiles} as the tile list`;
      // SystemUI writes its own list back over a `settings put` of sysui_qs_tiles within a second.
      const result = await shell(device, "cmd", "statusbar", "set-tiles", `'${tiles}'`);
      if (result.code !== 0) {
        return `cmd statusbar set-tiles failed (${(result.stderr || result.stdout).trim()}); add the tile by hand`;
      }
    }
    await sleep(1000);
  }
  return undefined;
}

/**
 * Installs this checkout's debug build over whatever is there, writes the saved preferences back,
 * and puts the service, the tile's grant and the tile back as they were saved. The other enabled
 * services are left as the device has them now. Returns what does not match the saved state.
 */
export async function restore(device: Device, saved: Saved): Promise<string[]> {
  if (!existsSync(APK)) throw new Error(`no debug build at ${APK}; build it with ./gw :app:assembleDebug`);
  log(`installing ${APK}`);
  await adb(device, "install", "-r", APK);
  // The framework shortens entries when it rewrites the list; an entry that was saved is written
  // back in the form it was saved in.
  const savedForm = new Map(serviceEntries(saved.enabledServices).map((entry) => [expandEntry(entry), entry]));
  const others = serviceEntries(await secureSetting(device, "enabled_accessibility_services"))
    .filter((entry) => expandEntry(entry) !== SERVICE)
    .map((entry) => savedForm.get(expandEntry(entry)) ?? entry);
  // When the tests stopped before the uninstall, the app is still running and would write its own
  // preferences back over the restored file; force-stop ends it (and drops the grant, rewritten below).
  await adb(device, "shell", "am", "force-stop", PACKAGE);
  log(`writing ${PREFS} back`);
  // A leftover .bak is what SharedPreferences loads in place of the file, so it goes too.
  const write = `run-as ${PACKAGE} sh -c 'mkdir -p shared_prefs && rm -f ${PREFS}.bak && cat > ${PREFS}'`;
  const written = await adbRun(device, ["shell", write], { stdin: saved.prefs });
  if (written.code !== 0) throw new Error(`writing ${PREFS} failed: ${(written.stderr || written.stdout).trim()}`);
  if ((await deviceSha256(device)) !== saved.prefsSha256) throw new Error(`${PREFS} was written back wrong`);

  if (saved.tilePermission) {
    log("granting WRITE_SECURE_SETTINGS");
    await grantTilePermission(device);
  }
  const expected = saved.serviceEnabled ? [...others, savedForm.get(SERVICE) ?? SERVICE] : others;
  if (saved.serviceEnabled) {
    log("enabling the service");
    await enableService(device, expected);
  }

  const mismatches: string[] = [];
  const tile = await restoreTile(device, saved);
  if (tile !== undefined) mismatches.push(tile);
  // Checked last, after the service has started, so a write it made at startup would show.
  if (!(await installed(device, PACKAGE))) mismatches.push(`${PACKAGE} is not installed`);
  else if ((await deviceSha256(device)) !== saved.prefsSha256) mismatches.push(`${PREFS} changed after restoring`);
  const now = await secureSetting(device, "enabled_accessibility_services");
  if (serviceEntries(now).map(expandEntry).sort().join(":") !== expected.map(expandEntry).sort().join(":")) {
    mismatches.push(`enabled_accessibility_services is ${now}, expected ${expected.join(":")}`);
  }
  if (saved.tilePermission && !(await tilePermission(device))) mismatches.push("WRITE_SECURE_SETTINGS is not granted");
  return mismatches;
}

/**
 * Gradle runs in its own process group, so Ctrl+C reaches only this process. Stopping the Gradle
 * client would leave its daemon cancelling the run, and the uninstall it makes on the way out could
 * land after the restore.
 */
function runGradle(args: string[]): Promise<number> {
  const child = spawn(join(REPO_ROOT, "gradlew"), ["-p", REPO_ROOT, ...args], {
    detached: true,
    stdio: ["ignore", 2, 2],
    env: { ...process.env },
  });
  return new Promise((resolve) => child.on("exit", (code) => resolve(code ?? 1)));
}

const USAGE = `usage: tools/device-tests <command>

  run <serial> [gradle args...]   save Caretto's preferences and how the service, its grant and the
                                  tile stand on <serial>, run :app:connectedDebugAndroidTest there,
                                  then reinstall this checkout's debug build and put them back,
                                  whether or not the tests passed
  restore <serial> <backup dir>   put a saved backup back, for a run that was killed before it could

<serial> is required, as adb devices lists it: the command never picks a device by itself.`;

async function runTests(device: Device, gradleArgs: string[]): Promise<number> {
  await requireAttached(device);
  // Every child inherits it, so Gradle installs, tests and uninstalls on this device only.
  process.env.ANDROID_SERIAL = device.serial;

  // A build that fails here fails before anything on the device is touched, and the APK that
  // restore installs exists before the uninstall happens.
  log("building the debug and test APKs");
  await must([join(REPO_ROOT, "gradlew"), "-p", REPO_ROOT, ":app:assembleDebug", ":app:assembleDebugAndroidTest"], {
    inherit: true,
  });

  let saved: Saved;
  try {
    saved = await save(device);
  } catch (error) {
    throw new Error(`refusing to run the tests, the preferences cannot be saved: ${(error as Error).message}`);
  }
  const dir = writeBackup(saved);
  log(`saved the preferences and the service, grant and tile state to ${dir}`);

  let restoring = false;
  process.on("SIGINT", () =>
    log(restoring ? "interrupted while restoring" : "interrupted; the tests run to their end, then the restore"),
  );
  const code = await runGradle([":app:connectedDebugAndroidTest", ...gradleArgs]);
  restoring = true;

  const outcome = code === 0 ? "passed" : `failed (gradle exited ${code})`;
  const retry = `retry with: tools/device-tests restore ${device.serial} ${dir}`;
  let mismatches: string[];
  try {
    mismatches = await restore(device, saved);
  } catch (error) {
    mismatches = [`restoring failed: ${(error as Error).message}`];
  }
  if (mismatches.length > 0) {
    mismatches.forEach((line) => log(line));
    console.log(`instrumentation tests ${outcome} on ${device.serial}; restore incomplete, ${retry}`);
    return 1;
  }
  console.log(`instrumentation tests ${outcome} on ${device.serial}; preferences, service and tile restored`);
  return code === 0 ? 0 : 1;
}

async function restoreBackup(device: Device, dir: string): Promise<number> {
  await requireAttached(device);
  const saved = readBackup(dir);
  // A run whose own process was killed leaves Gradle running, and it uninstalls once the tests end.
  if (await installed(device, TEST_PACKAGE)) {
    throw new Error(
      `${TEST_PACKAGE} is still installed, so a test run may be in progress; retry when it ends. ` +
        `If no Gradle run is left, remove it with adb -s ${device.serial} uninstall ${TEST_PACKAGE}`,
    );
  }
  if (saved.identity !== (await deviceIdentity(device))) {
    throw new Error(`${dir} was saved from another device, ${saved.serial} (${saved.identity})`);
  }
  process.env.ANDROID_SERIAL = device.serial;
  // An older backup would overwrite whatever changed since; the current preferences are kept too.
  const current = await save(device).catch(() => undefined);
  if (current !== undefined && current.prefsSha256 !== saved.prefsSha256) {
    log(`the preferences on ${device.serial} differ from the backup; saved them to ${writeBackup(current)}`);
  }
  let mismatches: string[];
  try {
    mismatches = await restore(device, saved);
  } catch (error) {
    mismatches = [`restoring failed: ${(error as Error).message}`];
  }
  mismatches.forEach((line) => log(line));
  const retry = `retry with: tools/device-tests restore ${device.serial} ${dir}`;
  console.log(`${dir} ${mismatches.length === 0 ? "restored" : `restored incompletely, ${retry}`} on ${device.serial}`);
  return mismatches.length === 0 ? 0 : 1;
}

async function main(argv: string[]): Promise<number> {
  const [command, serial, ...rest] = argv;
  if (serial !== undefined && serial !== "" && !serial.startsWith("-")) {
    // The phone is on the shared adb server; tools/emu's emulator is not, so it cannot be named here.
    const device: Device = { serial, adbServer: "shared" };
    if (command === "run") return runTests(device, rest);
    if (command === "restore" && rest.length === 1) return restoreBackup(device, rest[0]!);
  }
  console.error(USAGE);
  return 2;
}

if (import.meta.main) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    console.error(`device-tests: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
