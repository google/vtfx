# Research: measured IME calls from Gboard (emulator)

Status: Record. It records measurements made before the repository was published (2026-10-15) and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decisions it led to are [0004](../decisions/0004-ime-editor-details.md) and [0017](../decisions/0017-ime-scope.md).

These are the results of recording the `InputConnection` calls that reach a custom editor View, with Gboard on the emulator.

## 1. Environment

| Item | Value |
|---|---|
| Emulator | Pixel 7 AVD, Android 17 (API 37.0), `google_apis_playstore` x86_64 |
| Gboard | 17.2.2.895242737-preload (the preinstalled version). Not signed in to a Google account |
| Keyboards | English (US) QWERTY, Japanese QWERTY (romaji), 한국어 두벌식 |
| Recording tool | [tools/ime-probe](../../tools/ime-probe/) |
| Traces | `tools/ime-probe/traces/gboard-17.2-api37/*.jsonl`. Only the traces that the spec tests replay (`*@contract.jsonl` referenced from `spec/ime/`) are kept in the repository; the others were removed after this research |

Structure of the recording tool:

- It extends `BaseInputConnection` and writes every call to logcat with its arguments, return value, and the buffer state.
- It records committed text converted to the bytes that would be sent to the host (`send` events).
- There are two policies for disclosing surrounding text.
  - `none`: committed text is removed from the buffer immediately, and only uncommitted text is shown to the IME (option A in [ime-languages.md](ime-languages.md)).
  - `history`: committed text is kept until a non-character key such as Enter, and deletions by the IME are converted to DELs (option B).
- For comparison, there is also a mode that uses a standard `EditText`.

The software keyboard was tapped with `adb shell input tap` at 0.4-second intervals. The physical keyboard was injected with `adb shell input keyboard keyevent`.

How to reproduce:

```
cd tools/ime-probe
../../gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/run_scenarios.py traces/<dir> [scenario name pattern ...]
scripts/summarize.py traces/<dir>/<scenario>.jsonl
```

What follows `@` in a scenario name represents the conditions. Those without it are `inputType=TYPE_CLASS_TEXT`, disclosure policy `none`, with the flag that disables personalized learning.

## 2. Bytes sent to the host

| Scenario | Action | Bytes sent |
|---|---|---|
| ja-confirm-enter | `nihongo` Enter Enter | `にほんご\r` |
| ja-convert | `kanji` Space Enter Enter | `感じ\r` |
| ja-backspace | `aiu` BS Enter BS BS | `あい\x7f\x7f` |
| ja-ascii-symbol | `a` `?` Enter | `あ？` |
| ja-hw-convert | Physical keys `kanji` Space Enter Enter | `感じ\r` |
| ko-hangul | `gksrnr` (한국) Space Enter | `한국 \r` |
| ko-jamo-move | `rkr` (각) `k` Enter | `가가\n` |
| ko-enter-mid | `gks` (한) Enter | `한\n` |
| en-vim-j | `j`, wait 2 seconds, `j` | `jj` (each immediately) |
| en-autocorrect | `teh` Space Enter | `teh \r` |
| en-double-space | `ok` Space Space Enter | `ok . \r` |
| en-double-space@history | Same as above | `ok \x7f. \r` |
| ja-convert@visible_password | `kanji` Space Enter Enter | `janji \r\r` (not Japanese input) |
| ja-convert@null | Same as above | `janji \r\r` (not Japanese input) |

## 3. Observations

### 3.1 Japanese

- On every key press, the whole uncommitted string is replaced with `setComposingText`. In the middle of romaji, it is fullwidth Latin letters (`ｋ` → `か` → `かｎ`).
- Converting with Space replaces it with the conversion result (`感じ`) with `setComposingText`.
- Enter while uncommitted only commits with `commitText`, and no Enter key event comes.
- Enter after committing comes as `finishComposingText` followed by down and up of `sendKeyEvent(KEYCODE_ENTER)`.
- BS while uncommitted comes as `setComposingText` with a shortened string. BS when there is no uncommitted string becomes `sendKeyEvent(KEYCODE_DEL)`.
- Symbols such as `?` also go into the uncommitted string (fullwidth `？`).
- The Japanese conversion dictionary was available even without signing in to a Google account.

### 3.2 Korean

- **The uncommitted range was the whole word, not a syllable** (`한` → `한ㄱ` → `한구` → `한국`). It is committed on a separator such as Space.
- Moving the final consonant (adding `ㅏ` to `각` gives `가가`) is handled within the uncommitted range. Committed text is never rewritten.
- **Enter while uncommitted becomes `commitText("한")` followed by `commitText("\n")`.** It arrives as a committed newline character, not a key event.

### 3.3 English

- Every character was committed immediately one at a time with `commitText`, without going through composing text. Therefore one-key-at-a-time operations such as vim's `j` work without problems.
- Autocorrect (`teh` → `the`) never happened. The same was true for a standard `EditText` with `TYPE_TEXT_FLAG_AUTO_CORRECT`, `CAP_SENTENCES`, and `MULTI_LINE`. The suggestion bar showed next-word suggestions.
- **Therefore, it is presumed that Gboard on this emulator has no English language model.**
- The results of verification on a real device are in [ime-gboard-device.md](ime-gboard-device.md). On the real device too, composing text was not used, and characters were committed immediately one at a time.

### 3.4 The double-space period feature

- Gboard called `replaceText(start=2, end=3, ". ")` with absolute positions, without querying the surrounding text.
- With the `none` policy, the editor's buffer was empty and had notified cursor position 0 with `updateSelection`. Even so, Gboard used the position it was tracking itself (the third character).
- As a result, with the `none` policy, the replacement outside the range was treated as an insertion, giving the wrong output `ok . `.
- With the `history` policy, the replacement is converted to one DEL and `. `, which correctly gives `ok. ` in the host's line editing.
- A standard `EditText` received the same `replaceText(2, 3, ". ")`. `EditText` keeps the committed text, so it correctly becomes `ok. `.
- Setting `TYPE_TEXT_FLAG_NO_SUGGESTIONS` did not stop this feature. It did not happen with `visible_password` and `TYPE_NULL`.

### 3.5 Effect of inputType

| inputType | Japanese, Korean | How Latin letters arrive | Enter |
|---|---|---|---|
| `TYPE_CLASS_TEXT` | Usable | `commitText` | Key event |
| `+ NO_SUGGESTIONS` | Usable | `commitText` | Key event |
| `+ AUTO_CORRECT` | Usable | `commitText` | Key event |
| `+ MULTI_LINE` | Usable | `commitText` | **`commitText("\n")`** |
| `VISIBLE_PASSWORD` | **Not usable** (becomes an ASCII layout with a number row) | `commitText` | Key event |
| `TYPE_NULL` | **Not usable** (same as above) | `sendKeyEvent` | Key event |

With `IME_FLAG_NO_PERSONALIZED_LEARNING`, Gboard showed the incognito icon. No difference was seen in the content of the calls.

### 3.6 Physical keyboards

- Physical key events go to Gboard first. Japanese romaji conversion worked with the same calls as the software keyboard.
- Gboard consumed the key down and **returned only the key up to the app with `sendKeyEvent`**. Therefore the editor receives key ups without a corresponding down.

### 3.7 Other calls

- `getSurroundingText(600, 600)` is called when input starts and after commits.
- `requestCursorUpdates(mode=2)` (MONITOR) is called. The recording tool returns `false`, and the candidate window position was not verified.
- For a standard `EditText`, Gboard called `setComposingText` with a `TextAttribute`. For the recording tool's editor, the version without `TextAttribute` was called. The content is the same in both.
- `BaseInputConnection` internally calls `beginBatchEdit` and `endBatchEdit` nested. `depth` in the traces represents this nesting.

## 4. Implications for vtfx

1. **With the policy of disclosing only uncommitted text (option A), Gboard's Japanese and Korean work.** No calls that rewrite committed text were observed for Japanese, Korean, or English input.
2. **However, measures against APIs that edit by absolute position are needed.** Gboard uses the position it tracks itself, not the cursor position notified by the editor. Clearing the buffer with option A makes the coordinates of the IME and the editor diverge. One of the following needs to be decided as the contract.
   - Reject edits that touch the committed range (return `false`). The double-space feature would no longer insert a period, but there is no wrong output.
   - Keep the coordinates continuous. Keep the length of the committed range but do not disclose its content, and convert edits to that range into DELs (option B with the content hidden).
3. **Treat `\n` in committed text as Enter and send CR**, because it arrives as a newline character for Enter during Korean composition and with `MULTI_LINE`. Also, do not set `MULTI_LINE`.
4. **Do not use `VISIBLE_PASSWORD` or `TYPE_NULL`.** Japanese and Korean can no longer be entered. Use `TYPE_CLASS_TEXT` as the base; `NO_SUGGESTIONS` does not affect Japanese and Korean.
5. **Ignore key ups without a corresponding down.** They always arrive on the physical keyboard path.
6. Enter while uncommitted only commits in Japanese and sends no key. This is what Japanese users expect. In Korean, a newline arrives after the commit. This difference between languages is decided by the IME, and the editor only needs to follow the calls it receives faithfully.

## 5. Unverified items

- English autocorrect (it did not happen on a real device either; see [ime-gboard-device.md](ime-gboard-device.md))
- IMEs other than Gboard (Samsung Keyboard, SwiftKey, Simeji; not installed on the emulator)
- The candidate window position (providing `CursorAnchorInfo`)
- Voice input, handwriting input
- Races under fast typing (0.4-second intervals this time)
- Vietnamese Telex
