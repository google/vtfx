# Steps for checking on real devices

These are steps for humans to check on real devices the items in section 3 of [review-queue.md](review-queue.md). Tell an agent, for each item, "what was used and what happened", and the agent records the result in the related decisions and documents and removes the item from review-queue.md. Results different from the expectation are handled as bugs following the procedure in [reviews/README.md](reviews/README.md).

What was checked on the emulator is written in each section. Only what cannot be checked on the emulator remains here.

## 1. Preparation

Connect the device to the development machine over USB and enable USB debugging. Below, the device's serial is `SERIAL` (shown by `adb devices`).

```bash
./gradlew :vtfx-sample:assembleDebug
```

```bash
adb -s SERIAL install -r vtfx-sample/build/outputs/apk/debug/vtfx-sample-debug.apk
```

The sample app is a terminal connected to a fake host (a small shell that echoes input). `help` lists the commands. The extra key bar (Esc, Tab, Ctrl, Alt, arrows, Direct) is shown only while the software keyboard is shown.

The extras that can be passed at startup are as follows.

| extra | Meaning |
|---|---|
| `--ez logHostInput true` | Writes the bytes sent to the host to logcat (tag `VtfxSampleHost`) in hex on every send |
| `--es locale ja` | The locale for Han glyphs (`TerminalStyle.locale`) |
| `--ez benchmark true` | Runs the rendering benchmark |

Watch the bytes that reached the host in another terminal as follows. One line is one send.

```bash
adb -s SERIAL logcat -s VtfxSampleHost:I
```

```bash
adb -s SERIAL shell am start -S -n com.google.vtfx.sample/.MainActivity --ez logHostInput true
```

## 2. IMEs other than Gboard, voice input, handwriting input

[decisions/0017](decisions/0017-ime-scope.md). Gboard (English, Japanese, Korean) was checked with the emulator e2e (`scripts/e2e_ime.py`).

The targets are three: Samsung Keyboard, SwiftKey, and Simeji. For each, start the app with logHostInput enabled, and tap the terminal to show the keyboard.

| # | Action | Expected |
|---|---|---|
| 2.1 | Type `ls` with the English layout (Direct off) | `6c` and `73` appear **one line per key press**. If nothing appears until pressing space or Enter, the IME is holding English words as composing text |
| 2.2 | Turn on Direct on the extra key bar and repeat 2.1 | One line per key |
| 2.3 | Type `にほんご` in Japanese input, convert, and commit (Direct off) | On commit, the UTF-8 of the converted text appears all at once. The composing characters are shown underlined at the terminal's cursor position and are not sent before committing |
| 2.4 | In the middle of 2.3, erase one character with BS | Nothing is sent during composition. BS after commit produces `7f` |
| 2.5 | Autocorrect (misspell in English and fix it with a suggestion) | `7f` for the wrong word, and the corrected word |

Then check the following with Gboard.

| # | Action | Expected |
|---|---|---|
| 2.6 | Speak a short English sentence with voice input (Gboard's microphone) | The recognized sentence appears when committed. The composing display overlays the terminal, and nothing is sent before committing |
| 2.7 | Write characters with Gboard's handwriting keyboard | The written characters appear when committed |
| 2.8 | Write characters directly on the terminal with a stylus (handwriting input on Android 14 and later). If it works, also try the erase gesture (a horizontal line over characters) | No expectation has been decided. Record whether handwriting starts and whether the written characters are sent. Handwriting gestures are not accepted (6.4 of [ime-contract.md](design/ime-contract.md)), so the erase gesture should send nothing and the app should not crash |

What to tell us: the IME's name and version, the device and Android version, and the result of each row (whether as expected, and if not, the logcat output).

## 3. Physical keyboards

Connect a USB or Bluetooth keyboard.

### 3.1 Intercepting app shortcuts

"Key interception" in [decisions/0019](decisions/0019-extensibility.md) and [decisions/0032](decisions/0032-keys-and-layout.md). The sample app receives `Ctrl+Shift+K` with `onPreImeKeyEvent` of `Terminal`, before the IME, and shows the toast "Ctrl+Shift+K was taken by the app.".

On the emulator (Pixel 9 Pro AVD, API 36 Google Play image, the preinstalled Gboard in English), keys injected with `adb shell input keyboard keycombination` were caught: the toast appeared and nothing reached the host, while `Ctrl+C` still reached it as `03`. With the earlier sample, which used `Modifier.onPreviewKeyEvent`, Gboard consumed the same keys and resent them through `InputConnection` (`Ctrl+Shift+K` reached the host as `0b`). Check the same with a real physical keyboard, and with Gboard's Japanese, which the emulator did not have.

| # | Action | Expected |
|---|---|---|
| 3.1.1 | With the IME set to Gboard (English), tap the terminal and press `Ctrl+Shift+K` | The toast appears, and nothing appears in logcat |
| 3.1.2 | Then press `Ctrl+C` | `03` appears |
| 3.1.3 | Switch to Gboard's Japanese (romaji input) and repeat 3.1.1 | Same as 3.1.1 |

If `0b` appears in 3.1.1 and no toast appears, the IME received the key before `TerminalInputView.dispatchKeyEventPreIme`. Tell us the IME and the device.

### 3.2 AltGr

`KeyEventHandler` sends Alt as an ESC prefix. Characters typed with AltGr (right Alt) may be sent as ESC and a character instead of the character.

In Settings → System → Keyboard → Physical keyboard, set the layout to German.

| # | Action | Expected | If it is a bug |
|---|---|---|---|
| 3.2.1 | `AltGr+Q` | `40` (`@`) | `1b71` (ESC q) |
| 3.2.2 | `AltGr+7` | `7b` (`{`) | `1b37` or similar |
| 3.2.3 | Left `Alt+Q` | `1b71` (ESC q; the default behavior of Alt) | — |

## 4. TalkBack

[decisions/0015](decisions/0015-accessibility.md). On the emulator, only the node bounds and the tree structure were checked (because input from `adb` does not go through explore by touch).

The row nodes are marked as sensitive data. TalkBack declares `isAccessibilityTool`, so it should be able to read them. If it cannot, the mark may be the cause.

Enable Settings → Accessibility → TalkBack, open the sample app, and type `unicode` (with TalkBack enabled, touching a keyboard key once and lifting the finger types it).

| # | Action | Expected |
|---|---|---|
| 4.1 | Swipe right to move through the items in order | Each row of the terminal is read, one row at a time from the top |
| 4.2 | Put a finger on a row of the terminal and trace it (explore by touch) | The touched row is read, and the focus frame overlaps that row |
| 4.3 | Double-tap while the terminal has focus | The keyboard appears |
| 4.4 | Type `ls` with the keyboard and press Enter | `ls` is shown in the terminal, followed by a newline |

## 5. Rendering performance

[decisions/0014](decisions/0014-performance.md). On the emulator (software GPU), only the UI thread's drawing time (`DRAW_DURATION`) was a target. On a real device, also check whether the time of the whole frame (`TOTAL_DURATION`) stays around 16 ms.

Run it with the following steps, or with `scripts/benchmark.py --serial SERIAL`, which writes the results to `build/reports/performance.md`.

```bash
adb -s SERIAL logcat -c
```

```bash
adb -s SERIAL shell am start -S -W -n com.google.vtfx.sample/.MainActivity --ez benchmark true
```

Wait about a minute, then read the results.

```bash
adb -s SERIAL logcat -d -s VtfxRenderBenchmark:I -v raw
```

One line of JSON appears per `scenario` (`full`: the whole screen changes every time, `scroll`: colored log lines flow one at a time).

| Value | Target |
|---|---|
| `drawP50` of `full` (UI thread drawing) | ≤ 25 ms |
| `drawP50` of `scroll` | ≤ 10 ms |
| `p50` (whole frame) | Around 16 ms (on a 60 Hz device). Not a target; look at it for reference |

What to tell us: the device model and Android version, and the JSON lines output.

## 6. Han glyphs

[decisions/0011](decisions/0011-unicode.md). On the emulator (Android 17, system locale en-US), it was confirmed that with each of `--es locale ja` and `--es locale zh-CN`, the second line of the `unicode` command (骨 直 角 説) is drawn with Japanese and Chinese glyphs respectively.

What remains is whether it switches in the same way on devices where the manufacturer installed its own CJK font (Samsung and others). On a device whose system language is not Japanese, check the following.

```bash
adb -s SERIAL shell am start -S -n com.google.vtfx.sample/.MainActivity --es locale ja
```

Type `unicode` and check that the second line has Japanese glyphs (the bottom left of 直 connects into an L shape, the central vertical stroke of 角 does not pierce through the bottom, and the top right of 説 is shaped like ハ). Then start with `--es locale zh-CN` and check that the glyphs change.

## 7. Mouse and touchpad

[decisions/0031](decisions/0031-desktop-pointer-and-terminal-state.md). Only Compose's injected mouse events on Robolectric were checked (`TerminalMouseTest`). Use a Chromebook, Samsung DeX, or a tablet with a USB or Bluetooth mouse, start the sample app with `--ez logHostInput true`, and type `links`, then `help` a few times so that there is scrollback.

| # | Action | Expected |
|---|---|---|
| 7.1 | Turn the wheel up, then down | The scrollback scrolls 3 lines a notch, and back; nothing appears in logcat |
| 7.2 | Two-finger scroll on a touchpad, and a high-resolution wheel if there is one | Scrolls smoothly in proportion, not in jumps or not at all |
| 7.3 | Drag over some text; double-click a word; triple-click a line | The text, the word, the line are highlighted; no menu appears |
| 7.4 | Shift+click further on | The selection extends to there |
| 7.5 | Right-click on the selection | The menu (Copy, Paste, Select all) appears at the pointer; Copy copies the selection |
| 7.6 | Ctrl+Shift+C after selecting | The selection is copied (the system's clipboard preview shows it) |
| 7.7 | Hover over a link, then over plain text | The pointer is a hand over the link and an I-beam elsewhere |
| 7.8 | Click a link | The confirmation dialog appears |
| 7.9 | Type `mouse`, then click, drag, turn the wheel, right-click, and middle-click | logcat shows SGR reports (`1b5b3c...`): presses (`M`), releases (`m`), drags (`32;`), wheel (`64;`, `65;`) |
| 7.10 | With `mouse` on, Shift+drag | Text is selected, and nothing appears in logcat |
| 7.11 | Type `mouse off` | Clicks no longer appear in logcat |
| 7.12 | With a hardware keyboard attached, click the terminal | The terminal gets focus; note whether the software keyboard appears |
| 7.13 | Tap and drag on a touchpad (without pressing it down) | Note whether it acts as a mouse (7.3) or as a finger (a swipe scrolls) |
| 7.14 | Type `mouse` again, Shift+turn the wheel up, then (without Shift) hover, drag, click, and turn the wheel down until the display is back at the bottom; then click | While scrolled back: the pointer is an I-beam, the drag selects, the wheel scrolls, and nothing appears in logcat. Back at the bottom: the wheel and the click appear in logcat again |

What to tell us: the device, the Android version, the mouse or touchpad, and each row's result. If the right button acts as Back, or the pointer does not change, tell us too.
