# Research: measured English input with Gboard on a real device

Status: Record. It records measurements made before the repository was published (2026-10-15) and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decisions it led to are [0082](../decisions/0082-ime-editing-model.md).

In [ime-gboard-emulator.md](ime-gboard-emulator.md), Gboard on the emulator had no English language model, so predictive text and autocorrect could not be verified. This document fills that gap on a real device.

## 1. Environment

| Item | Value |
|---|---|
| Device | The author's device (arm64-v8a; the model and Android version were not recorded) |
| Gboard | The one installed on the device (version not stated) |
| Keyboard | English (US) |
| Recording method | Manual input recorded with `tools/ime-probe/scripts/record_manual.sh` |
| Traces | `tools/ime-probe/traces/gboard-device/*.jsonl` and `gboard-device-autocorrect/*.jsonl`, removed from the repository after this research. The traces recorded with the contract settings (section 3.5), `gboard-device-contract/*.jsonl`, are kept because the spec tests replay them |

Gboard's behavior may change between versions.

Under each condition, the following were typed in order, each followed by Enter. Since the input was manual, the traces also include typos and corrections.

1. Press `j` and wait 2 seconds
2. `teh` Space
3. `ok` Space Space
4. `hello` BS BS
5. After `wor`, tap `working` in the suggestion bar
6. `hello` with glide typing

## 2. Conditions

| Name | inputType | Disclosure policy | Personalized learning disabled |
|---|---|---|---|
| `en@typical` | `TEXT` + `AUTO_CORRECT` + `CAP_SENTENCES` + `MULTI_LINE` | none | No |
| `en@text` | `TEXT` | none | Yes |
| `en@text_nosuggest` | `TEXT` + `NO_SUGGESTIONS` | none | Yes |
| `en@typical+history` | Same as `en@typical` | history | No |

## 3. Observations

### 3.1 Composing text is not used

- **`setComposingText` was never called under any condition.** All Latin letters were committed immediately, one at a time, with `commitText`.
- Therefore one-key-at-a-time operations such as vim's `j` work without problems under every condition. The concern in section 2 of [ime-languages.md](ime-languages.md), "cannot send one character at a time immediately", does not arise with this Gboard.
- With `AUTO_CORRECT` and `CAP_SENTENCES`, the start of a sentence was capitalized (`Teh`, `Ok`, `Hello`).

### 3.2 Autocorrect

In the first recording, autocorrect (Auto-correction) was off in Gboard's settings. It was turned on, and the two conditions `en@typical` and `en@typical+history` were recorded again (`tools/ime-probe/traces/gboard-device-autocorrect/`).

What was typed: `j` (wait 2 seconds), `teh` Space, `teh` Space BS, `dont` Space, `ok` Space Space, `wor` → suggestion `working`. Each was followed by Enter.

- **Even with autocorrect on, `setComposingText` was not used.** `j` was committed immediately.
- `teh` was not corrected to `the`. Capitalizing the start of the sentence into `Teh` may have had an effect, but this was not isolated. Therefore undoing a correction (revert by BS) could not be observed either.
- `dont` Space was corrected to `Don't `. The calls were as follows.
  1. Commit `D`, `o`, `n`, `t`, ` ` one character at a time with `commitText`
  2. Replace the word and the space with `replaceText(<start of Dont>, <after the space>, "Don't ")`
  3. Notify the correction with `commitCorrection`
- The results differed by disclosure policy.

| Condition | What `dont` Space sent | Verdict |
|---|---|---|
| `en@typical` (none) | `Dont` + `Don't ` (`replaceText` becomes an insertion outside the range) | Wrong |
| `en@typical+history` | `Dont` + DEL + `'t ` | Correct (becomes `Don't ` in the host's line editing) |

- With the history policy, Gboard computed the correct position from the surrounding text (`replaceText(1, 5, ...)`). The recording tool takes the difference of the committed text, so what was sent was the minimal edit (one DEL and `'t `).

### 3.3 Operations that rewrite committed text

Corrections arrived as edits to committed text, not to composing text.

| Operation | Calls |
|---|---|
| Double-space period | `replaceText(start, end, ". ")` (replaces the one preceding space) |
| Choosing a suggestion (none policy) | `replaceText(3, 6, " working ")`, or `commitText("king ")` |
| Choosing a suggestion (history policy) | `commitText("king ")` |
| Glide typing | `commitText("hello")` (commits the whole word at once) |
| BS outside composition | `sendKeyEvent(KEYCODE_DEL)` |

Results sent to the host:

| Condition | `ok` Space Space | `wor` → `working` |
|---|---|---|
| `en@typical` | `Ok . ` (wrong) | `Working ` (correct) |
| `en@text` | `ok . ` (wrong) | `wor working ` (wrong) |
| `en@text_nosuggest` | `ok . ` (wrong) | `wor working ` (wrong) |
| `en@typical+history` | `Ok ` DEL `. ` (correct) | `Working ` (correct) |

- **With the none policy, Gboard calls `replaceText` with the position it tracks itself.** The editor has cleared the committed text and notified cursor position 0, so the target of the edit is off, producing wrong output. The same phenomenon as on the emulator.
- The calls for choosing a suggestion changed with the conditions. With `en@typical`, only the completion was added with `commitText("king ")`, so the result happened to be correct. With `en@text`, it tried to replace with `replaceText` and got it wrong.
- **With the history policy, every operation produced correct output.** Gboard read `Wor` from the surrounding text and added only the completion with `commitText`.
- Even with `NO_SUGGESTIONS`, the suggestion bar was shown, and neither choosing suggestions nor the double-space feature stopped.

### 3.4 Others

- With `MULTI_LINE`, Enter arrived as `commitText("\n")`. The same as on the emulator.
- Two points to improve were found in the recording tool's history policy. Neither affects the observations.
  - It does not treat `commitText("\n")` as Enter and does not reset the history.
  - It treats `KEYCODE_DEL` as a non-character key, resetting the history and calling `restartInput`. BS is an edit, so the history should be kept.

### 3.5 Checking with settings following the IME contract

Recorded with the settings of [ime-contract.md](../design/ime-contract.md) (`inputType` is only `TYPE_CLASS_TEXT`, the history policy, rejecting `setSelection` other than at the end) (`tools/ime-probe/traces/gboard-device-contract/`). Gboard's autocorrect setting is on. The recording tool's history policy had been aligned with the contract by this point (it does not reset the history on BS, and resets on a committed newline).

**English (`contract.jsonl`)**

| Input | Calls | Sent |
|---|---|---|
| `dont` Space | `replaceText(0, 4, "don't ")`, `commitCorrection` | `dont` DEL `'t ` |
| `teh` Space | No correction | `teh ` |
| `i` Space | `replaceText(0, 1, "I ")`, `commitCorrection` | `i` DEL `I ` |
| `ok` Space Space | `replaceText(2, 3, ". ")` | `ok ` DEL `. ` |
| After `hello world`, slide the space bar left, then `x` | `sendKeyEvent(KEYCODE_DPAD_LEFT)` twice, then only the up of `KEYCODE_SHIFT_LEFT` | `hello world` `ESC [ D` `ESC [ D` `x` |

- **Gboard autocorrected even without `AUTO_CORRECT`.** Whether to autocorrect is decided by Gboard's settings, not by the `inputType` flag. However, every result sent was correct.
- **Cursor movement with the space bar arrived as arrow key events, not `setSelection`.** The same happened while the input history was visible. In a terminal it becomes arrow key sequences and moves the shell's cursor, so it works as intended. Rejecting `setSelection` was not involved.
- An up of `KEYCODE_SHIFT_LEFT` arrived without a corresponding down.

**Japanese 12-key (`ja-12key.jsonl`)**

- With flick input too, the flow of updating composing text with `setComposingText` and committing with `commitText` was the same as QWERTY romaji input.
- When another candidate was chosen while the first candidate (`感じ`) was the composing text, it was committed directly with `commitText("漢字")`.
- The dakuten key (`か` → `が`) arrived as a replacement of the composing text.
- The ◀ key during composition arrived as `setComposingText` with the same content. The cursor position within the composing text is not conveyed to the editor.
- The ◀ ▶ keys when not composing arrived as key events `KEYCODE_DPAD_LEFT` and `KEYCODE_DPAD_RIGHT`.
- With this device's settings, toggle input was disabled, and tapping `あ` twice gave `ああ`.

## 4. Implications for vtfx

1. **Even for English, no measures assuming composing text are needed.** There is no need to tweak `inputType` for immediate sending of each key. `NO_SUGGESTIONS` has no effect either, so there is no reason to set it.
2. **Edits to committed text need to be supported.** Choosing suggestions, the double-space feature, and autocorrect (`dont` → `don't`) are operations used daily in English. With the none policy (option A in [ime-languages.md](ime-languages.md)), all of them produce wrong output.
3. **The history policy (option B) gave the best results.** What is disclosed is only the text the user typed with the IME since the last Enter. This is text the IME itself generated, so there is almost no additional disclosure for privacy.
4. What to decide when adopting option B:
   - The triggers for resetting the history: Enter (including committing `\n`), non-character keys such as arrows, cursor movement due to host output, and so on. BS does not reset.
   - How to handle edits outside the history. The proposal is to reject them and return `false`.
   - How far to assume that deleting with DEL gives the expected result on the host (whether to assume shell line editing).

## 5. Unverified items

- Autocorrect of misspellings like `teh`, and undoing it (revert by BS). `teh` was not corrected in any of the three recordings.
- Voice input
- Other versions of Gboard
- IMEs other than Gboard
