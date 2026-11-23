/**
 * The emulator scenarios `tools/e2e` runs. Each one starts an editor from a text and selection,
 * sends typed text and chords, and says what the editor should hold after each step that matters.
 *
 * A state is written as text with the caret as `|`, or a selection as `[` and `]` around it, so
 * `ab|c` is the caret before `c` and `[abc]` is all three selected. Neither character appears in
 * the scenarios' own text. A scenario runs in all four editors unless it names some, and a step
 * can expect something else in one editor (`expectIn`), because the editors legitimately differ.
 */

export const EDITORS = ["edittext-multiline", "edittext-singleline", "compose-state", "compose-value"] as const;
export type EditorName = (typeof EDITORS)[number];

/** The two editors that hold more than one line; the other two are single-line fields. */
const MULTILINE: EditorName[] = ["edittext-multiline", "compose-state"];

/**
 * A state as written above, the home screen in front, or, for a repeat whose count depends on
 * timing, text that lost at least `shortenedBy` characters from the end and then did not change
 * for `stillFor` milliseconds.
 */
export type Expectation = string | { home: true } | { shortenedBy: number; stillFor: number };

export type Action =
  | { type: string }
  | { press: string }
  /** Holds the chord down this long, then releases it. */
  | { hold: string; ms: number }
  /**
   * Opens the key map's capture sheet over the test editor's task, presses the chord, picks the
   * operation in the picker that follows, and focuses the editor again in the state it had.
   */
  | { capture: string; pick: string }
  /**
   * Opens the capture sheet the same way, covers it with Recents or the notification shade, which
   * must end capture, presses the chord there, which Caretto must pass through rather than hand to
   * the hidden sheet, and focuses the editor again in the state it had.
   */
  | { coverCapture: "recents" | "shade"; chord: string };

export type Step = Action & {
  expect?: Expectation;
  expectIn?: Partial<Record<EditorName, Expectation>>;
  /**
   * Whether the chord of a `press` or `hold` reaches the editor as a key. Off by default: Caretto
   * consumes a chord it remaps, which is what tells its edit apart from the editor's own handling
   * of the same chord (Compose deletes on Ctrl+H by itself).
   */
  reachesEditor?: boolean;
};

export type Scenario = {
  id: string;
  /** What breaks when this fails, for the reader of a failure. */
  covers: string;
  start: string;
  steps: Step[];
  editors?: EditorName[];
  /** Departures from the known state: remapping on, the test editor not in the pass-through list. */
  prefs?: { remapEnabled?: boolean; passThrough?: boolean };
  /** Runs on the default bindings plus HYPER_STAND_INS, and is counted apart in the summary. */
  standIn?: boolean;
};

/**
 * The emulator's built-in keyboard has no Meta key (docs/findings.md F21), so a Hyper chord cannot
 * be pressed there. The stand-in scenarios bind each Hyper operation a second time, on
 * Ctrl+Alt+Shift and the same key, and press that: the operation is checked end to end, while
 * matching the Meta bit of a real Hyper chord is left to MappingEngineTest and the phone. The
 * Hyper defaults stay bound, and the next scenario's reset removes the stand-ins.
 */
export const STAND_IN = "ctrl+alt+shift+";
export const HYPER_STAND_INS: { key: string; operation: string }[] = [
  { key: "c", operation: "Copy" },
  { key: "x", operation: "Cut" },
  { key: "v", operation: "Paste" },
  { key: "a", operation: "SelectAll" },
  { key: "z", operation: "Undo" },
  { key: "y", operation: "Redo" },
  { key: "h", operation: "Home" },
];

/**
 * Chords left to the editor itself. Ctrl+A selects all in every editor where Caretto would move to
 * the line start; Ctrl+H does nothing in an EditText, while Compose's own key mapping deletes.
 */
const UNTOUCHED: Step[] = [
  {
    press: "ctrl+h",
    reachesEditor: true,
    expect: "abc|",
    expectIn: { "compose-state": "ab|", "compose-value": "ab|" },
  },
  {
    press: "ctrl+a",
    reachesEditor: true,
    expect: "[abc]",
    expectIn: { "compose-state": "[ab]", "compose-value": "[ab]" },
  },
];

export const SCENARIOS: Scenario[] = [
  { id: "ctrl-b", covers: "Ctrl+B moves backward", start: "ab|c", steps: [{ press: "ctrl+b", expect: "a|bc" }] },
  { id: "ctrl-f", covers: "Ctrl+F moves forward", start: "a|bc", steps: [{ press: "ctrl+f", expect: "ab|c" }] },
  {
    id: "ctrl-p",
    covers: "Ctrl+P moves up a line",
    editors: MULTILINE,
    start: "abc\nde|f",
    steps: [{ press: "ctrl+p", expect: "ab|c\ndef" }],
  },
  {
    id: "ctrl-n",
    covers: "Ctrl+N moves down a line",
    editors: MULTILINE,
    start: "ab|c\ndef",
    steps: [{ press: "ctrl+n", expect: "abc\nde|f" }],
  },
  {
    id: "ctrl-p-n-single-line",
    covers: "Ctrl+P and Ctrl+N send Up and Down, which a single-line field takes to its start and end",
    editors: ["edittext-singleline", "compose-value"],
    start: "ab|c",
    steps: [
      { press: "ctrl+p", expect: "|abc" },
      { press: "ctrl+n", expect: "abc|" },
    ],
  },
  { id: "ctrl-a", covers: "Ctrl+A moves to the line start", start: "ab|c", steps: [{ press: "ctrl+a", expect: "|abc" }] },
  {
    id: "ctrl-a-logical-line",
    covers: "Ctrl+A stops at the start of the logical line, not the document",
    editors: MULTILINE,
    start: "abc\nde|f",
    steps: [{ press: "ctrl+a", expect: "abc\n|def" }],
  },
  { id: "ctrl-e", covers: "Ctrl+E moves to the line end", start: "a|bc", steps: [{ press: "ctrl+e", expect: "abc|" }] },
  {
    id: "ctrl-e-logical-line",
    covers: "Ctrl+E stops at the end of the logical line, not the document (F6)",
    editors: MULTILINE,
    start: "abc\nd|ef\nghi",
    steps: [{ press: "ctrl+e", expect: "abc\ndef|\nghi" }],
  },
  { id: "ctrl-h", covers: "Ctrl+H deletes backward", start: "ab|c", steps: [{ press: "ctrl+h", expect: "a|c" }] },
  { id: "ctrl-d", covers: "Ctrl+D deletes forward", start: "a|bc", steps: [{ press: "ctrl+d", expect: "a|c" }] },
  { id: "ctrl-k", covers: "Ctrl+K deletes to the line end", start: "a|bc", steps: [{ press: "ctrl+k", expect: "a|" }] },
  {
    id: "ctrl-k-newline",
    covers: "Ctrl+K at the end of a line deletes the newline",
    editors: MULTILINE,
    start: "abc|\ndef",
    steps: [{ press: "ctrl+k", expect: "abc|def" }],
  },
  {
    id: "alt-b",
    covers: "Alt+B moves backward by word",
    start: "hello world|",
    steps: [{ press: "alt+b", expect: "hello |world" }],
  },
  {
    id: "alt-f",
    covers: "Alt+F moves forward by word",
    start: "|hello world",
    steps: [{ press: "alt+f", expect: "hello| world" }],
  },
  {
    id: "alt-d",
    covers: "Alt+D deletes a word forward",
    start: "|hello world",
    steps: [{ press: "alt+d", expect: "| world" }],
  },
  {
    id: "alt-h",
    covers: "Alt+H deletes a word backward",
    start: "hello world|",
    steps: [{ press: "alt+h", expect: "hello |" }],
  },
  // The Hyper operations, pressed on stand-in chords: see HYPER_STAND_INS.
  {
    id: "hyper-standin-a",
    covers: "Select all, on the stand-in for Hyper+A",
    standIn: true,
    start: "ab|c",
    steps: [{ press: `${STAND_IN}a`, expect: "[abc]" }],
  },
  {
    id: "hyper-standin-c-v",
    covers: "Copy and paste, on the stand-ins for Hyper+C and Hyper+V",
    standIn: true,
    start: "ab|c",
    steps: [
      { press: `${STAND_IN}a`, expect: "[abc]" },
      { press: `${STAND_IN}c`, expect: "[abc]" },
      { press: "ctrl+e", expect: "abc|" },
      { press: `${STAND_IN}v`, expect: "abcabc|" },
    ],
  },
  {
    id: "hyper-standin-x-v",
    covers: "Cut and paste, on the stand-ins for Hyper+X and Hyper+V",
    standIn: true,
    start: "ab|c",
    steps: [
      { press: `${STAND_IN}a`, expect: "[abc]" },
      { press: `${STAND_IN}x`, expect: "|" },
      { press: `${STAND_IN}v`, expect: "abc|" },
    ],
  },
  {
    id: "hyper-standin-z-y",
    covers: "Undo and redo (F17: menu action and key event both sent), on the stand-ins for Hyper+Z and Hyper+Y",
    standIn: true,
    start: "abc|",
    steps: [
      { type: "d", expect: "abcd|" },
      { press: `${STAND_IN}z`, expect: "abc|" },
      { press: `${STAND_IN}y`, expect: "abcd|" },
    ],
  },
  {
    id: "hyper-standin-h",
    covers: "Going home, on the stand-in for Hyper+H",
    standIn: true,
    start: "ab|c",
    steps: [{ press: `${STAND_IN}h`, expect: { home: true } }],
  },
  {
    id: "grapheme-move",
    covers: "Ctrl+B and Ctrl+F step over a ZWJ emoji as one character (F4)",
    start: "a\u{1F468}‍\u{1F469}‍\u{1F467}‍\u{1F466}|b",
    steps: [
      { press: "ctrl+b", expect: "a|\u{1F468}‍\u{1F469}‍\u{1F467}‍\u{1F466}b" },
      { press: "ctrl+f", expect: "a\u{1F468}‍\u{1F469}‍\u{1F467}‍\u{1F466}|b" },
    ],
  },
  {
    id: "grapheme-delete",
    covers: "Ctrl+H deletes a flag as one character (F4)",
    start: "x\u{1F1EF}\u{1F1F5}|",
    steps: [{ press: "ctrl+h", expect: "x|" }],
  },
  {
    id: "japanese-move",
    covers: "Ctrl+B moves over Japanese one character at a time",
    start: "日本語|",
    steps: [{ press: "ctrl+b", expect: "日本|語" }],
  },
  {
    id: "key-repeat",
    covers: "Holding Ctrl+H repeats it (F13)",
    start: "abcdefghij|",
    steps: [{ hold: "ctrl+h", ms: 1500, expect: "|" }],
  },
  {
    id: "key-repeat-stops",
    covers: "A repeat stops when the key is released (F13)",
    // Longer than a short hold can delete, and no key follows: any key press would stop a repeat
    // that the release failed to stop.
    start: "abcdefghijklmnopqrstuvwxyz0123456789|",
    steps: [{ hold: "ctrl+h", ms: 700, expect: { shortenedBy: 2, stillFor: 1500 } }],
  },
  {
    id: "remap-disabled",
    covers: "With Enable remapping off, chords reach the editor untouched",
    prefs: { remapEnabled: false },
    start: "abc|",
    steps: UNTOUCHED,
  },
  {
    id: "pass-through-list",
    covers: "With the app in the pass-through list, chords reach the editor untouched",
    prefs: { passThrough: true },
    start: "abc|",
    steps: UNTOUCHED,
  },
  {
    id: "capture-dialog",
    covers: "The capture sheet takes a bound chord without running it, and the binding picked for it works",
    editors: ["edittext-multiline"],
    start: "a|bc",
    steps: [
      { capture: "ctrl+h", pick: "Move forward", expect: "a|bc" },
      { press: "ctrl+h", expect: "ab|c" },
    ],
  },
  {
    id: "capture-covered",
    covers: "Recents or the notification shade over the capture sheet ends capture, and a chord pressed there is not swallowed",
    editors: ["edittext-multiline"],
    start: "a|bc",
    steps: [
      { coverCapture: "recents", chord: "ctrl+b", expect: "a|bc" },
      { press: "ctrl+b", expect: "|abc" },
      { coverCapture: "shade", chord: "ctrl+b", expect: "|abc" },
      { press: "ctrl+f", expect: "a|bc" },
    ],
  },
];

export type EditorState = { text: string; selection: [number, number] };

/** `ab|c` and `[abc]` into text and selection, in UTF-16 units as the editors report them. */
export function parseState(written: string): EditorState {
  const caret = written.indexOf("|");
  if (caret !== -1) {
    if (written.includes("[") || written.includes("]") || written.indexOf("|", caret + 1) !== -1) {
      throw new Error(`state ${JSON.stringify(written)} must have one caret or one selection`);
    }
    return { text: written.replace("|", ""), selection: [caret, caret] };
  }
  const start = written.indexOf("[");
  const end = written.indexOf("]");
  if (start === -1 || end < start) {
    throw new Error(`state ${JSON.stringify(written)} has neither a caret | nor a selection [ ]`);
  }
  return { text: written.replace("[", "").replace("]", ""), selection: [start, end - 1] };
}
