# Caretto design

How Caretto presents itself: the name, the mark, and the rules the app's screens follow.

## Brand

### The name

**Caretto** is `caret`, the English word for the text cursor, plus `-etto`, the Italian
diminutive, the same ending as in amaretto and libretto. It places the app as a small tool that
sits well in the hand, not a grand utility: underneath, it does not restrike keys, it moves the
caret.

The name had to be a coined word, to avoid collisions on the Play Store and in trademarks; it had
to combine a word for what the app does with a keyword; and it had to read as English rather than
as a Japanese word written in romaji. It is pronounced ka-ret-to (カレット in Japanese), the same for
Japanese-speaking and English-speaking users.

### The mark

The caret `^` split into two wedges, left and right, with a gap left at the apex. **The gap is the
insertion point itself**, so the meaning of the symbol and its function sit in one shape. The left
wedge is darker and the right lighter, so the right one comes forward slightly and gives the mark
depth.

| Item | Value |
|---|---|
| Viewport | 108 × 108 |
| Base | y 64, width 13.5 |
| Top edge | y 44, width 10 |
| Arms | 51°, corner radius 3 |
| Gap | 9.7 (measured) |
| Left wedge | `#7C4DFF` |
| Right wedge | `#CBB6FF` |
| Icon background | `#0F0C17` |

The point farthest from the centre (54, 54) is 26.7 away, inside the adaptive icon's safe zone, a
circle of radius 33 that every launcher mask leaves visible. The mark has to stay readable down to
48 dp, its size in the launcher grid and in the accessibility settings list, where it is what tells
the app apart.

The mark is meant to convey accuracy, quiet and precision through a geometric, abstract shape:
text and the caret rather than the keyboard. Earlier logos drew a keyboard, which was too
generic, and an I-beam on a dark purple gradient, which was large and heavy and, being a plain
I-beam, had little identity of its own.

Where it appears:

- **Launcher icon**: `ic_launcher_foreground` (the two wedges) over `ic_launcher_background` (a
  flat `#0F0C17`), joined in `mipmap-anydpi-v26/ic_launcher.xml`.
- **Themed icon** (Android 13 and later): `ic_launcher_monochrome`, the mark in white with no
  background shape.
- **Quick settings tile**: `ic_tile`, the strokes of the monochrome icon cropped to 24 dp.
- **App bar**: `ic_mark`, the two wedges cropped to their own bounds, beside the name on the home
  screen. On the light ground the right wedge is `#B9A2F5` rather than `#CBB6FF`, which washes out
  against near-white; the dark variant in `drawable-night` keeps `#CBB6FF`.

## The interface

The screens aim for some character of their own, bindings that can be read at a glance, and as
few steps as possible to the thing being changed. The character comes from type, not from colour.

### Appearance

It follows the system's light or dark setting. The palette is fixed rather than taken from the
wallpaper, since an app whose colours change from phone to phone cannot be recognised by them.

| Role | Light | Dark | Used for |
|---|---|---|---|
| Background | `#FBFAF7` | `#131116` | Every page, and the window before the first frame |
| Row | `#F1EEF4` | `#1D1A22` | The ground of each row and of the status row |
| Ink | `#1D1B20` | `#EBE7F0` | Text, the add button, the snackbar |
| Muted | `#625C6A` | `#A29CAB` | Detail lines, section labels, modifier keys |
| Accent | `#6A4BD0` | `#CBB6FF` | Switches, text buttons, the selected row in the two-pane Settings |
| Track | `#CFC8D8` | `#433D4C` | A switch that is off, the outline of the capture box |
| Sheet | `#FFFFFF` | `#1B1820` | Sheets from the bottom |
| Running | `#2F7D4F` | `#7FD3A0` | The status dot while remapping |
| Waiting | `#9A6A12` | `#E8B86A` | The status dot while connecting |
| Stopped | `#B3261E` | `#F2B8B5` | The status dot while off, and Remove |

Muted text is always below the large-text size, so it has to reach 4.5:1 on every ground it sits
on at rest, and the darkest of those are a modifier key's fill (Ink at 7% over Row) and the
selected row, not the background. The light `#625C6A` gives 4.9:1 and 4.8:1 there; a grey as light
as `#6B6573` still passes on the background but falls to 4.3:1 and 4.1:1 on those two.

Violet stays in the mark and in the few controls listed above. It is not mixed into backgrounds
and it does not colour the keys.

### Type

IBM Plex Sans for text and IBM Plex Mono for keys, section labels, package names and the log, both
bundled with the app (`res/font`, regular, medium and semibold) under the SIL Open Font License,
whose text ships in `assets/licenses/IBM-Plex-OFL.txt`. Bundling rather than downloading keeps the
app without network access ([security.md](./security.md)). Section labels are Plex Mono in small
capitals with wide tracking, so they read as labels and not as one more row.

### Structure

The home screen holds the service status and the key map, and nothing else, so the bindings are
reached at once and the settings around them are not pushed down by however many bindings there
are. Everything else is under the gear at the top right: the two app lists, the two behaviour
switches, the test editor and the log, then About, in that order.

At 600 dp wide and above, which is the Fold's inner screen, the key map runs in two columns, moves
on the left and the rest on the right, and Settings becomes two panes: the list on the left, the
chosen page on the right, with Pass-through apps open from the start. Back from there returns to
the home screen in one step.

### Rows

Rows are separated by a 2 dp gap, each on the row colour, never by rules: a list of twenty
bindings with a rule under each read as too many lines flickering. A group's outer corners are
rounded at 18 dp and the corners between rows at 5 dp, so a group reads as one block. Groups sit
12 dp from the screen edge, and row text 16 dp inside the row.

### Keys

A chord is drawn as the keys pressed, filled rather than outlined: modifiers (`Ctrl`, `Alt`,
`Hyper`) light and in the muted colour, the key itself darker and semibold, since the key is what
tells two chords apart.

### Status

A dot and two lines: Remapping (green), Paused (muted), Connecting (amber, pulsing) and Not running
(red). The row is the same height in every state, so the `Open settings` button that appears when
the service is off never moves the key map. While running, the switch on the row pauses remapping.

### Changing things

- Tapping a binding opens a sheet from the bottom: change the key, change the operation, or remove.
- The round button at the bottom right adds a binding: press the chord, then choose the operation.
  The picker shows which keys already run each operation; choosing one adds this key beside them.
- Removing a binding or an app, a chord taken from another operation, and Reset to defaults happen
  at once and offer Undo in the snackbar, rather than asking first.
- Apps are added from a sheet with a search over names and package names; each app row shows its
  icon and name, with the package name small beneath.

### About

The last item of Settings, with the build under its name. The page shows the mark, the name and
the build (`0.1.0 · e984f34`, a `-dirty` suffix in the waiting colour when the tree had
uncommitted changes), then the source and Caretto's own licence, then the open-source list: the
two bundled fonts first, then the libraries grouped by Maven group, each row with its licence at
the end. A row opens the full text in a sheet; a font's sheet also sets a line in that font.

### Social preview

`docs/images/social-preview.png` (1280 × 640) is the repository's preview image on GitHub: the
mark and the name over a few rows of the key map, on the dark palette. Its source is
`social-preview.html` beside it, which uses the bundled fonts; open it at 1280 × 640 and take a
screenshot to regenerate it.

### README pictures

`docs/images/hero-light.png` and `hero-dark.png` are the README's first picture, one for each
GitHub theme: the home screen, the capture sheet and the operation picker after `Ctrl+Alt+K`, side
by side with rounded corners and a hairline edge on a transparent ground. They are screenshots of
the dedicated emulator (`tools/emu start`) with the system UI demo mode holding the status bar at
10:00, never of the phone, whose app list would show on the settings and app picker screens.
`icon.svg` is the launcher icon, the mark on its `#0F0C17` ground.
