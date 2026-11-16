# To do

Work for agents that has been decided on but not started: improvements that no bug report or decision tracks yet. Read it at the start of work, with [review-queue.md](review-queue.md) and the open [bug reports](reviews/README.md). Take an item, do it as usual (decisions in [decisions/](decisions/README.md), the design in the design documents, measurements in reports under `build/reports/`), and remove the item in the commit that finishes it. This file lists only open items; outcomes live in the decisions, the design documents, and [research/](research/).

For each item, write why it is worth doing, what to do, and when it is done. Keep items short, and point to commits by full SHA instead of retelling their changes.

## 1. Items

### 1.3 Copy only the lines that changed for each frame

**Why.** `Terminal.snapshot()` copies every line of the screen for each frame. On a Pixel 9, one snapshot of a full 120 × 40 screen of colored text took 27 µs, against 17 µs for reading alacritty_terminal's grid of the same screen into arrays through JNI. The display reads a snapshot on every frame, also while the host floods output ([0089](decisions/0089-performance.md) topic 2), so the copy is paid at every vsync.

**What.** Give lines a version that changes whenever the line changes, and let a snapshot reuse the `LineSnapshot` of a line whose version has not changed since the previous snapshot: snapshots are immutable, so sharing them is safe. Check the thread conventions of [session-and-threads.md](design/session-and-threads.md) and how `LineCache` in vtfx-compose keys its drawings, which could use the same version. Update the design documents to match.

**Done when.** The cost of a snapshot is measured on a device before and after, for a screen that does not change, one where one line changes, and the scenes of `RenderBenchmark`, with no regression in the scenes.

### 1.4 Draw the rest of the symbols for legacy computing and Powerline Extra

**Why.** vtfx draws the shapes meant to join across cells itself (topic 2 of [0099](decisions/0099-private-use-and-built-in-glyphs.md)), but not yet all of them: the octants (U+1CD00–U+1CDE5) and the other block mosaics of Symbols for Legacy Computing Supplement (U+1CC00–U+1CEBF, Unicode 16), the rest of Symbols for Legacy Computing (U+1FBB0–U+1FBFF: the larger block fractions U+1FBCE–U+1FBEF and the segmented digits), and the Powerline Extra shapes U+E0D2, U+E0D4, U+E0D6, and U+E0D7. Android's system fonts have none of them, so they show as boxes for missing glyphs.

**What.** Add them to `BuiltinGlyphs`, in the families of section 7.3 of [emulation.md](design/emulation.md), from the code charts and the character names; the octants follow a pattern of eight cells that their names give, which `UnicodeData.txt` in `third_party/unicode` could generate. Leave pictures to fonts.

**Done when.** Each range is drawn, with a screenshot golden of every code point and tests of the joins, and section 7.3 lists it.


### 1.5 Type the characters of AltGr on hardware keyboards

**Why.** `KeyEventHandler` takes the character of a key from `KeyEvent.getUnicodeChar` with every Alt bit of the meta state removed, and sends Alt as a modifier (section 7.1 of [compose-input.md](design/compose-input.md)). On layouts that type characters with AltGr, the right Alt, such as German or French, AltGr+Q should type `@`, but sends `ESC q`. Robolectric's virtual key character map has no AltGr, so only a real device with such a layout shows it; the human owner is getting one. It matters only for keys that the IME does not consume and passes to the view.

**What.** On the device, check what AltGr+Q on a German layout and AltGr+2 on a French layout send, with Gboard and with no IME consuming the keys. If it is `ESC` and the base character, treat `META_ALT_RIGHT_ON` as AltGr: take the character with the right Alt kept in the meta state, and when it differs from the one without, send it as text without Alt, as xterm and the platform's text fields do. Left Alt stays a modifier. Update section 7.1 and record the choice in a decision.

**Done when.** A test of `KeyEventHandler` with a key character map that has AltGr covers it, the device sends `@` for AltGr+Q on a German layout, and section 7.1 says how AltGr is told from Alt.

### 1.7 Switch Gboard's language in the IME e2e on real devices

**Why.** `scripts/e2e_ime.py --serial` could not switch Gboard's language in 11 of its 12 cases on the real devices of the releases of 0.7.1 and 0.8.0 (API 36 with Gboard 16.2.4, and API 37 with Gboard 17.2.3, both with gesture navigation in 0.8.0; see `TEST=` of db4a9b9b8ba69f645975fc705a39dfdd83e93094). Only `ja-hw-convert`, which needs no switch, ran, so the recommended check on devices (section 14 of [testing.md](design/testing.md)) tells almost nothing, and each run takes about 40 minutes of retries per device. Screenshots during the runs showed the Japanese keyboard with `あ` left composing after the probe's "a" and Enter, and nothing reaching the host.

**What.** `layouts_for` in `tools/ime-probe/scripts/run_scenarios.py` takes the keys from the touchable region of the keyboard, assuming four rows under a toolbar and a language key (`GLOBE`) in the bottom row, as with three-button navigation. On these devices the bottom row has `?` where `GLOBE` is assumed, the language key is in the strip under the keys where gesture navigation puts the keyboard's buttons, and the touchable region (`(0, 1466, 1080, 2424)` on a 1080×2424 screen) appears to include that strip, which would put the computed rows off. Check the region and the key positions on such a device (`uiautomator dump`, screenshots), and find the keys and the language key from what the device shows instead of fixed fractions where they differ. Keep the emulator's layout working. Update section 8 of [testing.md](design/testing.md) and [device-checks.md](device-checks.md) if the method changes.

**Done when.** `e2e_ime.py --serial` switches the language on a real device with gesture navigation, so that its cases run, and the cases that fail then are handled as bugs or recorded as differences of the device.
