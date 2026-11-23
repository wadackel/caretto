/**
 * Runs the emulator scenarios (tools/scenarios.ts) on the dedicated emulator and reports one
 * summary line on stdout, exiting 0 only when every case passed. `tools/e2e` is the entry point;
 * it enters the devShell and runs this file.
 *
 * Results come from the test editor's own `TestEditor` logcat lines, not from a UI dump, and from
 * Caretto's log in one place only: the capture sheet, where no editor is focused to show a chord
 * that ran anyway. Each case first puts the app in a known state through the debug-only
 * ScenarioReceiver: default bindings, remapping on, the test editor not in the pass-through list.
 */
import { mkdirSync, writeFileSync, appendFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import {
  PACKAGE,
  REPO_ROOT,
  adb,
  hold,
  loadConfig,
  press,
  start,
  typeText,
  type EmulatorConfig,
} from "./emu.ts";
import {
  EDITORS,
  HYPER_STAND_INS,
  SCENARIOS,
  parseState,
  type EditorName,
  type EditorState,
  type Expectation,
  type Scenario,
  type Step,
} from "./scenarios.ts";

const TEST_EDITOR = `${PACKAGE}/.ui.TestEditorActivity`;
const MAIN_ACTIVITY = `${PACKAGE}/.ui.MainActivity`;
const RECEIVER = `${PACKAGE}/.ui.testeditor.ScenarioReceiver`;
const LOG_PATH = join(REPO_ROOT, "build", "e2e.log");

/** How long a step may take to land, and how long a result must then hold still. */
const LAND_MS = 5000;
const STILL_MS = 400;
/** A step that should leave the editor as it was gets this long to change it anyway. */
const UNCHANGED_MS = 1200;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

class StepFailure extends Error {}

/** Follows logcat for the app's two tags, keeping each editor's latest state and Caretto's lines. */
class Logcat {
  private lines = 0;
  private readonly states = new Map<string, { state: EditorState; seq: number }>();
  private readonly caretto: { line: string; seq: number }[] = [];
  private readonly keys: { editor: string; key: string; seq: number }[] = [];
  private seq = 0;
  private proc: ReturnType<typeof Bun.spawn> | undefined;
  private stopped = false;
  /** How many times the stream has ended; a case it ended during cannot trust what it read. */
  endings = 0;
  /** The newest line's timestamp and the lines read at it, to follow again without repeats. */
  private lastStamp = "";
  private atLastStamp = new Set<string>();
  /** True from following again until the first line newer than what was already read. */
  private replaying = false;
  /** False from the stream ending until the stream that follows it again has delivered a line. */
  private live = true;

  constructor(private readonly path: string) {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, "");
  }

  async start(config: EmulatorConfig): Promise<void> {
    await adb(config, "logcat", "-c");
    this.follow(config);
  }

  /**
   * The stream ends when the adb server restarts, which this machine's has done mid-run; every
   * later case would then wait for lines that never come, so it is followed again from the newest
   * line already read.
   */
  private follow(config: EmulatorConfig): void {
    const since = this.lastStamp === "" ? [] : ["-T", this.lastStamp];
    this.proc = Bun.spawn(
      ["adb", "-s", config.serial, "logcat", "-v", "time", ...since, "Caretto:D", "TestEditor:D", "AndroidRuntime:E", "*:S"],
      { stdout: "pipe", stderr: "ignore", env: { ...process.env } },
    );
    void this.read(this.proc.stdout as ReadableStream<Uint8Array>).then(async () => {
      if (this.stopped) return;
      this.live = false;
      this.endings++;
      this.write(`~~~~~ logcat ended; following again from ${this.lastStamp || "the start"}`);
      await sleep(1000);
      this.replaying = this.lastStamp !== "";
      if (!this.stopped) this.follow(config);
    });
  }

  stop(): void {
    this.stopped = true;
    this.proc?.kill();
  }

  private async read(stream: ReadableStream<Uint8Array>): Promise<void> {
    const decoder = new TextDecoder();
    let pending = "";
    for await (const chunk of stream) {
      pending += decoder.decode(chunk, { stream: true });
      let newline: number;
      while ((newline = pending.indexOf("\n")) !== -1) {
        this.take(pending.slice(0, newline).replace(/\r$/, ""));
        pending = pending.slice(newline + 1);
      }
    }
  }

  /** Waits until the stream is back after an ending, so a case does not start on a gap. */
  async whenLive(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (!this.live) {
      if (Date.now() > deadline) throw new Error(`logcat did not come back within ${timeoutMs / 1000}s`);
      await sleep(100);
    }
  }

  private take(line: string): void {
    this.live = true;
    const stamp = /^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d) /.exec(line)?.[1];
    if (stamp !== undefined) {
      // Following again from a timestamp repeats what was already read at it; outside that replay
      // every line counts, since one can legitimately repeat within a millisecond.
      if (this.replaying) {
        if (stamp < this.lastStamp || (stamp === this.lastStamp && this.atLastStamp.has(line))) return;
        if (stamp > this.lastStamp) this.replaying = false;
      }
      if (stamp !== this.lastStamp) {
        this.lastStamp = stamp;
        this.atLastStamp = new Set();
      }
      this.atLastStamp.add(line);
    }
    this.write(line);
    this.seq++;
    const editorLine = /TestEditor\(\s*\d+\): (\{.*\})$/.exec(line)?.[1];
    if (editorLine !== undefined) {
      let parsed: { editor: string; key?: string; text: string; selection: [number, number] };
      try {
        parsed = JSON.parse(editorLine);
      } catch {
        // logcat cuts lines past its limit; the step that needed this one fails on the stale state.
        this.write(`!!!!! unreadable TestEditor line above`);
        return;
      }
      if (parsed.key !== undefined) this.keys.push({ editor: parsed.editor, key: parsed.key, seq: this.seq });
      else this.states.set(parsed.editor, { state: { text: parsed.text, selection: parsed.selection }, seq: this.seq });
      return;
    }
    if (line.includes("Caretto")) this.caretto.push({ line, seq: this.seq });
  }

  write(line: string): void {
    appendFileSync(this.path, `${line}\n`);
    this.lines += line.split("\n").length;
  }

  get lineCount(): number {
    return this.lines;
  }

  get mark(): number {
    return this.seq;
  }

  latest(editor: string): EditorState | undefined {
    return this.states.get(editor)?.state;
  }

  sawKey(editor: string, chord: string, mark: number): boolean {
    return this.keys.some((entry) => entry.seq > mark && entry.editor === editor && entry.key === chord);
  }

  changedSince(editor: string, mark: number): boolean {
    return (this.states.get(editor)?.seq ?? 0) > mark;
  }

  sawCaretto(match: (line: string) => boolean, since: number): boolean {
    return this.caretto.some((entry) => entry.seq > since && match(entry.line));
  }

  async waitForCaretto(match: (line: string) => boolean, since: number, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.sawCaretto(match, since)) return true;
      await sleep(50);
    }
    return false;
  }
}

const same = (a: EditorState | undefined, b: EditorState) =>
  a !== undefined && a.text === b.text && a.selection[0] === b.selection[0] && a.selection[1] === b.selection[1];

const show = (state: EditorState | undefined) =>
  state === undefined ? "nothing reported" : `text ${JSON.stringify(state.text)} selection [${state.selection.join(",")}]`;

type Case = { scenario: Scenario; editor: EditorName };

/** How a case failed: on a step, on adb, or with the log stream gone, which stops the run. */
type Outcome = { kind: "step" | "adb" | "lost"; text: string };

class Runner {
  constructor(
    private readonly config: EmulatorConfig,
    private readonly log: Logcat,
  ) {}

  private async broadcast(setup: object): Promise<void> {
    const encoded = Buffer.from(JSON.stringify(setup), "utf8").toString("base64");
    const out = await adb(this.config, "shell", "am", "broadcast", "-n", RECEIVER, "--es", "setup", encoded);
    const data = /data="([^"]*)"/.exec(out)?.[1];
    if (data !== "ok") throw new StepFailure(`setting up the app failed: ${data ?? out.trim()}`);
  }

  /** The app's own defaults, so the last case's bindings and verbose logging do not outlive the run. */
  async restoreDefaults(): Promise<void> {
    await this.broadcast({ prefs: { remapEnabled: true, passThrough: false, verboseLog: false, extraBindings: [] } });
  }

  private async openTestEditor(): Promise<void> {
    // Clear-top and single-top bring back the instance under the key map rather than stacking a
    // new one, whose editors would start empty under the same names.
    await adb(this.config, "shell", "am", "start", "-W", "--activity-clear-top", "--activity-single-top", "-n", TEST_EDITOR);
  }

  /** Puts [editor] in [state] and focuses it, then waits for the editor to say so. */
  private async focus(editor: EditorName, state: EditorState): Promise<void> {
    await this.broadcast({ editor: { name: editor, text: state.text, selection: state.selection } });
    const deadline = Date.now() + LAND_MS;
    while (!same(this.log.latest(editor), state)) {
      if (Date.now() > deadline) {
        throw new StepFailure(`the editor did not take the start state ${show(state)}; it has ${show(this.log.latest(editor))}`);
      }
      await sleep(50);
    }
    // The accessibility input method binds to the newly focused editor a moment after focus moves.
    await sleep(300);
  }

  private async foregroundPackage(): Promise<string | undefined> {
    const dump = await adb(this.config, "shell", "dumpsys", "activity", "activities");
    return /topResumedActivity=ActivityRecord\{\S+ \S+ ([^/\s]+)\//.exec(dump)?.[1];
  }

  private async homePackage(): Promise<string> {
    const out = await adb(
      this.config, "shell", "cmd", "package", "resolve-activity", "--brief",
      "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
    );
    const component = out.trim().split("\n").pop()?.trim() ?? "";
    return component.split("/")[0] ?? "";
  }

  private async screenSize(): Promise<[number, number]> {
    const out = await adb(this.config, "shell", "wm", "size");
    // An override, when set, is what the screen is drawn at, and it comes after the physical size.
    const sizes = [...out.matchAll(/(\d+)x(\d+)/g)];
    const last = sizes.at(-1);
    if (!last) throw new StepFailure(`cannot read the screen size from ${JSON.stringify(out.trim())}`);
    return [Number(last[1]), Number(last[2])];
  }

  /**
   * Taps the node whose text or content description is exactly [text], scrolling down to find it
   * when [scroll] is set, and otherwise looking again while a sheet is still sliding in. Only the
   * capture and cover steps use it, to reach the key map's controls, never to read a result. A dump between
   * chords is the price of driving the real sheets rather than a debug-only way into them.
   */
  private async tapText(text: string, scroll: boolean): Promise<boolean> {
    for (let attempt = 0; attempt < 8; attempt++) {
      const mark = this.log.mark;
      await adb(this.config, "shell", "uiautomator", "dump", "/sdcard/caretto-e2e.xml");
      const xml = await adb(this.config, "shell", "cat", "/sdcard/caretto-e2e.xml");
      // A dump makes the service disconnect and connect again (docs/debugging.md recipe 5); a chord
      // sent before it is back would pass straight through.
      await this.log.waitForCaretto((line) => line.includes("service connected"), mark, 5000);
      const bounds = findBounds(xml, text);
      if (bounds) {
        const [x1, y1, x2, y2] = bounds;
        await adb(this.config, "shell", "input", "tap", String((x1 + x2) >> 1), String((y1 + y2) >> 1));
        return true;
      }
      if (!scroll) {
        if (attempt >= 3) return false;
        await sleep(400);
        continue;
      }
      const [width, height] = await this.screenSize();
      const x = String(width >> 1);
      await adb(this.config, "shell", "input", "swipe", x, String((height * 2) / 3 | 0), x, String(height / 3 | 0), "300");
      await sleep(500);
    }
    return false;
  }

  /** Opens the sheet and returns the log mark from before it opened. */
  private async openCaptureSheet(): Promise<number> {
    const mark = this.log.mark;
    await adb(this.config, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY);
    if (!(await this.tapText("Add binding", false))) throw new StepFailure("no Add binding button on the key map");
    if (!(await this.log.waitForCaretto((line) => line.includes("capture mode on"), mark, LAND_MS))) {
      throw new StepFailure("the capture sheet did not open");
    }
    return mark;
  }

  private async coverCapture(
    step: { coverCapture: "recents" | "shade"; chord: string },
    editor: EditorName,
    before: EditorState,
  ): Promise<void> {
    const cover = step.coverCapture;
    const coverName = cover === "recents" ? "Recents" : "the notification shade";
    const opened = await this.openCaptureSheet();
    // The sheet's window takes focus a moment after it opens; capture must survive that.
    await sleep(UNCHANGED_MS);
    const ended = (line: string) => line.includes("capture mode off");
    if (this.log.sawCaretto(ended, opened)) throw new StepFailure("capture ended before anything covered the sheet");
    const covered = this.log.mark;
    if (cover === "recents") {
      await adb(this.config, "shell", "input", "keyevent", "KEYCODE_APP_SWITCH");
    } else {
      await adb(this.config, "shell", "cmd", "statusbar", "expand-notifications");
    }
    try {
      if (!(await this.log.waitForCaretto(ended, covered, LAND_MS))) {
        throw new StepFailure(`capture went on while ${coverName} covered the sheet`);
      }
      // No editor is behind the cover, so Caretto's own pass-through line is the trace that the
      // chord went on to whatever is in front rather than into the hidden sheet.
      const pressed = this.log.mark;
      await press(this.config, step.chord);
      const passed = `: ${carettoName(step.chord)} pass-through`;
      if (!(await this.log.waitForCaretto((line) => line.includes(passed), pressed, LAND_MS))) {
        throw new StepFailure(`${step.chord} pressed over ${coverName} was not passed through`);
      }
    } finally {
      // A failed collapse must not replace the step's own failure, which says what broke.
      if (cover === "shade") {
        await adb(this.config, "shell", "cmd", "statusbar", "collapse").catch((error: unknown) => {
          this.log.write(`!!!!! collapsing the shade failed: ${error instanceof Error ? error.message : String(error)}`);
        });
      }
    }
    await this.openTestEditor();
    await this.focus(editor, before);
  }

  private async act(step: Step, editor: EditorName, before: EditorState): Promise<void> {
    if ("type" in step) return typeText(this.config, step.type);
    if ("press" in step) return press(this.config, step.press);
    if ("hold" in step) return hold(this.config, step.hold, step.ms);
    if ("coverCapture" in step) return this.coverCapture(step, editor, before);
    await this.openCaptureSheet();
    const pressed = this.log.mark;
    await press(this.config, step.capture);
    if (!(await this.tapText(step.pick, false))) {
      throw new StepFailure(`no ${JSON.stringify(step.pick)} in an operation picker: the capture sheet did not take ${step.capture}`);
    }
    await sleep(300);
    // No editor is focused behind the sheet, so an operation run on the captured chord changes no
    // text; Caretto's own line for it is the only trace, and its absence is all this can check.
    const ran = `: ${carettoName(step.capture)} -> `;
    if (this.log.sawCaretto((line) => line.includes(ran), pressed)) {
      throw new StepFailure(`Caretto ran an operation for ${step.capture} while the capture sheet was reading it`);
    }
    await this.openTestEditor();
    await this.focus(editor, before);
  }

  private async check(expectation: Expectation, editor: EditorName, before: EditorState, mark: number): Promise<void> {
    if (typeof expectation === "object" && "shortenedBy" in expectation) {
      return this.checkShortened(expectation, editor, before);
    }
    if (typeof expectation !== "string") {
      const home = await this.homePackage();
      const deadline = Date.now() + LAND_MS;
      let foreground = await this.foregroundPackage();
      while (foreground !== home && Date.now() < deadline) {
        await sleep(200);
        foreground = await this.foregroundPackage();
      }
      if (foreground !== home) throw new StepFailure(`expected the home screen (${home}) in front; ${foreground ?? "nothing"} is`);
      return;
    }
    const expected = parseState(expectation);
    const fail = () =>
      new StepFailure(`expected ${show(expected)}\n      actual   ${show(this.log.latest(editor))}`);
    if (same(before, expected)) {
      await sleep(UNCHANGED_MS);
      if (this.log.changedSince(editor, mark) && !same(this.log.latest(editor), expected)) throw fail();
      return;
    }
    const deadline = Date.now() + LAND_MS;
    while (!same(this.log.latest(editor), expected)) {
      if (Date.now() > deadline) throw fail();
      await sleep(50);
    }
    await sleep(STILL_MS);
    if (!same(this.log.latest(editor), expected)) throw fail();
  }

  /** Text cut from the end by at least `shortenedBy`, and then left alone for `stillFor`. */
  private async checkShortened(
    expectation: { shortenedBy: number; stillFor: number },
    editor: EditorName,
    before: EditorState,
  ): Promise<void> {
    const kept = (state: EditorState | undefined) =>
      state !== undefined &&
      before.text.startsWith(state.text) &&
      before.text.length - state.text.length >= expectation.shortenedBy &&
      state.selection[0] === state.text.length &&
      state.selection[1] === state.text.length;
    const wanted = `text cut from the end by at least ${expectation.shortenedBy}`;
    const deadline = Date.now() + LAND_MS;
    while (!kept(this.log.latest(editor))) {
      if (Date.now() > deadline) throw new StepFailure(`expected ${wanted}\n      actual   ${show(this.log.latest(editor))}`);
      await sleep(50);
    }
    await sleep(STILL_MS);
    const settled = this.log.latest(editor);
    await sleep(expectation.stillFor);
    const after = this.log.latest(editor);
    // Emptied text cannot show a repeat that went on after the release, so it does not count.
    if (!kept(after) || !same(after, settled!) || after!.text === "") {
      throw new StepFailure(
        `expected ${show(settled)} to stay for ${expectation.stillFor}ms after the release\n      actual   ${show(after)}`,
      );
    }
  }

  /** A remapped chord is consumed before any window sees it; one left alone reaches the editor. */
  private async checkReach(editor: EditorName, chord: string, reaches: boolean, mark: number): Promise<void> {
    const key = keyName(chord);
    if (!reaches) {
      if (this.log.sawKey(editor, key, mark)) {
        throw new StepFailure(`${chord} reached the editor as a key, so Caretto did not take it`);
      }
      return;
    }
    const deadline = Date.now() + LAND_MS;
    while (!this.log.sawKey(editor, key, mark)) {
      if (Date.now() > deadline) throw new StepFailure(`${chord} never reached the editor, so Caretto took it`);
      await sleep(50);
    }
  }

  /**
   * A case that failed on adb rather than on what the editor showed runs once more: this machine's
   * adb server restarts now and then, and the emulator drops off `adb devices` for a moment.
   */
  async runWithRetry(testCase: Case): Promise<Outcome | undefined> {
    const first = await this.run(testCase);
    if (first === undefined || first.kind === "step") return first;
    this.log.write(`~~~~~ running ${testCase.scenario.id} [${testCase.editor}] again: it failed on something other than a step`);
    await sleep(3000);
    return this.run(testCase);
  }

  private async run({ scenario, editor }: Case): Promise<Outcome | undefined> {
    const waited = await this.log.whenLive(30_000).then(
      () => undefined,
      (error: Error) => error.message,
    );
    if (waited !== undefined) {
      this.log.write(`!!!!! FAIL ${scenario.id} [${editor}] setup: ${waited}`);
      const text = [
        `FAIL ${scenario.id} [${editor}] setup — ${scenario.covers}`,
        `      ${waited}`,
        `      log: ${relative(process.cwd(), LOG_PATH)} from line ${this.log.lineCount}`,
      ].join("\n");
      return { kind: "lost", text };
    }
    const firstLine = this.log.lineCount + 1;
    const endings = this.log.endings;
    this.log.write(`===== ${scenario.id} [${editor}]`);
    let stepLabel = "setup";
    // A stream that ended mid-case can pass a step that waited for nothing to happen, or fail one
    // whose lines had not come back yet, so neither result stands.
    const interrupted = () => {
      if (this.log.endings !== endings) throw new Error("logcat ended during the case, so its result does not stand");
    };
    try {
      await this.openTestEditor();
      await this.broadcast({
        prefs: {
          remapEnabled: scenario.prefs?.remapEnabled ?? true,
          passThrough: scenario.prefs?.passThrough ?? false,
          verboseLog: true,
          extraBindings: scenario.standIn ? STAND_IN_BINDINGS : [],
        },
      });
      let current = parseState(scenario.start);
      await this.focus(editor, current);
      for (const [index, step] of scenario.steps.entries()) {
        stepLabel = `step ${index + 1} (${describeStep(step)})`;
        this.log.write(`----- ${stepLabel}`);
        const mark = this.log.mark;
        await this.act(step, editor, current);
        const expectation = step.expectIn?.[editor] ?? step.expect;
        if (expectation !== undefined) await this.check(expectation, editor, current, mark);
        const chord = "press" in step ? step.press : "hold" in step ? step.hold : undefined;
        if (chord !== undefined) await this.checkReach(editor, chord, step.reachesEditor ?? false, mark);
        current = this.log.latest(editor) ?? current;
      }
      interrupted();
      return undefined;
    } catch (error) {
      if (error instanceof StepFailure && this.log.endings !== endings) {
        error = new Error(`logcat ended during the case, so its result does not stand (${error.message})`);
      }
      const reason = error instanceof Error ? error.message : String(error);
      this.log.write(`!!!!! FAIL ${stepLabel}: ${reason}`);
      const text = [
        `FAIL ${scenario.id} [${editor}] ${stepLabel} — ${scenario.covers}`,
        `      ${reason}`,
        `      log: ${relative(process.cwd(), LOG_PATH)} from line ${firstLine}`,
      ].join("\n");
      return { kind: error instanceof StepFailure ? "step" : "adb", text };
    }
  }
}

/** A chord as the test editor reports a key: `hyper+a` is `ctrl+alt+shift+meta+a`. */
function keyName(chord: string): string {
  const parts = chord.toLowerCase().split("+");
  const key = parts.pop() ?? "";
  const held = new Set(parts.flatMap((part) => (part === "hyper" ? ["ctrl", "alt", "shift", "meta"] : [part])));
  return [...["ctrl", "alt", "shift", "meta"].filter((modifier) => held.has(modifier)), key].join("+");
}

/**
 * HYPER_STAND_INS in BindingCodec's `keycode:modifiers=operation` form: Ctrl 1 + Alt 2 + Shift 4,
 * and KEYCODE_A is 29 with the letters in order after it.
 */
const STAND_IN_BINDINGS = HYPER_STAND_INS.map(
  ({ key, operation }) => `${29 + key.charCodeAt(0) - "a".charCodeAt(0)}:7=${operation}`,
);

/** A chord as CarettoService.describe writes it in the log: `ctrl+h` is `Ctrl+H`. */
function carettoName(chord: string): string {
  return keyName(chord)
    .split("+")
    .map((part, index, parts) =>
      index === parts.length - 1 ? part.toUpperCase() : part.charAt(0).toUpperCase() + part.slice(1),
    )
    .join("+");
}

function describeStep(step: Step): string {
  if ("type" in step) return `type ${JSON.stringify(step.type)}`;
  if ("press" in step) return `press ${step.press}`;
  if ("hold" in step) return `hold ${step.hold} ${step.ms}ms`;
  if ("coverCapture" in step) return `cover the capture sheet with ${step.coverCapture}, press ${step.chord}`;
  return `capture ${step.capture}, pick ${JSON.stringify(step.pick)}`;
}

/** Bounds of the node whose text or content description is exactly [text] in a uiautomator dump. */
function findBounds(xml: string, text: string): [number, number, number, number] | undefined {
  for (const node of xml.matchAll(/<node\b[^>]*>/g)) {
    // Attribute values cannot hold a raw `"`, so this splits them correctly; the labels searched
    // for contain no character XML would escape.
    const attributes = new Map([...node[0].matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1]!, m[2]!]));
    if (attributes.get("text") !== text && attributes.get("content-desc") !== text) continue;
    const match = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attributes.get("bounds") ?? "");
    if (match) return [Number(match[1]), Number(match[2]), Number(match[3]), Number(match[4])];
  }
  return undefined;
}

const USAGE = `usage: tools/e2e [--scenario <id>]... [--editor <name>]... [--no-build] [--list]

  Boots the dedicated emulator if needed, installs the current debug build, and runs every
  scenario in every editor it applies to. --scenario and --editor narrow the run; each can be
  given more than once. --list prints the scenario ids and exits.

  editors: ${EDITORS.join(", ")}
  The emulator settings are tools/emu's (CARETTO_AVD, CARETTO_EMU_PORT, ...).`;

function parseArgs(argv: string[]): { scenarios: string[]; editors: string[]; build: boolean; list: boolean } | undefined {
  const parsed = { scenarios: [] as string[], editors: [] as string[], build: true, list: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--no-build") parsed.build = false;
    else if (arg === "--list") parsed.list = true;
    else if ((arg === "--scenario" || arg === "--editor") && argv[i + 1] !== undefined) {
      (arg === "--scenario" ? parsed.scenarios : parsed.editors).push(argv[++i]!);
    } else return undefined;
  }
  return parsed;
}

async function main(argv: string[]): Promise<number> {
  const args = parseArgs(argv);
  if (!args) {
    console.error(USAGE);
    return 2;
  }
  const unknownScenario = args.scenarios.find((id) => !SCENARIOS.some((scenario) => scenario.id === id));
  const unknownEditor = args.editors.find((name) => !(EDITORS as readonly string[]).includes(name));
  if (unknownScenario !== undefined || unknownEditor !== undefined) {
    const what = unknownScenario !== undefined ? `scenario ${JSON.stringify(unknownScenario)}` : `editor ${JSON.stringify(unknownEditor)}`;
    console.error(`e2e: unknown ${what}\n${USAGE}`);
    return 2;
  }
  if (args.list) {
    for (const scenario of SCENARIOS) console.log(`${scenario.id}  ${scenario.covers}`);
    return 0;
  }
  const cases: Case[] = SCENARIOS.filter((scenario) => args.scenarios.length === 0 || args.scenarios.includes(scenario.id))
    .flatMap((scenario) =>
      (scenario.editors ?? [...EDITORS])
        .filter((editor) => args.editors.length === 0 || args.editors.includes(editor))
        .map((editor) => ({ scenario, editor })),
    );
  if (cases.length === 0) {
    console.error("e2e: no scenario applies to that selection");
    return 2;
  }

  const config = loadConfig();
  // Every child inherits it, so nothing that reaches for a device finds a connected phone.
  process.env.ANDROID_SERIAL = config.serial;
  // Straight after an adb server restart the emulator is missing from `adb devices` for a moment,
  // and start then takes it for stopped and refuses its busy gRPC port; once more settles that.
  await start(config, { build: args.build }).catch(async (error) => {
    const message = error instanceof Error ? error.message : String(error);
    if (!message.includes(`gRPC port ${config.grpcPort} is already in use`)) throw error;
    console.error(`e2e: ${message}; asking adb once more`);
    await sleep(5000);
    await start(config, { build: args.build });
  });

  const log = new Logcat(LOG_PATH);
  await log.start(config);
  const runner = new Runner(config, log);
  const began = Date.now();
  const failures: string[] = [];
  let ran = 0;
  try {
    for (const testCase of cases) {
      const failure = await runner.runWithRetry(testCase);
      console.error(`${failure ? "FAIL" : "ok  "} ${testCase.scenario.id} [${testCase.editor}]`);
      if (failure) failures.push(failure.text);
      ran++;
      // Every later case would wait as long for a stream that is not coming back.
      if (failure?.kind === "lost") break;
    }
  } finally {
    await runner.restoreDefaults().catch((error) => console.error(`e2e: restoring the defaults failed: ${error}`));
    await sleep(300);
    log.stop();
  }

  const seconds = Math.round((Date.now() - began) / 1000);
  const where = relative(process.cwd(), LOG_PATH);
  // The stand-ins are counted on the summary line itself, so a pass is not read as the Hyper chord
  // having been pressed.
  const standIns = cases.filter((testCase) => testCase.scenario.standIn).length;
  const notRun = cases.length - ran;
  const note =
    (standIns > 0 ? `, ${standIns} of them Hyper operations on Ctrl+Alt+Shift stand-ins` : "") +
    (notRun > 0 ? `, ${notRun} not run (logcat did not come back)` : "");
  if (failures.length > 0) console.error(`\n${failures.join("\n\n")}\n`);
  console.log(
    failures.length === 0
      ? `e2e: PASS ${cases.length} of ${cases.length} cases${note} in ${seconds}s (log ${where})`
      : `e2e: FAIL ${failures.length} of ${cases.length} cases failed${note} in ${seconds}s (log ${where})`,
  );
  return failures.length === 0 ? 0 : 1;
}

if (import.meta.main) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    console.error(`e2e: ${error instanceof Error ? error.message : String(error)}`);
    console.log("e2e: ERROR the run did not complete");
    process.exitCode = 1;
  }
}
