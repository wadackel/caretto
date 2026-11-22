/**
 * The dedicated emulator: create it, boot it headless, install Caretto and enable it, send chords
 * to it as kernel input, and stop it. `tools/emu` is the entry point; it enters the devShell and
 * runs this file. Everything is exported so a scenario runner can drive the same functions.
 *
 * Every adb call names this emulator's serial, and that serial is checked to belong to the AVD
 * configured here before anything is installed or sent, so nothing reaches another emulator or a
 * phone that happens to be connected.
 */
import { spawn as spawnChild } from "node:child_process";
import { existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export const PACKAGE = "dev.wadackel.caretto";
export const SERVICE = `${PACKAGE}/${PACKAGE}.service.CarettoService`;
const TEST_EDITOR = `${PACKAGE}/.ui.TestEditorActivity`;
const AVD_PREFIX = "caretto_";

export const REPO_ROOT = dirname(import.meta.dir);

export type EmulatorConfig = {
  avd: string;
  consolePort: number;
  grpcPort: number;
  serial: string;
  systemImage: string;
  device: string;
  bootTimeoutSeconds: number;
  sdk: string;
  avdHome: string;
};

function defaultAbi(): string {
  if (process.arch === "arm64") return "arm64-v8a";
  if (process.arch === "x64") return "x86_64";
  throw new Error(`no default system image for ${process.arch}; set CARETTO_SYSTEM_IMAGE`);
}

function intFromEnv(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value)) throw new Error(`${name} must be an integer, got ${raw}`);
  return value;
}

/** Reads the configuration from the environment; every value has an overridable default. */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): EmulatorConfig {
  const sdk = env.ANDROID_HOME;
  if (!sdk) throw new Error("ANDROID_HOME is not set; run this through tools/emu or the devShell");

  const avd = env.CARETTO_AVD || "caretto_e2e";
  // A name outside the prefix is someone else's AVD; this tool rewrites the config of the one it
  // is given and installs onto it, so it is not allowed near a shared one by a typo.
  if (!avd.startsWith(AVD_PREFIX)) {
    throw new Error(`CARETTO_AVD must start with ${AVD_PREFIX}, got ${avd}`);
  }

  const consolePort = intFromEnv(env, "CARETTO_EMU_PORT", 5570);
  if (consolePort < 5554 || consolePort > 5682 || consolePort % 2 !== 0) {
    throw new Error(`CARETTO_EMU_PORT must be an even number from 5554 to 5682, got ${consolePort}`);
  }

  return {
    avd,
    consolePort,
    grpcPort: intFromEnv(env, "CARETTO_GRPC_PORT", consolePort + 3000),
    serial: `emulator-${consolePort}`,
    systemImage: env.CARETTO_SYSTEM_IMAGE || `system-images;android-36;google_apis;${defaultAbi()}`,
    device: env.CARETTO_AVD_DEVICE || "pixel_7",
    bootTimeoutSeconds: intFromEnv(env, "CARETTO_BOOT_TIMEOUT", 600),
    sdk,
    avdHome: env.ANDROID_AVD_HOME || join(env.ANDROID_USER_HOME || join(homedir(), ".android"), "avd"),
  };
}

function log(message: string): void {
  console.error(`emu: ${message}`);
}

export type RunResult = { code: number; stdout: string; stderr: string };

export async function run(cmd: string[], options: { stdin?: string; inherit?: boolean } = {}): Promise<RunResult> {
  const proc = Bun.spawn(cmd, {
    // Bun.spawn's default is the environment as it was at startup, without later assignments.
    env: { ...process.env },
    stdin: options.stdin === undefined ? "ignore" : new Blob([options.stdin]),
    // Shown output goes to stderr, so stdout carries only the one summary line a caller reads.
    stdout: options.inherit ? 2 : "pipe",
    stderr: options.inherit ? "inherit" : "pipe",
  });
  const [stdout, stderr, code] = await Promise.all([
    options.inherit ? "" : new Response(proc.stdout as ReadableStream).text(),
    options.inherit ? "" : new Response(proc.stderr as ReadableStream).text(),
    proc.exited,
  ]);
  return { code, stdout, stderr };
}

export async function must(cmd: string[], options: { stdin?: string; inherit?: boolean } = {}): Promise<string> {
  const result = await run(cmd, options);
  if (result.code !== 0) {
    throw new Error(`${cmd.join(" ")} exited ${result.code}\n${result.stderr || result.stdout}`.trim());
  }
  return result.stdout;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** An adb target: every call names its serial. An EmulatorConfig is one. */
export type Device = { serial: string };

export function adb(device: Device, ...args: string[]): Promise<string> {
  return must(["adb", "-s", device.serial, ...args]);
}

async function ensureSystemImage(config: EmulatorConfig): Promise<void> {
  if (existsSync(join(config.sdk, ...config.systemImage.split(";")))) return;
  log(`installing ${config.systemImage} with sdkmanager`);
  await must(["sdkmanager", "--install", config.systemImage], { stdin: "y\n".repeat(20), inherit: true });
}

/** The settings this tool relies on; written on every start, so an older AVD picks them up too. */
const AVD_SETTINGS: Record<string, string> = {
  // The built-in keyboard is what makes gRPC keys arrive as a physical device (deviceId 0).
  "hw.keyboard": "yes",
};

async function ensureAvd(config: EmulatorConfig): Promise<void> {
  const avdDir = join(config.avdHome, `${config.avd}.avd`);
  const existing = (await must(["emulator", "-list-avds"])).split("\n").map((line) => line.trim());
  if (!existing.includes(config.avd)) {
    await ensureSystemImage(config);
    log(`creating AVD ${config.avd} from ${config.systemImage}`);
    await must(
      ["avdmanager", "create", "avd", "-n", config.avd, "-k", config.systemImage, "-d", config.device, "-p", avdDir],
      { stdin: "no\n" },
    );
  }

  const configPath = join(avdDir, "config.ini");
  const lines = readFileSync(configPath, "utf8").split("\n").filter((line) => line !== "");
  for (const [key, value] of Object.entries(AVD_SETTINGS)) {
    const index = lines.findIndex((line) => line.split("=")[0]?.trim() === key);
    if (index === -1) lines.push(`${key}=${value}`);
    else lines[index] = `${key}=${value}`;
  }
  writeFileSync(configPath, `${lines.join("\n")}\n`);
}

/** True when this config's emulator is up; fails when its port belongs to another AVD. */
export async function isRunning(config: EmulatorConfig): Promise<boolean> {
  const devices = await must(["adb", "devices"]);
  const listed = devices.split("\n").some((line) => line.split(/\s+/)[0] === config.serial);
  if (!listed) return false;
  const name = (await adb(config, "emu", "avd", "name")).split(/\r?\n/)[0]?.trim();
  if (name !== config.avd) {
    throw new Error(`${config.serial} is running AVD ${name}, not ${config.avd}; choose another CARETTO_EMU_PORT`);
  }
  return true;
}

async function requireRunning(config: EmulatorConfig): Promise<void> {
  if (!(await isRunning(config))) throw new Error(`${config.avd} is not running; run tools/emu start`);
}

async function waitForBoot(config: EmulatorConfig, exited: () => string | undefined, logPath: string): Promise<void> {
  const deadline = Date.now() + config.bootTimeoutSeconds * 1000;
  while (Date.now() < deadline) {
    const reason = exited();
    if (reason !== undefined) throw new Error(`emulator exited (${reason}) before booting; see ${logPath}`);
    const result = await run(["adb", "-s", config.serial, "shell", "getprop", "sys.boot_completed"]);
    if (result.code === 0 && result.stdout.trim() === "1") return;
    await sleep(2000);
  }
  throw new Error(`${config.avd} did not finish booting in ${config.bootTimeoutSeconds}s; see ${logPath}`);
}

const emulatorLogPath = (config: EmulatorConfig) => join(REPO_ROOT, "build", `emulator-${config.avd}.log`);

function loopbackPortFree(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const server = createServer();
    server.once("error", () => resolve(false));
    server.listen(port, "127.0.0.1", () => server.close(() => resolve(true)));
  });
}

async function boot(config: EmulatorConfig): Promise<void> {
  const logPath = emulatorLogPath(config);
  mkdirSync(dirname(logPath), { recursive: true });
  const out = openSync(logPath, "w");

  log(`booting ${config.avd} on ${config.serial}, gRPC ${config.grpcPort}; log in ${logPath}`);
  // -grpc alone listens on every interface with no authentication; -grpc-use-token makes every
  // call present the token the emulator writes to its discovery file (see grpcEndpoint).
  const child = spawnChild(
    "emulator",
    [
      "-avd", config.avd,
      "-port", String(config.consolePort),
      "-grpc", String(config.grpcPort),
      "-grpc-use-token",
      "-no-window",
      "-no-audio",
      "-no-boot-anim",
      "-no-snapshot-save",
      "-gpu", "swiftshader_indirect",
    ],
    { detached: true, stdio: ["ignore", out, out] },
  );
  let exitReason: string | undefined;
  child.on("exit", (code, signal) => {
    exitReason = signal ? `signal ${signal}` : `code ${code}`;
  });
  child.unref();

  await waitForBoot(config, () => exitReason, logPath);
}

async function keepAwake(config: EmulatorConfig): Promise<void> {
  // A sleeping or locked screen takes no key input, and nobody is there to wake it.
  await adb(config, "shell", "svc", "power", "stayon", "true");
  await adb(config, "shell", "input", "keyevent", "KEYCODE_WAKEUP");
  await adb(config, "shell", "wm", "dismiss-keyguard");
}

/**
 * docs/debugging.md recipe 2: the framework routes keys through a filter only while the service is
 * up.
 */
export async function serviceFiltering(device: Device): Promise<boolean> {
  const dump = await adb(device, "shell", "dumpsys", "accessibility");
  const enabled = dump.split("\n").some((line) => line.includes("Enabled services:") && line.includes(SERVICE));
  return enabled && /Enabled features of Display \[0\] = \[[^\]]*KeyboardInterceptor/.test(dump);
}

/** The entries of `enabled_accessibility_services`, which reads `null` when it was never set. */
export function serviceEntries(setting: string): string[] {
  const value = setting.trim();
  return value === "null" ? [] : value.split(":").filter((entry) => entry !== "");
}

/** The framework rewrites an entry in either form, `pkg/.Cls` or `pkg/pkg.Cls`. */
export function expandEntry(entry: string): string {
  const [pkg, cls] = entry.split("/");
  return cls?.startsWith(".") ? `${pkg}/${pkg}${cls}` : entry;
}

/** The permission behind the quick settings tile, which only adb can grant. */
export async function grantTilePermission(device: Device): Promise<void> {
  await adb(device, "shell", "pm", "grant", PACKAGE, "android.permission.WRITE_SECURE_SETTINGS");
}

/**
 * Writes `services` as the enabled accessibility services until Caretto is filtering keys.
 * `services` must include Caretto; it can hold other entries, which a phone keeps there and a
 * write of Caretto alone would drop.
 */
export async function enableService(device: Device, services: string[] = [SERVICE]): Promise<void> {
  // adb hands the arguments to the device's shell, and a class name may contain `$`.
  const value = services.join(":");
  if (value.includes("'")) throw new Error(`cannot write ${value} as enabled_accessibility_services`);
  const deadline = Date.now() + 30_000;
  while (!(await serviceFiltering(device))) {
    if (Date.now() > deadline) throw new Error("the service did not come up after enabling it");
    // Written straight after an install, the setting can be cleared again while the framework is
    // still registering the package, so it is checked and written until the service is up.
    const current = await adb(device, "shell", "settings", "get", "secure", "enabled_accessibility_services");
    if (!serviceEntries(current).map(expandEntry).includes(SERVICE)) {
      await adb(device, "shell", "settings", "put", "secure", "enabled_accessibility_services", `'${value}'`);
      await adb(device, "shell", "settings", "put", "secure", "accessibility_enabled", "1");
    }
    await sleep(500);
  }
}

/** Builds the debug APK (unless told not to), installs it, and enables the service again. */
export async function install(config: EmulatorConfig, options: { build: boolean }): Promise<void> {
  await requireRunning(config);
  if (options.build) {
    log("building the debug APK");
    await must([join(REPO_ROOT, "gradlew"), "-p", REPO_ROOT, ":app:assembleDebug"], { inherit: true });
  }
  const apk = join(REPO_ROOT, "app/build/outputs/apk/debug/app-debug.apk");
  log(`installing ${apk}`);
  await adb(config, "install", "-r", apk);

  // A first install starts without the grant, and one written too early is cleared again, so it
  // is applied after every install rather than trusted to have survived.
  await grantTilePermission(config);
  await enableService(config);
}

/** Creates the AVD when missing, boots it when stopped, and installs and enables Caretto. */
export async function start(config: EmulatorConfig, options: { build: boolean }): Promise<void> {
  if (await isRunning(config)) {
    log(`${config.avd} is already running on ${config.serial}`);
    // It may still be booting from an earlier start.
    await waitForBoot(config, () => undefined, emulatorLogPath(config));
  } else {
    // An emulator that finds its gRPC port taken on 127.0.0.1 still boots and binds ::1 instead;
    // grpcEndpoint would catch that, but only after a full boot.
    if (!(await loopbackPortFree(config.grpcPort))) {
      throw new Error(`gRPC port ${config.grpcPort} is already in use; choose another CARETTO_GRPC_PORT`);
    }
    await ensureAvd(config);
    await boot(config);
    // The boot wait asked the serial only; another emulator holding the port would satisfy it.
    await requireRunning(config);
  }
  // Fails here, before anything is installed, when 127.0.0.1 at the gRPC port is someone else's.
  await grpcEndpoint(config);
  await keepAwake(config);
  await install(config, options);
}

export async function stop(config: EmulatorConfig): Promise<void> {
  if (!(await isRunning(config))) {
    log(`${config.avd} is not running`);
    return;
  }
  await adb(config, "emu", "kill");
  const deadline = Date.now() + 60_000;
  while (await isRunning(config).catch(() => true)) {
    if (Date.now() > deadline) throw new Error(`${config.avd} did not stop`);
    await sleep(1000);
  }
}

/** Linux evdev codes (input-event-codes.h). The emulator's qwerty2 layout maps them to Android keys. */
const EVDEV: Record<string, number> = {
  esc: 1, escape: 1,
  "1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11,
  "-": 12, minus: 12, "=": 13, equal: 13,
  backspace: 14, tab: 15,
  q: 16, w: 17, e: 18, r: 19, t: 20, y: 21, u: 22, i: 23, o: 24, p: 25,
  "[": 26, "]": 27, enter: 28,
  ctrl: 29,
  a: 30, s: 31, d: 32, f: 33, g: 34, h: 35, j: 36, k: 37, l: 38,
  ";": 39, "'": 40, "`": 41,
  shift: 42,
  "\\": 43,
  z: 44, x: 45, c: 46, v: 47, b: 48, n: 49, m: 50,
  ",": 51, ".": 52, "/": 53,
  alt: 56, space: 57,
  f1: 59, f2: 60, f3: 61, f4: 62, f5: 63, f6: 64, f7: 65, f8: 66, f9: 67, f10: 68, f11: 87, f12: 88,
  home: 102, up: 103, pageup: 104, left: 105, right: 106, end: 107, down: 108, pagedown: 109,
  insert: 110, delete: 111, del: 111,
  meta: 125,
};

const MODIFIERS = ["ctrl", "alt", "shift", "meta"] as const;

export function evdevCode(name: string): number {
  const code = EVDEV[name.toLowerCase()];
  if (code === undefined) throw new Error(`unknown key ${name}`);
  return code;
}

/**
 * Parses `Ctrl+A`, `alt+shift+left`, `hyper+h` (Hyper is all four modifiers) into the order the
 * keys go down; they come up in reverse.
 */
export function parseChord(chord: string): number[] {
  const parts = chord.split("+").map((part) => part.toLowerCase());
  const key = parts.pop();
  if (key === undefined || key === "") throw new Error(`empty chord: ${chord}`);
  const held = new Set<string>();
  for (const part of parts) {
    if (part === "hyper") MODIFIERS.forEach((modifier) => held.add(modifier));
    else if ((MODIFIERS as readonly string[]).includes(part)) held.add(part);
    else throw new Error(`${part} in ${chord} is not a modifier`);
  }
  return [...MODIFIERS.filter((modifier) => held.has(modifier)).map(evdevCode), evdevCode(key)];
}

export type GrpcEndpoint = { port: number; token: string };

/**
 * This emulator's gRPC port and the token `-grpc-use-token` makes every call present, read from
 * the discovery file the emulator writes, once the process listening on 127.0.0.1 at that port is
 * shown to be the one the file belongs to. The console on this AVD's serial names the file; its
 * location differs by host OS, which is why it is asked for rather than built. Not cached: every
 * boot generates a new token.
 */
export async function grpcEndpoint(config: EmulatorConfig): Promise<GrpcEndpoint> {
  await requireRunning(config);
  const path = (await adb(config, "emu", "avd", "discoverypath")).split(/\r?\n/)[0]?.trim() ?? "";
  const entries = new Map(
    readFileSync(path, "utf8")
      .split("\n")
      .filter((line) => line.includes("="))
      .map((line) => [line.slice(0, line.indexOf("=")).trim(), line.slice(line.indexOf("=") + 1).trim()]),
  );
  if (entries.get("avd.name") !== config.avd) {
    throw new Error(`${path} belongs to ${entries.get("avd.name")}, not ${config.avd}`);
  }
  if (Number(entries.get("grpc.port")) !== config.grpcPort) {
    throw new Error(
      `${config.avd} has gRPC on ${entries.get("grpc.port") ?? "no port"}, not ${config.grpcPort}; ` +
        "it was started with other settings",
    );
  }
  const token = entries.get("grpc.token");
  if (!token) throw new Error(`no grpc.token in ${path}; start the emulator with tools/emu start`);

  // The file says which port the emulator was given, not that it got it: one that finds the port
  // taken boots anyway on ::1. So the token goes out only when the listener on 127.0.0.1 is this
  // emulator, the process the discovery file is named after.
  const pid = /pid_(\d+)\.ini$/.exec(path)?.[1];
  const listeners = (await run(["lsof", "-nP", "-a", `-iTCP@127.0.0.1:${config.grpcPort}`, "-sTCP:LISTEN", "-Fp"]))
    .stdout.split("\n")
    .filter((line) => line.startsWith("p"))
    .map((line) => line.slice(1));
  if (pid === undefined || listeners.length !== 1 || listeners[0] !== pid) {
    throw new Error(
      `127.0.0.1:${config.grpcPort} is held by ${listeners.join(", ") || "nobody"}, not ${config.avd}` +
        `${pid ? ` (pid ${pid})` : ""}; restart it with tools/emu stop and start`,
    );
  }
  return { port: config.grpcPort, token };
}

async function sendKeyTo(
  config: EmulatorConfig,
  endpoint: GrpcEndpoint,
  code: number,
  eventType: "keydown" | "keyup",
): Promise<void> {
  // The token travels in the environment and is expanded by grpcurl, so it stays out of argv,
  // which any local user can read from the process list. 127.0.0.1 rather than localhost, which
  // can resolve to ::1, where the emulator does not listen and anyone else could.
  const proc = Bun.spawn(
    [
      "grpcurl",
      "-plaintext",
      "-import-path", join(config.sdk, "emulator/lib"),
      "-proto", "emulator_controller.proto",
      "-expand-headers",
      "-H", "authorization: Bearer ${CARETTO_GRPC_TOKEN}",
      "-d", JSON.stringify({ codeType: "Evdev", eventType, keyCode: code }),
      `127.0.0.1:${endpoint.port}`,
      "android.emulation.control.EmulatorController/sendKey",
    ],
    { env: { ...process.env, CARETTO_GRPC_TOKEN: endpoint.token }, stdout: "ignore", stderr: "pipe" },
  );
  const [stderr, exitCode] = await Promise.all([new Response(proc.stderr).text(), proc.exited]);
  if (exitCode !== 0) {
    throw new Error(`sendKey ${eventType} ${code} failed: ${stderr.split(endpoint.token).join("<token>").trim()}`);
  }
}

/** One key transition through gRPC `sendKey`, which the guest sees as its built-in keyboard. */
export async function sendKey(config: EmulatorConfig, code: number, eventType: "keydown" | "keyup"): Promise<void> {
  await sendKeyTo(config, await grpcEndpoint(config), code, eventType);
}

export async function press(config: EmulatorConfig, chord: string): Promise<void> {
  const codes = parseChord(chord);
  const endpoint = await grpcEndpoint(config);
  for (const code of codes) await sendKeyTo(config, endpoint, code, "keydown");
  for (const code of [...codes].reverse()) await sendKeyTo(config, endpoint, code, "keyup");
}

/** Holds a chord down for [ms] and releases it; the endpoint is looked up once, so the hold lasts close to [ms]. */
export async function hold(config: EmulatorConfig, chord: string, ms: number): Promise<void> {
  const codes = parseChord(chord);
  const endpoint = await grpcEndpoint(config);
  for (const code of codes) await sendKeyTo(config, endpoint, code, "keydown");
  await sleep(ms);
  for (const code of [...codes].reverse()) await sendKeyTo(config, endpoint, code, "keyup");
}

/** Plain text through the console. It reaches the kernel too, but it cannot hold a modifier. */
export async function typeText(config: EmulatorConfig, text: string): Promise<void> {
  // The console drops anything else without an error, which would pass a short input as typed.
  if (/[^\x20-\x7e]/.test(text)) throw new Error("type takes printable ASCII only");
  await requireRunning(config);
  // It also drops a text of 16 characters or more whole, so it goes in shorter pieces.
  for (let at = 0; at < text.length; at += 15) {
    await adb(config, "emu", "event", "text", text.slice(at, at + 15));
  }
}

/** Opens the test editor, which debug builds export for this (src/debug/AndroidManifest.xml). */
export async function openTestEditor(config: EmulatorConfig): Promise<void> {
  await requireRunning(config);
  await adb(config, "shell", "am", "start", "-W", "-n", TEST_EDITOR);
}

const USAGE = `usage: tools/emu <command>

  start [--no-build]   create the AVD if missing, boot it headless, install Caretto and enable it
  install [--no-build] rebuild and reinstall the debug APK, and enable the service again
  stop                 shut the emulator down
  press <chord>...     press and release each chord in turn: ctrl+a, alt+shift+b
                       (meta and hyper+ send evdev 125, which the built-in keyboard maps to no
                       key, so a Hyper chord cannot be pressed here: docs/findings.md F21)
  keydown <key>        hold a key down (release it with keyup)
  keyup <key>
  type <text>          type plain text, no modifiers, printable ASCII only
  editor               open the test editor

environment (defaults in brackets):
  CARETTO_AVD [caretto_e2e]  CARETTO_EMU_PORT [5570]  CARETTO_GRPC_PORT [console port + 3000]
  CARETTO_SYSTEM_IMAGE [system-images;android-36;google_apis;<host abi>]
  CARETTO_AVD_DEVICE [pixel_7]  CARETTO_BOOT_TIMEOUT [600 seconds]`;

async function main(argv: string[]): Promise<number> {
  const [command, ...args] = argv;
  const build = !args.includes("--no-build");
  const config = loadConfig();
  // Every child inherits it, so a Gradle task that reaches for a device finds this one and not
  // whichever phone is also connected.
  process.env.ANDROID_SERIAL = config.serial;
  switch (command) {
    case "start":
      await start(config, { build });
      console.log(`${config.avd} ready on ${config.serial} (gRPC ${config.grpcPort}): Caretto installed and enabled`);
      return 0;
    case "install":
      await install(config, { build });
      console.log(`Caretto installed and enabled on ${config.serial}`);
      return 0;
    case "stop":
      await stop(config);
      console.log(`${config.avd} stopped`);
      return 0;
    case "press":
      if (args.length === 0) break;
      // A typo in the third chord must not leave the first two already sent.
      args.forEach(parseChord);
      for (const chord of args) await press(config, chord);
      return 0;
    case "keydown":
    case "keyup":
      if (args.length !== 1) break;
      await sendKey(config, evdevCode(args[0]!), command);
      return 0;
    case "type":
      if (args.length !== 1) break;
      await typeText(config, args[0]!);
      return 0;
    case "editor":
      await openTestEditor(config);
      return 0;
  }
  console.error(USAGE);
  return 2;
}

if (import.meta.main) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    console.error(`emu: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
