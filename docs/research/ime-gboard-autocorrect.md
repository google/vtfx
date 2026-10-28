# Research: Gboard's autocorrect and `inputType` (2026-10)

Status: Record. It records measurements made on 2026-10-28 and is not kept up to date. The current contract is in [ime-contract.md](../design/ime-contract.md), and the decision it led to is topic 2 of [0082](../decisions/0082-ime-editing-model.md).

Typing `unicode` and Enter on Gboard's English keyboard ran `Unicode` on the host. This document records how Gboard made that correction, and which `inputType` stops it while keeping Japanese input.

## 1. Environment

| Item | Value |
|---|---|
| Device | Pixel 9, Android 14 (API 34), 1080x2424 |
| Gboard | 14.2.09.629370537-preload-arm64-v8a (the preinstalled version), autocorrect on in its settings |
| Keyboards | English (US) QWERTY, Japanese QWERTY (romaji) |
| Recording | `tools/ime-probe` with the contract settings (history policy, personalized learning disabled, rejecting selection changes), the `inputType` varied with the probe's `inputType` extra; keys tapped with `adb shell input tap` at coordinates measured from a screenshot |

A Lenovo Tab P12 (TB370FU, Android 15, Gboard 14.4.07) was tried first. It did not correct `unicode` with `TYPE_CLASS_TEXT`, so it was not used further; whether its Gboard had autocorrect on was not checked.

The traces were not added to `tools/ime-probe/traces/`, which humans own.

## 2. How the correction arrives

With `TYPE_CLASS_TEXT`, the letters of `unicode` were committed one at a time with `commitText` and sent at once. Enter then made these calls:

1. `beginBatchEdit`
2. `deleteSurroundingText(7, 0)`
3. `commitCorrection` (`"" -> "Unicode"`)
4. `commitText("Unicode")`
5. `endBatchEdit`
6. `sendKeyEvent(KEYCODE_ENTER)` down and up, outside the batch edit

The editing model sent the difference at the end of the batch edit, then Enter: `unicode`, seven DELs, `Unicode`, and CR. The correction is a rewrite of the input history, as in section 3.2 of [ime-gboard-device.md](ime-gboard-device.md), but through `deleteSurroundingText` and `commitText` rather than `replaceText`, and the Enter that triggered it comes after the batch edit ends.

## 3. Results by `inputType`

Each row typed the keys shown and then Enter. The cell is what reached the host (`⌫` is DEL, `⏎` is CR).

| `inputType` | `unicode` | `dont` Space | `i` Space | `teh` Space | `ok` Space Space |
|---|---|---|---|---|---|
| `TEXT` | `unicode⌫⌫⌫⌫⌫⌫⌫Unicode⏎` | `dont⌫'t ⏎` | `i⌫I ⏎` | `teh⌫⌫he ⏎` | `ok ⌫. ⏎` |
| `TEXT` + `NO_SUGGESTIONS` | `unicode⏎` | `dont ⏎` | `i ⏎` | `teh ⏎` | `ok ⌫. ⏎` |
| `TEXT` + `WEB_EDIT_TEXT` | `unicode⏎` | `dont ⏎` | `i ⏎` | `teh ⏎` | `ok ⌫. ⏎` |

`unicode` alone, with other variations and flags:

| `inputType` | Corrected |
|---|---|
| `TEXT` + `URI`, `EMAIL_ADDRESS`, `FILTER`, `VISIBLE_PASSWORD` | No |
| `TEXT` + `AUTO_COMPLETE`, `PERSON_NAME`, `POSTAL_ADDRESS` | Yes |

- **`NO_SUGGESTIONS` stopped every correction measured**, on Enter and on Space. Section 3.3 of [ime-gboard-device.md](ime-gboard-device.md) found that it did not stop choosing suggestions or the double-space period; whether it stopped autocorrect was not measured then, since autocorrect was off in that Gboard's settings during those recordings.
- **The double-space period still happens with `NO_SUGGESTIONS`**: `replaceText(2, 3, ". ")`, sent as DEL and `. `. It also happened with `URI`, `EMAIL_ADDRESS`, `FILTER`, `PERSON_NAME`, `POSTAL_ADDRESS`, and `AUTO_COMPLETE` (`ok` Space Space Enter sent `ok ⌫. ⏎` with each). On the emulator, it did not happen with `VISIBLE_PASSWORD` and `TYPE_NULL` (section 3.4 of [ime-gboard-emulator.md](ime-gboard-emulator.md)).
- **The suggestion bar still offers words with `NO_SUGGESTIONS`**: after `wor`, it showed `wor`, `WOR`, and `work`, as with `TYPE_CLASS_TEXT` alone.
- `TYPE_CLASS_TEXT` corrected `unicode` on both runs.

## 4. Japanese with `NO_SUGGESTIONS`

With `TEXT` + `NO_SUGGESTIONS`, the globe key switched to the Japanese keyboard. `nihongo` was composed with `setComposingText` (`ｎ`, `に`, ..., `にほんご`), Space converted it to `日本語`, and Enter committed it with `commitText("日本語")` and sent it; the next Enter sent CR. The candidate bar showed conversion candidates while composing. Korean was not checked on this device; on the emulator, `NO_SUGGESTIONS` kept Korean input (section 3.5 of [ime-gboard-emulator.md](ime-gboard-emulator.md)).

## 5. Unverified items

- Other Gboard versions, and the newer Gboard of the Pixel XL and emulator checks (18.4)
- Korean on a real device with `NO_SUGGESTIONS`
- Other IMEs (Samsung Keyboard, SwiftKey, Simeji)
