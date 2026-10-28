# Checks on real devices

These are the checks that the emulator and the JVM cannot cover, with their steps. Agents run them on real devices that they reach over adb, record the results in the related decisions and documents, and handle results different from the expectation as bugs following the procedure in [reviews/README.md](reviews/README.md) ([decisions/0092](decisions/0092-device-checks-by-agents.md)). A check that needs a human, for a judgment of how something looks or feels or for hardware that an agent cannot drive, goes to section 3 of [review-queue.md](review-queue.md) with the evidence that the agent prepared. Section 16 says who runs each check. "What to tell us" at the end of a section lists what the result records, whoever ran it.

This file is the only description of what remains to be checked on real devices: what to check, the steps, and what was already checked on the emulator, on the JVM, and on devices. The design documents link here.

## 1. Preparation

The device is reachable over adb, for example connected to the development machine over USB with USB debugging enabled. Below, the device's serial is `SERIAL` (shown by `adb devices`).

```bash
./gradlew :vtfx-sample:assembleDebug
```

```bash
adb -s SERIAL install -r vtfx-sample/build/outputs/apk/debug/vtfx-sample-debug.apk
```

The sample app is a terminal connected to a fake host (a small shell that echoes input). `help` lists the commands. The extra key bar (Esc, Tab, Ctrl, Alt, arrows, Direct, Find) is shown only while the software keyboard is shown.

The extras that can be passed at startup are as follows. The activity is exported and any app could pass them, so only a debuggable build takes `logHostInput`, and only a debuggable build or the benchmark build (section 5) takes `benchmark` and `throughput` ([decisions/0089](decisions/0089-performance.md)).

| extra | Meaning |
|---|---|
| `--ez logHostInput true` | Writes the bytes sent to the host to logcat (tag `VtfxSampleHost`) in hex on every send |
| `--es locale ja` | The locale for Han glyphs (`TerminalStyle.locale`) |
| `--ez benchmark true` | Runs the rendering benchmark |
| `--ez throughput true` | Runs the throughput benchmark (section 17); `--es workload NAME` measures only that workload, and `--es seconds N` measures each for N seconds instead of 3 |

Watch the bytes that reached the host in another terminal as follows. One line is one send.

```bash
adb -s SERIAL logcat -s VtfxSampleHost:I
```

```bash
adb -s SERIAL shell am start -S -n com.google.vtfx.sample/.MainActivity --ez logHostInput true
```

## 2. IMEs other than Gboard, voice input, handwriting input

Section 13 of [ime-contract.md](design/ime-contract.md). Gboard (English, Japanese, Korean) was checked with the emulator e2e (`scripts/e2e_ime.py`). Rows 2.1 to 2.6 are left for later, with no date set; rows 2.7 and 2.8 are run with the other checks ([decisions/0092](decisions/0092-device-checks-by-agents.md)).

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

Section 7.2 of [compose-input.md](design/compose-input.md). The sample app receives `Ctrl+Shift+K` with `onPreImeKeyEvent` of `Terminal`, before the IME, and shows the toast "Ctrl+Shift+K was taken by the app.".

On the emulator (Pixel 9 Pro AVD, API 36 Google Play image, the preinstalled Gboard in English), keys injected with `adb shell input keyboard keycombination` were caught: the toast appeared and nothing reached the host, while `Ctrl+C` still reached it as `03`. With the earlier sample, which used `Modifier.onPreviewKeyEvent`, Gboard consumed the same keys and resent them through `InputConnection` (`Ctrl+Shift+K` reached the host as `0b`). Check the same with a real physical keyboard, and with Gboard's Japanese, which the emulator did not have.

| # | Action | Expected |
|---|---|---|
| 3.1.1 | With the IME set to Gboard (English), tap the terminal and press `Ctrl+Shift+K` | The toast appears, and nothing appears in logcat |
| 3.1.2 | Then press `Ctrl+C` | `03` appears |
| 3.1.3 | Switch to Gboard's Japanese (romaji input) and repeat 3.1.1 | Same as 3.1.1 |

If `0b` appears in 3.1.1 and no toast appears, the IME received the key before `TerminalInputView.dispatchKeyEventPreIme`. Tell us the IME and the device.

`scripts/device_checks.py` runs these rows with a virtual USB keyboard made through uhid, whose keys take the path of a physical keyboard. On a Pixel XL (Android 10, Gboard 18.4), on 2026-10-26, all three rows were as expected: the toast appeared and nothing reached the host in English and in Japanese (romaji input), and `Ctrl+C` reached it as `03`. On a Pixel 8 (Android 15, Gboard 14.8), the same on 2026-10-26. Gboard's Japanese keyboard switches between kana and Latin input for physical keys on `Shift+Space`, which the script uses to choose the mode.

### 3.2 AltGr

`KeyEventHandler` sends Alt as an ESC prefix and does not distinguish right Alt. Characters typed with AltGr (right Alt) may be sent as ESC and a character instead of the character. If they are, the fix is to prefer the character when the layout produces one with right Alt.

In Settings → System → Keyboard → Physical keyboard, set the layout to German.

| # | Action | Expected | If it is a bug |
|---|---|---|---|
| 3.2.1 | `AltGr+Q` | `40` (`@`) | `1b71` (ESC q) |
| 3.2.2 | `AltGr+7` | `7b` (`{`) | `1b37` or similar |
| 3.2.3 | Left `Alt+Q` | `1b71` (ESC q; the default behavior of Alt) | — |

`scripts/device_checks.py` runs these rows with a virtual USB keyboard whose layout it sets to German. On a Pixel XL (Android 10, Gboard 18.4), on 2026-10-26, all three rows were as expected, but not through `KeyEventHandler`: with Latin input, Gboard takes the keys of AltGr and commits the characters of the layout itself (`@`, `{`, and `€` for `AltGr+E`). With Japanese input, Gboard takes them too, and nothing reaches the host before it commits. With a US layout, `AltGr+Q` reaches the app and is sent as `1b71`, as Alt is. From Android 14, the layout of a physical keyboard is chosen on other screens of Settings, which the script does not drive yet, so 3.2 was not run on the Pixel 8. What remains is an IME that passes the keys of AltGr to the app, where `KeyEventHandler` would send ESC and the key without AltGr; no such IME was available.

## 4. TalkBack

Section 11 of [compose-input.md](design/compose-input.md). On the emulator, only the node bounds and the tree structure were checked (because input from `adb` does not go through explore by touch).

The row nodes are marked as sensitive data. TalkBack declares `isAccessibilityTool`, so it should be able to read them. If it cannot, the mark may be the cause.

Enable Settings → Accessibility → TalkBack, open the sample app, and type `unicode` (with TalkBack enabled, touching a keyboard key once and lifting the finger types it).

| # | Action | Expected |
|---|---|---|
| 4.1 | Swipe right to move through the items in order | Each row of the terminal is read, one row at a time from the top |
| 4.2 | Put a finger on a row of the terminal and trace it (explore by touch) | The touched row is read, and the focus frame overlaps that row |
| 4.3 | Double-tap while the terminal has focus | The keyboard appears |
| 4.4 | Type `ls` with the keyboard and press Enter | `ls` is shown in the terminal, followed by a newline |

## 5. Rendering performance

[decisions/0089](decisions/0089-performance.md). On the emulator (software GPU), only the UI thread's drawing time (`DRAW_DURATION`) was a target. On a real device, also check whether the time of the whole frame (`TOTAL_DURATION`) stays around 16 ms.

Run it with the following steps, or with `scripts/benchmark.py --serial SERIAL`, which writes the results to `build/reports/performance.md`. The benchmark runs in the benchmark build, a release build that is not debuggable, as an app that users install is, but profileable by the shell ([decisions/0089](decisions/0089-performance.md)): a debuggable build runs much slower.

```bash
./gradlew :vtfx-sample:assembleBenchmark
```

```bash
adb -s SERIAL install -r vtfx-sample/build/outputs/apk/benchmark/vtfx-sample-benchmark.apk
```

```bash
adb -s SERIAL logcat -c
```

```bash
adb -s SERIAL shell am start -S -W -n com.google.vtfx.sample/.MainActivity --ez benchmark true
```

Wait about a minute and a half, then read the results.

```bash
adb -s SERIAL logcat -d -s VtfxRenderBenchmark:I -v raw
```

One line of JSON appears per `scenario` (`full`: the whole screen changes every time, `scroll`: colored log lines flow one at a time, `flood`: colored log lines fed without a break for 5 s, [decisions/0089](decisions/0089-performance.md)).

| Value | Target |
|---|---|
| `drawP50` of `full` (UI thread drawing) | ≤ 25 ms |
| `drawP50` of `scroll` | ≤ 10 ms |
| `p50` (whole frame) | Around 16 ms (on a 60 Hz device). Not a target; look at it for reference |
| `fps` and `janky` of `flood` (frames per second, and the share of frames longer than the refresh interval), and `mbps` against `headlessMbps` | On a Pixel XL, the reference device: `fps` ≥ 80 % of the refresh rate, `janky` ≤ 35 %, `mbps` ≥ 75 % of `headlessMbps`. On other devices, for reference |

What to tell us: the device model and Android version, and the JSON lines output.

Checked on a Pixel XL (Android 10, 60 Hz) on 2026-10-25, in two runs: `drawP50` of `full` 6.6 and 6.9 ms, of `scroll` 1.7 and 1.6 ms, with `p50` of the whole frame 14.5 and 15.3 ms in `full`; `flood` at 52.4 and 51.2 frames per second, with 27.5 % and 29.7 % janky frames, and 85 % and 88 % of the throughput without a display. Printing a large file over SSH, below, was not checked.

On 2026-10-26, with `scripts/benchmark.py`'s rendering scenes: a Galaxy A06 (Android 15, 60 Hz, a low-end phone) drew `full` in 7.2 ms and `scroll` in 3.5 ms (`drawP50`), and `flood` at 46.8 frames per second with 49.6 % janky frames and 87 % of the throughput without a display; a Galaxy S24 (Android 16, 120 Hz, Exynos 2400) drew `full` in 4.4 ms and `scroll` in 1.3 ms, and `flood` at 119.6 frames per second with no janky frames and 89 % of the throughput; a Pixel Tablet (Android 16, 60 Hz) drew `full` in 4.7 ms and `scroll` in 2.6 ms, and `flood` at 59.8 frames per second with no janky frames and 94 % of the throughput. The drawing targets hold on all three; the targets of `flood` are set for the Pixel XL only, and the Galaxy A06 is below them.

The `flood` scene measures the frames while output comes without a break, but not input. Also have the host print a large file without a break (for example `cat` of a file of several MB over SSH), and tell us whether the display keeps scrolling smoothly and keys typed meanwhile reach the host without a long delay ([decisions/0084](decisions/0084-session-and-threads.md)).

## 6. Han glyphs

Section 7.3 of [emulation.md](design/emulation.md). On the emulator (Android 17, system locale en-US), it was confirmed that with each of `--es locale ja` and `--es locale zh-CN`, the second line of the `unicode` command (骨 直 角 説) is drawn with Japanese and Chinese glyphs respectively.

What remains is whether it switches in the same way on devices where the manufacturer installed its own CJK font (Samsung and others). On a device whose system language is not Japanese, check the following.

```bash
adb -s SERIAL shell am start -S -n com.google.vtfx.sample/.MainActivity --es locale ja
```

Type `unicode` and check that the second line has Japanese glyphs (the bottom left of 直 connects into an L shape, the central vertical stroke of 角 does not pierce through the bottom, and the top right of 説 is shaped like ハ). Then start with `--es locale zh-CN` and check that the glyphs change.

On a Galaxy S24 (Android 16, One UI, which ships Samsung's own CJK font `SECCJK`), on 2026-10-26, the second line had the Japanese glyphs above with `--es locale ja`, and the Chinese ones with `--es locale zh-CN` (the bottom left of 直 without the L shape, the central stroke of 角 through the bottom), judged by an agent from screenshots.

## 7. Mouse and touchpad

Section 3 of [compose-input.md](design/compose-input.md). Only Compose's injected mouse events on Robolectric were checked (`TerminalMouseTest`). Use a Chromebook, Samsung DeX, or a tablet with a USB or Bluetooth mouse, start the sample app with `--ez logHostInput true`, and type `links`, then `help` a few times so that there is scrollback.

| # | Action | Expected |
|---|---|---|
| 7.1 | Turn the wheel up, then down | The scrollback scrolls 3 lines a notch, and back; nothing appears in logcat |
| 7.2 | Two-finger scroll on a touchpad, and a high-resolution wheel if there is one | Scrolls smoothly in proportion, not in jumps or not at all |
| 7.3 | Drag over some text; double-click a word; triple-click a line | The text, the word, the line are highlighted; no menu appears |
| 7.4 | Shift+click further on | The selection extends to there; after a double click (7.3), to the end of the word clicked |
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
| 7.15 | Double-click a word and keep the button down on the second click, then drag over the words before and after it; do the same with a triple click over lines | The selection grows and shrinks by whole words, then by whole lines; the word or line clicked stays selected |

What to tell us: the device, the Android version, the mouse or touchpad, and each row's result. If the right button acts as Back, or the pointer does not change, tell us too.

The drag of 7.3 was run on a Pixel 9 (Android 15) on 2026-10-27, with a virtual USB mouse made with the `hid` shell command that moved a few pixels every 20 ms for 3 seconds: the selection followed the pointer over four rows. Before `3bed452827a78e9bf046198b74b6725a801b93c3`, the same drag selected a single cell and stopped, because a fine mouse reports several moves within a cell. The other rows were not run.

Rows 7.4 and 7.15 were run on a Pixel 9 (Android 16) on 2026-10-27 at `1277e9a818515f743f3d1a632dd607af31cc9464`, with the same kind of virtual USB mouse and a virtual USB keyboard for Shift, on the first line of the sample app. With the button held after a double click on "sample.", the selection was "sample. Type 'help'" with the pointer within "help", and "vtfx sample." with the pointer on "v". After a triple click, moving down a row selected both rows whole. Shift+click on the "t" of "the" after a double click on "sample." selected through "the". The long press of 7.15 remains unchecked: one injected with `input motionevent` selected nothing, not even the word, which points at the injection rather than at vtfx. The first double click right after the virtual keyboard was added selected nothing, and the next one selected; Gboard was switching to its mode for physical keyboards, which probably resized the terminal, and a resize drops the selection (section 4 of [compose-input.md](design/compose-input.md)). This was not investigated further.

## 8. Images

Section 9 of [emulation.md](design/emulation.md). Images of the kitty graphics protocol were checked on the JVM, with recordings of real programs and screenshot tests, and drawn on the emulator by the rendering e2e (section 8 of [testing.md](design/testing.md)), whose `DeviceLimitsTest` also drew the largest image on a Pixel XL (Android 10). Real programs were not run on a device. The sample app has no SSH, so it can only replay recordings; checking real programs needs an app that embeds vtfx and connects over SSH to a host with `kitten icat`, timg, and yazi.

| # | Action | Expected |
|---|---|---|
| 8.1 | In the sample app, `replay timg`, `replay icat`, `replay icat-placeholder`, and `replay fastfetch` | Each image appears where the recording put it, in proportion to the cells. The recordings chose sizes for cells of 10 × 20 pixels, so an image without a size in cells looks smaller in larger cells |
| 8.2 | Over SSH, show an image with `kitten icat`, then with timg, then browse images with yazi | The images appear at the size and place the programs ask for |
| 8.3 | Print lines until the image scrolls off, then scroll back through the scrollback | The image moves with the text and comes back with it |
| 8.4 | Show an image the size of the screen, then make it scroll (print lines below it) | The frame time stays near that of text (section 5) |
| 8.5 | Show a few large images, and read the memory with `adb -s SERIAL shell dumpsys meminfo PACKAGE` | Note the memory: the display keeps bitmaps besides the decoded pixels, which `TerminalLimits.maxImageBytes` bounds |
| 8.6 | Run `scripts/e2e_render.py --serial SERIAL`, whose `DeviceLimitsTest` draws an image of 64 MiB (4096 × 4096 pixels), the largest one image may be, with `maxImageBytes` of 256 MiB | `theLargestImageIsDrawn` and `anImageLargerThanAndroidDrawsIsNotDrawn` pass in `build/reports/e2e-render.md`. If one fails, note its message and the device's largest texture size |

What to tell us: the device, the Android version, the programs, and each row's result, with the meminfo output of 8.5 and the report of 8.6.

`scripts/e2e_render.py` ran 8.6 on 2026-10-26, with `RenderConsistencyTest` and `SystemFontsTest`: all 20 tests passed on a Pixel 5 (Android 11, 12, and 13), a Pixel 8 (Android 15), a Pixel 10 (Android 16), a Pixel Tablet (Android 16), a Galaxy S24 (Android 16, Exynos 2400 with an AMD-based GPU), and a Galaxy A06 (Android 15), so the largest image was drawn, and a larger one refused, with the GPUs of Qualcomm, ARM, Imagination, and AMD.

## 9. The input history on switching IMEs

5.1 of [ime-contract.md](design/ime-contract.md). `TerminalInputConnection.closeConnection` does not reset the input history, so if the window keeps the focus while the IME changes, the new IME may get the text typed with the previous one through `EditorInfo.setInitialSurroundingText` (API 30 and later). Whether that happens depends on how the system restarts the input, which the emulator e2e does not cover. If it happens, the input history should also be reset on `closeConnection`.

Install a second IME (such as SwiftKey) besides Gboard, start the sample app, and tap the terminal.

| # | Action | Expected |
|---|---|---|
| 9.1 | Type `hello wor` with Gboard without pressing Enter, then switch to the second IME with Gboard's switch key | The second IME's suggestions do not continue `wor` (for example, it does not suggest `world`) |
| 9.2 | Repeat 9.1, switching from the system picker (the keyboard icon in the navigation bar, or the notification) | Same as 9.1 |

If the new IME suggests words that continue the text typed before switching, it got the input history. Tell us the IMEs, the device, and the Android version.

## 10. Links taken by a protected activity

Sections 5.3 and 6 of [gate-policy.md](design/gate-policy.md). `AndroidUrlOpener.open` converts `SecurityException` from `startActivity` into `OpenUrlResult.Refused` and lets other exceptions propagate. The unit test only makes a context throw `SecurityException`; what is not known is whether the system throws that exception, or another one, when the activity that takes the link requires a permission. Robolectric does not model it.

This needs a second app with an activity that takes the link's URL (an app that verified the link's domain, so that no chooser appears) and declares `android:permission`, which the sample app does not hold.

| # | Action | Expected |
|---|---|---|
| 10.1 | In the sample app, type a URL of the second app's domain and press Enter (the fake host echoes it, and the URL becomes a link), tap it, and confirm | The sample app shows "The system did not let the app for this link open." and does not crash |

If the sample app crashes, tell us the exception in logcat.

## 11. Imported fonts

[fonts.md](design/fonts.md). Sanitized fonts have no hinting, and sanitizing a CJK font takes seconds. On the x86_64 emulator, face 0 of Noto Serif CJK (65535 glyphs, CFF) took about 10 s in a release build and about 60 s in a debuggable one, where Android's runtime optimizes much less, measured before later optimizations of the verifier and the conversion. `SystemFontsTest` (an instrumented test) logs the time of every font of the device. On the emulator (Android 17) and a Pixel XL (Android 10), it sanitized the first face of each font in `/system/fonts`, the Noto CJK collections included, within the limits on the work of a whole face (section 9 of [fonts.md](design/fonts.md)); the Pixel XL took 4.6 s for the first face of NotoSansCJK-Regular.ttc (20 MB). What remains is whether the CJK fonts that other devices ship and that users import stay within those limits, and the time and memory on a low-end phone.

Use a low-end phone and a device with a low density, and copy the font files to the device (for example to Downloads).

| # | Action | Expected |
|---|---|---|
| 11.1 | In the sample app, type `font import` and pick a Latin monospace font | The app reports the faces and `Imported face N in M ms`, and the terminal uses the font |
| 11.2 | Type `font import` and pick Noto Sans Mono CJK JP (CFF outlines, which sanitizing converts) | Same as 11.1. Note `M`. While it runs, read the memory with `adb -s SERIAL shell dumpsys meminfo com.google.vtfx.sample` |
| 11.3 | Type `unicode` and `colors`, and look at the text at small font sizes | Text without hinting looks acceptable |

What to tell us: the device, the Android version, the density, whether the build was debug or release, the reported times, and the memory.

`SystemFontsTest` (in the debug build) sanitized the first face of every font in `/system/fonts` within the limits on the devices of section 8, on 2026-10-26. The first face of NotoSerifCJK-Regular.ttc (26 MB, CFF) took 4.4 to 5.3 s on a Pixel 5, 6.8 s on a Pixel 8, 7.9 s on a Pixel 10, 9.4 s on a Pixel Tablet, 6.4 s on a Galaxy S24, and 13.7 s on a Galaxy A06; that of NotoSansCJK-Regular.ttc 2.5 to 3.0 s on a Pixel 5. Samsung's own CJK font, SECCJK-Regular.ttc (19 MB, CFF), took 2.4 s on the Galaxy S24 and 6.3 s on the Galaxy A06, and drew its own glyphs. 11.1 to 11.3, the memory, and the release build were not run.

## 12. Copying all the text

Section 4 of [compose-input.md](design/compose-input.md). Copying all the text copies at most the newest 10,000 lines, cut to 256 Ki characters (`TerminalDisplay.selectedText`), so that the system clipboard can take it. Robolectric's clipboard has no Binder limit; on the emulator and a Pixel XL (Android 10), `DeviceLimitsTest` in `vtfx-compose` checks that a copy of all the text, and a copy of a selection of a large display whose cells the host filled with combining marks, reach the system clipboard without `TransactionTooLargeException`. What is not known is how long the UI freezes on other devices.

| # | Action | Expected |
|---|---|---|
| 12.1 | In the sample app, fill the scrollback with long lines (for example, paste a long text of tens of KB and press Enter, several times; the fake host echoes it) | The scrollback holds many long lines |
| 12.2 | Long-press, choose Select all, then Copy | The system's clipboard preview shows the start of the text, and the app does not crash. Note how long the UI freezes |
| 12.3 | Paste into another app | The text is the newest part of the terminal, at most 256 Ki characters |
| 12.4 | Make the host fill the screen with characters that each carry many combining marks (for example, paste such a text of many lines), select the whole screen, and copy | The app does not crash. Note how long the UI freezes |

If the app crashes, tell us the exception in logcat.

`DeviceLimitsTest` copied all the text, and a large selection of combining marks, to the system clipboard without a crash on the devices of section 8, on 2026-10-26. How long the UI freezes was not measured.

## 13. IMEs on API 29 to 33

[decisions/0090](decisions/0090-distribution.md), sections 3 and 6.1 of [ime-contract.md](design/ime-contract.md). IMEs on Android 10 to 13 do not call `replaceText`, and on API 29 get no initial surrounding text, so they correct text through the other paths of the contract. On Android 10, rows 13.1 to 13.3 were checked with Gboard 8.0 on the emulator and Gboard 18.4 on a real device, and 13.4 could not be tried ([research/ime-gboard-api29.md](research/ime-gboard-api29.md)); Android 11 to 13 were not checked. Traces of the calls were not recorded on API 29: `tools/ime-probe/app` runs there, but `run_scenarios.py` switches keyboards by `selected_input_method_subtype`, which Gboard on API 29 does not update, and taps the key coordinates of the Pixel 7 AVD, so recording the `spec/ime` scenarios there and proposing them in `spec-proposals/` needs those adapted.

Use Gboard on devices with Android 10, 11, 12, and 13, and start the sample app with logHostInput enabled (section 1).

| # | Action | Expected |
|---|---|---|
| 13.1 | In English, misspell a word and fix it with a suggestion | `7f` for each character of the wrong word, then the corrected word |
| 13.2 | In English, type `ok` and two spaces | `6f`, `6b`, `20`, then `7f` and `2e20` (`. `) |
| 13.3 | In English, type part of a word and choose a suggestion | The rest of the word (or `7f`s and the whole word) |
| 13.4 | In Japanese, type and commit `にほんご`, then reconvert it (select it and choose a candidate, if Gboard offers it) | `7f`s for the committed text, then the new text on commit |

What to tell us: the Gboard version, the device and Android version, and each row's logcat output.

`scripts/e2e_ime.py --serial` now runs the `spec/ime` scenarios on devices, finding Gboard's keys from the keyboard's touchable region and its language from what reaches the host, on 2026-10-26:

| Device | Gboard | Result |
|---|---|---|
| Pixel XL, Android 10 | 18.4 | All 12 scenarios that run on devices passed |
| Pixel 8, Android 15 | 14.8 | 11 passed; `en-autocorrect` differs from `spec/ime/gboard.toml` because this Gboard corrects `teh`: it sent `teh`, two `7f`s, and `he `, which is right for the correction |
| Pixel 5, Android 11 | 9.4, as the system shipped it | Korean and the hardware key scenario passed. This Gboard holds English words as composing text (`en-vim-j` sent nothing before a commit, so per-key apps need direct input), commits a corrected word whole, adds no period on two spaces, and kept its Japanese keyboard at 12 keys, which the scenarios do not type |
| Pixel 5, Android 12 and 13 | 10.8 and 11.7, as the system shipped them | Not run: the keys are not where `layouts_for` puts them |

So rows 13.1 to 13.3 are checked on Android 10 and 15, and on Android 11 with an old Gboard; Android 12 and 13, a current Gboard on Android 11 to 13, and 13.4 remain. The phones' layouts of `layouts_for` do not fit Gboard 15.1 on a Pixel 10, or a tablet.

## 14. Searching

Section 13 of [compose-input.md](design/compose-input.md), [decisions/0085](decisions/0085-compose-interaction.md). A search runs on the main thread: a new search lays out the text of the whole scrollback, a change of the text searches the text kept, and each redraw after output searches the new lines. These were timed only on the development machine's JVM, not on ART.

| # | Action | Expected |
|---|---|---|
| 14.1 | In the sample app, fill the scrollback (for example, paste a long text of many lines and press Enter, several times; the fake host echoes it), then open the search bar with Find on the key bar or Ctrl+Shift+F | The search field takes the focus and the keyboard |
| 14.2 | Type a word that appears many times, one letter at a time | The matches are highlighted as each letter is typed, the count follows, and the terminal does not freeze noticeably. Note any pause |
| 14.3 | Tap ↑ and ↓ several times | The terminal scrolls to each match, the current one in orange |
| 14.4 | While searching, paste more text so that output flows | The count grows, the display stays where it is, and typing in the field stays smooth |
| 14.5 | Close the search bar | The highlights go |

What to tell us: the device and Android version, and any pause or freeze, with roughly how long it lasted.

## 15. Restarts of the IME

5.1 and 5.2 of [ime-contract.md](design/ime-contract.md), [decisions/0082](decisions/0082-ime-editing-model.md). The input history is reset, and the IME restarts, when a key of a hardware keyboard commits the composing text, after Enter, and when a sticky modifier is armed. On the emulator (Gboard 18.4), Japanese and Korean composing text that the key bar's → committed reached the host once, and Gboard dropped its composition, as Japanese did on a Pixel XL (Android 10, Gboard 18.4). Gboard took the keys injected with `adb shell input` itself while composing, so no hardware key committed composing text there; and keys injected right after Enter were lost before `bd194b21ccce3eeab2fb8efb92b4d3db145bdbec`, when the echo of Enter restarted the input a second time.

Connect a USB or Bluetooth keyboard, set the IME to Gboard, and start the sample app with logHostInput enabled (section 1).

| # | Action | Expected |
|---|---|---|
| 15.1 | In Japanese, type `にほんご` on the software keyboard without committing, then press an arrow key of the hardware keyboard | The composing text reaches the host once, and Gboard drops its composition (no composing text is left on the keyboard) |
| 15.2 | Type `echo hi` on the hardware keyboard, press Enter, and right away type `ls` and Enter | Every key reaches the host: `echo hi`, `0d`, `ls`, `0d` |
| 15.3 | Type a word on the software keyboard, then tap Ctrl on the key bar and a letter | Ctrl and the letter are sent. Note whether the restart of the keyboard when Ctrl is armed feels disruptive |

What to tell us: the Gboard version, the device and Android version, and each row's logcat output.

`scripts/device_checks.py` runs 15.2 with a virtual USB keyboard. On a Pixel XL (Android 10, Gboard 18.4) and a Pixel 8 (Android 15, Gboard 14.8), on 2026-10-26, every key reached the host. 15.1 needs taps on the software keyboard, which the script does not make yet.

## 16. Who runs each check

[decisions/0092](decisions/0092-device-checks-by-agents.md). Agents run every check that they can drive, and give a human only the judgment. A check moves to a human, in section 3 of [review-queue.md](review-queue.md), once the agent has the evidence for the judgment, or finds that it cannot drive the hardware. The column "Human" says what is expected to stay with a human.

| Section | Run by | Human |
|---|---|---|
| 2.1 to 2.6: IMEs other than Gboard, voice input | Nobody for now; left for later, with no date set | — |
| 2.7, 2.8: handwriting input | Agents | — |
| 3: physical keyboards | Agents, with a virtual keyboard made through `uhid` (`scripts/device_checks.py`) | — |
| 4: TalkBack | Agents, with touches from a virtual touchscreen | Only if explore by touch cannot be driven |
| 5: rendering performance | Agents | Whether the scrolling of a large output looks smooth, judged from a recording |
| 6: Han glyphs | Agents, judging screenshots | Only if the agent cannot tell the glyphs apart |
| 7: mouse and touchpad | Agents, with a virtual mouse and touchpad | Whether the scrolling of 7.2 feels proportional |
| 8: images | Agents | The images, judged from screenshots |
| 9: the input history on switching IMEs | Agents, with an IME written for the check | — |
| 10: links taken by a protected activity | Agents, with an app written for the check | — |
| 11: imported fonts | Agents | Whether text without hinting looks acceptable (11.3), judged from screenshots |
| 12: copying all the text | Agents | — |
| 13: IMEs on API 29 to 33 | Agents (`scripts/e2e_ime.py --serial`); the keys of more Gboard versions remain to be found | — |
| 14: searching | Agents | — |
| 15: restarts of the IME | Agents, with a virtual keyboard (`scripts/device_checks.py` for 15.2) | Whether the restart when Ctrl is armed feels disruptive (15.3) |
| 17: throughput | Agents | — |
| 18: colors set by the host | Agents, judging screenshots | — |
| 19: selection handles | Agents, with touches from a virtual touchscreen | Whether dragging a handle feels right (19.2, 19.3), judged from a recording |

## 17. Throughput

Topic 4 of [decisions/0089](decisions/0089-performance.md). The sample app's `ThroughputBenchmark` feeds the workloads of the JVM's `TerminalBenchmark` (section 1 of 0089) to a session that no display shows, in writes of 4 KiB into 120 × 40, and logs MB/s of each. The workloads are files that the JVM writes and adb pushes to the device, so that the device measures the same bytes. `scripts/benchmark.py --serial SERIAL` runs it after the rendering scenes of section 5, once after installing the app, when ART interprets it and compiles hot code with its JIT, and once after compiling the whole app ahead of time with the `speed` filter, and writes both to `build/reports/performance.md`. By hand, with the benchmark build installed as in section 5:

```bash
./gradlew :vtfx-core:benchmarkWorkloads
```

The app makes the directory of the workloads on a first run, which measures nothing: it cannot see files that adb pushes into a directory that adb made, which belongs to the shell.

```bash
adb -s SERIAL shell am start -S -W -n com.google.vtfx.sample/.MainActivity --ez throughput true --es workload none
```

```bash
adb -s SERIAL push vtfx-core/build/benchmark-workloads/. /sdcard/Android/data/com.google.vtfx.sample/files/workloads/
```

```bash
adb -s SERIAL logcat -c
```

```bash
adb -s SERIAL shell am start -S -W -n com.google.vtfx.sample/.MainActivity --ez throughput true
```

After about 20 seconds, one line of JSON per workload, and `{"done":true}` at the end, appear in the following.

```bash
adb -s SERIAL logcat -d -s VtfxThroughput:I -v raw
```

To compare with code compiled ahead of time, run `adb -s SERIAL shell cmd package compile -m speed -f com.google.vtfx.sample` and start it again; `cmd package compile --reset com.google.vtfx.sample` returns to the state after install. To profile, measure one workload for longer (`--es workload ascii --es seconds 30`) and record the app meanwhile with `adb -s SERIAL shell simpleperf record --app com.google.vtfx.sample --duration 10 -o /data/local/tmp/perf.data`, which the benchmark build allows since it is profileable by the shell; `simpleperf report -i /data/local/tmp/perf.data --comms vtfx-benchmark- --sort symbol` on the device lists the hot methods of the benchmark's thread.

| Value (after install) | Target on a Pixel 9 |
|---|---|
| ascii | ≥ 90 MB/s |
| sgr | ≥ 65 MB/s |
| cjk | ≥ 70 MB/s |
| recordings | ≥ 85 MB/s |

On other devices, and compiled with `speed`, the results are for reference.

Checked on a Pixel 9 (Android 16) on 2026-10-27 with `scripts/benchmark.py`: after install, ascii 100.2, sgr 72.6, cjk 76.2, and recordings 96.8 MB/s; compiled with `speed`, 99.4, 67.7, 68.8, and 93.9 MB/s. The optimizations that got there are in [research/performance-art-2026-10.md](research/performance-art-2026-10.md).

## 18. Colors set by the host

Section 12 of [emulation.md](design/emulation.md), [decisions/0096](decisions/0096-host-colors.md). The display keeps the drawing of lines by content (section 5), which colors that the host sets do not change, so lines drawn before must be drawn again in the new colors. The JVM checks the cache (`LineCacheTest`) and the screenshots (`TerminalScreenshotTest.hostColors`), and the rendering e2e checks the drawing with hardware acceleration (`RenderConsistencyTest.linesDrawnBeforeTheHostSetsColors`, section 8 of [testing.md](design/testing.md)).

| # | Action | Expected |
|---|---|---|
| 18.1 | In the sample app, `colors`, then `theme` (Solarized Dark, set with OSC 10 to 12 and OSC 4 as theme tools do) | The background, the padding, the text, the cursor, and the 16 colors of the lines drawn before change to the theme's; the colors 16 to 255 stay |
| 18.2 | `theme reset` | Everything returns to the app's colors |
| 18.3 | Run `scripts/e2e_render.py --serial SERIAL` | `linesDrawnBeforeTheHostSetsColors` passes |

What to tell us: the device, the Android version, screenshots of 18.1 and 18.2, and the report of 18.3.

Checked on a Pixel 9 (Android 15) on 2026-10-28: 18.1 and 18.2 as expected, judged from screenshots; `scripts/e2e_render.py` passed all 21 tests. With the cache not cleared on a change of the colors, `linesDrawnBeforeTheHostSetsColors` failed on the same device (the cell of color 1 stayed `#CD0000` instead of `#00FF00`).

## 19. Selection handles

Section 14 of [compose-input.md](design/compose-input.md), [decisions/0097](decisions/0097-selection-handles.md). The handles are popups that move with the finger. Robolectric checks what a drag selects (`SelectionHandlesTest`) and the look (`TerminalScreenshotTest.selectionHandles`), but its windows do not move; on a device, the popup's window moves while the finger drags it, and the finger's position must still follow the finger.

| # | Action | Expected |
|---|---|---|
| 19.1 | In the sample app, long-press a word and lift the finger | The word is selected, the menu shows, and a teardrop handle hangs below each end of the word |
| 19.2 | Drag the end handle slowly to the right over several cells, then back, and lift it | The end follows the handle's tip cell by cell without jumping, with a light vibration at each cell; the menu goes during the drag and comes back over the row when the handle is lifted |
| 19.3 | Drag the start handle to the previous row and lift it | The start follows to the previous row; the handle does not pass the end handle when dragged beyond it |
| 19.4 | Long-press the first column of the last row and drag the end handle | Both handles hang below the terminal, over the key bar, and the end handle can be dragged; the start handle, at the window's left edge, hangs right of its tip |
| 19.5 | With handles shown, tap the terminal, and select again with a mouse | The handles go on the tap, and a mouse selection shows none |

What to tell us: the device and Android version, a recording of 19.2 to 19.4, and whether the handle stayed under the finger.

Run on a Pixel 9 (Android 14) on 2026-10-28 with touches injected with `adb shell input touchscreen motionevent`: 19.1 to 19.5 as expected, judged from screenshots, except the mouse of 19.5, which was not attached. Each finger move of three cells moved the end three cells while the popup moved with the handle. Before the menu's rectangle covered the handles, the menu of a long press on the first row went below the row, over the handles; and before handles turned at the window's edge, the start handle of a selection from the first column was outside the window. A human dragged the handles by finger on a Pixel 9 Pro Fold on 2026-10-28, and they worked.
