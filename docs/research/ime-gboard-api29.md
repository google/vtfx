# Research: Gboard and rendering on API 29 (2026-10)

Status: Record. It records checks made on 2026-10-20 at commit `7e2a60353493e8814b213fddf82a345a3f83bdcb`, after minSdk was lowered to 29 ([decisions/0039](../decisions/0039-minsdk-29.md)), and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md).

Decision 0039 accepts that IMEs on API 29 to 33 correct text without `replaceText`, through paths of the contract that only unit tests checked. This document records how Gboard on Android 10 behaved with the sample app, and the rendering e2e on the same environments.

## 1. Environment

| Item | Emulator | Real device |
|---|---|---|
| System | AVD with the Pixel 7 profile (1080x2400), Android 10 (API 29), `google_apis_playstore` x86 | A real device with Android 10 (API 29), arm64-v8a, 1440x2560 |
| Gboard | 8.0.4.236324529-preload-x86 (the preinstalled version; not updated, since no Google account was signed in) | 18.4.1.985164140-release-arm64-v8a (a public release) |
| Keyboards | English (US) QWERTY, Japanese QWERTY (romaji), 한국어 두벌식 | Same |

## 2. Method

- **Rendering**: `scripts/e2e_render.py --serial SERIAL` on each (section 8 of [testing.md](../design/testing.md)).
- **IME**: the cases of `spec/ime/gboard.toml` typed with the scenarios of `tools/ime-probe/scripts/run_scenarios.py`, through `scripts/e2e_ime.py`, comparing the bytes that reached the sample app's pseudo host. The script could not be used unchanged; it was run through a throwaway wrapper that replaced the following:
  - Gboard on API 29 did not write its keyboards as subtypes into `enabled_input_methods`, and `selected_input_method_subtype` did not change when switching keyboards, on either version. So the check that the keyboards were added and `ensure_language` do not work. The keyboards were added through Gboard's settings screen (by hand on 8.0, whose settings activity cannot be started with `am start`), and the current keyboard was detected from a screenshot.
  - The key coordinates were measured from screenshots of each environment.
- **Rows of section 13 of [device-checks.md](../device-checks.md)**: typed with the same taps, watching the bytes in logcat (`logHostInput`) and the screen.
- No `InputConnection` calls were recorded: `tools/ime-probe/app` has minSdk 33. The results below are the bytes sent, so which calls Gboard made is inferred, not measured.

## 3. Results

### 3.1 Rendering

`RenderConsistencyTest` and `SystemFontsTest`: 14 of 14 passed on both the emulator and the real device. The real device draws with its GPU, so the line cache (`RenderNode`) was exercised on real hardware acceleration on API 29.

### 3.2 IME e2e

| Case | Emulator (Gboard 8.0) | Real device (Gboard 18.4) |
|---|---|---|
| ja-confirm-enter, ja-convert, ja-backspace, ja-hw-convert | pass | pass |
| ja-ascii-symbol | pass | not run: the Japanese layout of 18.4 on this device has no `?` key |
| ko-hangul, ko-jamo-move, ko-enter-mid | pass | pass |
| en-command | pass | pass on the retry (the first run sent nothing; taps right after starting are sometimes lost) |
| en-autocorrect (`teh `) | pass | pass |
| en-double-space (`ok \x7f. \r`) | pass | pass |
| en-vim-j (`jj`) | **fail**: nothing sent | pass |

en-vim-j on Gboard 8.0: Gboard kept the English word as composing text (underlined, with suggestions), so nothing was sent until a space or Enter, as invariant 2 of ime-contract.md specifies. With direct input (the Direct key of the sample app), the same taps sent `jj`. This is the kind of IME that direct input is for ([decisions/0082](../decisions/0082-ime-editing-model.md)); Gboard 18.4 commits each character on the same version of Android, so it depends on the Gboard version, not on API 29.

### 3.3 Corrections without `replaceText`

| Row of device-checks.md | Gboard 8.0 (emulator) | Gboard 18.4 (real device) |
|---|---|---|
| 13.1 Misspell a word and fix it | `helo`, Space, BS: Gboard turned the word back into composing text; `helo ` then 5 DELs (the space, then the 4 characters going back to composing). Choosing `hello` sent `hello`. Result: `hello` | `helo`, Space: autocorrected to `hello `, sent as `\x7f` + `lo `. BS sent `\x7f`; choosing the suggestion `helo` sent `\x7f\x7f` + `o `. Result: `helo ` |
| 13.2 `ok` and two spaces | `ok \x7f. ` (en-double-space) | Same |
| 13.3 Choose a suggestion for part of a word | `hel` (composing), choosing `hello` sent `hello`; the next word started with a space | `hel` (sent as typed), choosing `hello` sent `lo ` |
| 13.4 Reconversion in Japanese | Not offered: BS after committing `にほんご` sent one `\x7f` | Not offered: BS after committing `日本語` sent one `\x7f` |

In every row, the text that reached the host was what the screen showed. Reconversion could not be tried: Gboard offers it on selected text, and the sample app has no way to select the input history for the IME.

### 3.4 Other observations

- Gboard showed its incognito icon in the sample app on both versions, so `IME_FLAG_NO_PERSONALIZED_LEARNING` takes effect on API 29.
- `uiautomator dump`, which is not an accessibility tool, could read the text of the rows on both, as decision 2 of 0039 expects below API 34.
- The app started and took input on both without crashes, with the guards of `setInitialSurroundingText` (API 30) and `getSurroundingText` (API 31) in place.
- On the emulator, `adb shell input text` while Gboard 8.0's Japanese keyboard was active went through Gboard's romaji conversion and the result was garbled. Not investigated; it does not involve the software keyboard.

## 4. Not checked

- Traces of the `InputConnection` calls on API 29 to 33 (needs the minSdk of `tools/ime-probe/app` lowered).
- API 31 and 33.
- Reconversion (13.4).
- The clipboard on API 29 to 32 (decision 3 of 0039): the sample app has no way to send OSC 52.
