# IME contract

Status: Living document. It describes the current design and is kept in sync with the implementation; design choices and their reasons are recorded in [decisions/](../decisions/README.md).

This document defines, for each `InputConnection` call from the IME, what vtfx sends to the host, what it displays, and what it returns (requirement 5.3). It is based on the structure in [architecture.md](architecture.md) and the measurements in [ime-gboard-emulator.md](../research/ime-gboard-emulator.md) and [ime-gboard-device.md](../research/ime-gboard-device.md).

`ImeEditor` in `vtfx-input` implements the editing model of this document. Points not settled by the text of this document alone (the range of `setComposingRegion`, the handling of keys arriving during composition, and so on) are in [decisions/0004](../decisions/0004-ime-editor-details.md).

## 1. Terms

| Term | Meaning |
|---|---|
| Host | What the terminal is connected to. It receives bytes. |
| Send | Sending bytes to the host through `HostWriter` in `vtfx-gate`. |
| Editing buffer | The text shown to the IME. It is the **input history** followed by the **composing text**. |
| Input history | Text committed through the IME and sent since the last reset. It has already been sent to the host. |
| Composing text | Text in the IME's composing region. It has not been sent yet. |
| Reset | Emptying the input history and notifying the IME to restart input (`restartInput`). |

## 2. Invariants

1. The editing buffer is "input history + composing text", and the cursor is always at the end. There is no text after the cursor. Selections are limited to ranges that end at the cursor (`setSelection` in 6.1).
2. Composing text is not sent. It is sent only when committed.
3. The input history matches what was sent to the host. Whether the host holds it in its line-editing buffer is not guaranteed (see 4.3).
4. The terminal's screen contents (the host's output) do not enter the editing buffer. All the IME can see is the text the user typed with the IME.
5. The length of the input history has a limit. When it exceeds the limit, it is sent and reset at once, even during a batch edit and even with composing text, which is then committed first (the limit is 1024 code points; [decisions/0004](../decisions/0004-ime-editor-details.md)). The IME is not trusted, so the edit buffer must not grow without bound while it holds a batch edit open or moves composing text into the history bit by bit.

## 3. EditorInfo

| Field | Value | Reason |
|---|---|---|
| `inputType` (default) | `TYPE_CLASS_TEXT` only | Japanese and Korean cannot be entered with `VISIBLE_PASSWORD` or `TYPE_NULL`. With `MULTI_LINE`, Enter commits a newline character. With `CAP_SENTENCES`, the start of a command is capitalized. `AUTO_CORRECT` is not set (autocorrect is often harmful in a terminal). The only thing the app can change is switching to direct input in the next row. However, Gboard autocorrects according to its own settings even without this flag (measured). Therefore the contract assumes that sending is correct even when autocorrect happens (4.1). |
| `inputType` (direct input) | `TYPE_CLASS_TEXT` \| `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` \| `TYPE_TEXT_FLAG_NO_SUGGESTIONS` | When the app enables `TerminalSession.directInput`. Makes even IMEs that hold English words as composing text commit one character at a time (requirement 5.3, [decisions/0017](../decisions/0017-ime-scope.md)). Switching commits the composing text and resets. |
| `imeOptions` | `IME_ACTION_NONE` \| `IME_FLAG_NO_FULLSCREEN` \| `IME_FLAG_NO_EXTRACT_UI` \| `IME_FLAG_NO_PERSONALIZED_LEARNING` | Personalized learning is always disabled, because input to a terminal can contain secrets such as passwords (requirement 6.1). The app cannot change it. |
| `initialSelStart`, `initialSelEnd` | The length of the editing buffer | Invariant 1. |
| Initial surrounding text | The input history | Passed with `setInitialSurroundingText` on API 30 and later. On API 29, which lacks it, the IME gets the input history with the queries in 6.2 ([decisions/0039](../decisions/0039-minsdk-29.md)). |

## 4. Rules for sending

### 4.1 Differences in committed text

When an IME operation changes the input history, compare the input history before and after the change, and send the following.

1. Find the common prefix. Surrogate pairs are not split.
2. Send as many DELs (0x7f) as there are code points after the prefix in the history before the change.
3. Send the text after the prefix in the history after the change, with the conversion in 4.2.

Examples:

- When `Dont` is replaced with `Don't ` (measured with Gboard's autocorrect): send `DEL` + `'t `.
- When `ok ` is replaced with `ok. `: send `DEL` + `. `.

The difference is computed all at once **when the outermost batch edit ends**, not per edit. For an operation called outside a batch edit, it is computed when that operation ends. The exception is an edit that takes the input history over its limit, after which the difference is sent at once (invariant 5).

### 4.2 Text conversion

When sending committed text, `sendText` in `vtfx-gate` performs the following conversion.

| Character | What is sent |
|---|---|
| `\n`, `\r\n`, `\r` | CR (0x0d). Reset after sending (5.1). |
| `\t` | As is. |
| Other C0 control characters, DEL, C1 control characters | Not sent. |
| Anything else | Sent as UTF-8. |

`ImeEditor` also does not put control characters that are not sent into the editing buffer (the input history and the composing text). This keeps the input history matching what was sent (invariant 3), so that later edits do not send DELs for characters that never reached the host ([decisions/0004](../decisions/0004-ime-editor-details.md)).

So the rule for dropping control characters has two copies, in `ImeEditor` and in the gate's `OutboundText`. They are kept apart rather than shared, since the gate must not depend on code outside security review ([decisions/0041](../decisions/0041-security-review-by-module.md)) and `vtfx-input` does not depend on the gate. `DroppedControlsAgreementTest` in `vtfx-session` checks that they agree for every UTF-16 code unit. An unpaired surrogate stays in the editing buffer and is sent as U+FFFD; both are one code point, so a later edit sends one DEL for it.

This removal of control characters is not a defense against a malicious IME, because the IME can send Esc and the like with `sendKeyEvent`. Its purpose is to prevent unintended control sequences and to keep the rules for sending text in one place.

### 4.3 Assumptions and constraints

- **It is assumed that one DEL makes the host erase one code point.** This assumption roughly holds for shell line editing (readline, zle, and so on). It may break for combining characters and emoji containing ZWJ.
- Edits to the input history are reflected correctly only while the host is in a line-editing state. In vim's normal mode and the like, DELs and resending become unintended operations. However, such edits happen right after input (choosing a candidate, autocorrect, double-space period), so in practice they are expected to be limited to line editing.

## 5. Resetting the input history

### 5.1 Triggers for a reset

| Trigger | Reason |
|---|---|
| Enter (a key event, a committed newline character, `performEditorAction`) | The line is finished, and later edits are for a new line. |
| Non-character keys (arrows, Esc, Tab, Home, End, combinations with Ctrl or Alt, Forward Delete) | The content of the line on the host and the cursor position can no longer be predicted from the input history. |
| Host output moved the cursor to another line | The prompt has likely changed. Lines are counted as logical lines joined by autowrap. No reset for moves within the same line (echo and so on), moves where the echo of long input merely advanced to the next row by autowrap, or moves between rows joined by autowrap. Reset when the screen scrolls and the cursor's row number does not change, as with a newline on the bottom row, or when the cursor moves to another line and back within one receive (`Terminal.lineChanges`). |
| Switching the alternate screen, resetting the terminal (RIS) | Same as above. Also reset when the cursor's row does not change, or when switching to the alternate screen and back within one receive (`Terminal.lineChanges`). |
| Focus was lost | Input was interrupted. A session receives input only while it is connected to the focused View and the window also has focus. Whether the View or the window loses focus, and also when the View reconnects to another session or leaves the screen, the previous session loses focus. Focus notifications to the host (mode 1004) follow the same judgment. |
| The input history exceeded its limit | Invariant 5. |

BS (`KEYCODE_DEL`) does not reset. BS is treated as an edit that erases the last code point of the input history (6.3).

A reset that host output triggers applies to every edit made after the output was received, also to an edit on another thread that runs before the receive (`TerminalSession.feed`) returns.

### 5.2 Reset procedure

1. When there is composing text, delay the reset until it is gone, because `restartInput` forcibly commits the composition. The exception is the limit of the input history (invariant 5): the composing text is then committed and sent first, as `restartInput` would commit it.
2. Empty the input history.
3. Call `InputMethodManager.restartInput`.

## 6. Contract for each call

### 6.1 Editing

| Call | Effect on the editing buffer | Sent | Return value |
|---|---|---|---|
| `setComposingText(text, pos)` | Replaces the composing text with `text`. If there is no composing text, creates it at the end. | Nothing | `true` |
| `commitText(text, pos)` | Replaces the composing text with `text`, commits it, and adds it to the input history. If there is no composing text, adds it at the end. | The difference (4.1) | `true` |
| `finishComposingText()` | Commits the composing text as is and adds it to the input history. | The difference | `true` |
| `setComposingRegion(start, end)` | If the range is within the editing buffer, makes that range the composing text (reconversion). If it is outside, does nothing. | If the range includes input history, that part goes back to uncommitted, so DELs are sent | `true` if within the range, `false` if outside |
| `deleteSurroundingText(before, after)` | Erases `before` characters before the cursor. The part reaching before the start of the input history is treated as BS. `after` is ignored (there is no text after the cursor). | The difference, and DELs for the part outside | `true` |
| `deleteSurroundingTextInCodePoints(before, after)` | Same as above (in code points). | Same as above | `true` |
| `replaceText(start, end, text, pos, attr)` | If the range is within the editing buffer, replaces it. If it is outside, does nothing. | The difference if within the range | `true` if within the range, `false` if outside |
| `setSelection(start, end)` | Accepts only selections that end at the end (the cursor). When there is composing text, accepts only collapsed selections. The next `commitText` or `setComposingText` replaces the selected text, and other edits clear the selection first ([decisions/0008](../decisions/0008-ime-view-and-selection.md)). | Nothing (the difference when replaced) | `true` if accepted, `false` otherwise |
| `commitCompletion(info)` | Same as `commitText(info.text, 1)`. | The difference | `true` |
| `commitCorrection(info)` | Does nothing. The content of the correction has arrived through the preceding `replaceText` or the like. | Nothing | `true` |
| `beginBatchEdit()` | Increments the batch depth by 1. | Nothing | `true` |
| `endBatchEdit()` | Decrements the batch depth by 1, and sends the difference when it reaches 0. | The difference | `true` if still in a batch |

Notes:

- Even if `newCursorPosition` (`pos`) is not 1, the cursor is placed at the end (invariant 1).
- Only IMEs on API 34 and later call `replaceText`, which was added in API 34. IMEs on API 29 to 33 correct text with `setComposingRegion`, `setSelection`, or `deleteSurroundingText` followed by `commitText`, as the rows above specify. No IME was measured on those versions yet ([decisions/0039](../decisions/0039-minsdk-29.md)).
- Why edits outside the range are rejected: rejecting makes nothing happen, while accepting produces wrong output (`Dont` + `Don't ` with the measured "none" policy, and so on).
- Why only `deleteSurroundingText` allows the outside: a deletion outside can be interpreted as the user's intent of pressing BS, and sending DELs does what was intended. A replacement cannot be reproduced correctly because the content outside is unknown.
- DELs for a deletion outside the range are limited to the limit of the input history (section 10). The IME cannot see text before the input history, so deleting more than that is not the user's intent. Without a limit, an untrusted IME could make the host receive any number of DELs.
- Text committed in the same batch edit after a deletion outside the range is sent again after erasing all of the sent input history with DELs. DELs outside the range reach the text before it only after erasing the sent input history (keeping the common prefix would erase the text that was supposed to be resent).
- Reconverting the input history with `setComposingRegion` means erasing it with DELs once and resending it on commit. It increases traffic, but the result is correct.

### 6.2 Queries

| Call | Returns |
|---|---|
| `getTextBeforeCursor(n, flags)` | The `n` characters before the start of the selection (if the selection is empty, the last `n` characters of the editing buffer) |
| `getTextAfterCursor(n, flags)` | The empty string |
| `getSelectedText(flags)` | The selected text. `null` if the selection is empty |
| `getSurroundingText(before, after, flags)` | The last `before` characters of the editing buffer, and the cursor position at the end. Only IMEs on API 31 and later call it, since it was added in API 31 |
| `getExtractedText(request, flags)` | The whole editing buffer (the fullscreen input UI is disabled) |
| `getCursorCapsMode(reqModes)` | 0 (no automatic capitalization) |

### 6.3 Key events

Keys arriving from the IME through `sendKeyEvent` and physical keys the IME did not consume are handled by the same rules.

| Key | Effect |
|---|---|
| Up without a corresponding down | Ignored (it always arrives through the physical key path). |
| `KEYCODE_DEL` | Sends DEL. If the input history is not empty, erases its last code point. |
| `KEYCODE_ENTER`, `KEYCODE_NUMPAD_ENTER` | Sends CR and resets. |
| Keys that produce a character (`getUnicodeChar` is not 0, and the only modifier is Shift) | Handles the character the same as `commitText`. |
| Anything else | Sends it with `vtfx-gate`'s key encoding and resets. |

When a non-character key is sent through the app's API (an extra key bar and so on) while there is composing text, the composing text is committed and sent first, and then the key is sent.

When the app arms sticky modifiers (`TerminalSession.stickyModifiers`) while there is composing text, the composing text is committed and sent first, without the modifiers, and the input history is reset, so that the modifiers apply only to what is typed after them ([decisions/0033](../decisions/0033-sticky-modifiers-and-composing-text.md)).

### 6.4 Others

| Call | Effect | Return value |
|---|---|---|
| `performEditorAction(action)` | Same as Enter. | `true` |
| `performContextMenuAction(android.R.id.paste)` | Sends the clipboard content as a paste (`sendPaste` in `vtfx-gate`). Commits the composing text, and sends it and any difference not yet sent before the paste, even in the middle of a batch edit, so that the order of sending matches the order of input. Resets after the paste. Pastes through the app's API (`TerminalSession.paste`) are the same. | `true` |
| `performContextMenuAction(other)` | Does nothing. | `false` |
| `requestCursorUpdates(mode)` | Sends `CursorAnchorInfo` immediately for `CURSOR_UPDATE_IMMEDIATE`, and on each later change for `CURSOR_UPDATE_MONITOR` (section 7). | `true` |
| `clearMetaKeyStates(states)` | Does nothing (there is no meta key state). | `true` |
| `commitContent(...)` | Not accepted. | `false` |
| `performPrivateCommand(...)` | Does nothing. | `false` |
| `setImeConsumesInput(value)` | Does nothing. | `true` |
| Handwriting gestures | Not accepted (open). | Failure |
| `closeConnection()` | Commits the composing text if any (`finishComposingText`). Discarding it would lose the user's input, so it commits, like `EditText`. | None |

## 7. Display and notifications to the IME

- **Composing text**: Overlaid with an underline, starting at the terminal's cursor cell. The terminal model is not changed. Text beyond the right edge of the line wraps to the next row. The part beyond the bottom edge of the screen and the part exceeding the limit of combining characters per cell are not shown (the IME is not trusted, so the work per frame is bounded by the screen size).
- **Input history**: vtfx shows nothing. Display is left to the host's echo (nothing is shown for password input without echo and the like).
- **`updateSelection`**: When the selection or the composing range of the editing buffer changes, the IME is notified with `InputMethodManager.updateSelection`. `BaseInputConnection` does not notify automatically, so vtfx calls it.
- **`CursorAnchorInfo`**: Sends the rectangle of the cursor cell as the insertion point, together with the rectangle of each character of the composing text. It is used to position the candidate window.

## 8. Threads

IME calls arrive on the UI thread. `vtfx-compose` operates the editing model of `vtfx-input` through the lock of `TerminalSession` in `vtfx-session`. Sends from an extra key bar and resets caused by host output may come from other threads (section 2.2 of [session-and-threads.md](session-and-threads.md), [decisions/0026](../decisions/0026-session-module.md)). The editing model itself does not assume a particular thread and only requires being called from one thread at a time (section 1.4 of [session-and-threads.md](session-and-threads.md), [decisions/0024](../decisions/0024-thread-conventions.md)). Sending is handed to `HostWriter`, and writing to the host may be done across threads.

## 9. Verification

- Replay the `ic` events of traces recorded with `tools/ime-probe` into the editing model of `vtfx-input` in order.
- The expected sent content is kept, separately from the traces, as a table owned by humans. The policy of the recording tool differs in part from this document (it resets on BS and so on), so the `send` events recorded in traces are not used as expected values.
- The replay tests are gates. The emulator e2e with a real IME is for reports (requirement 7.2).
- The IME is not trusted, so the invariants and what the host receives are checked against sequences of calls with arbitrary arguments (`HostileImeTest` and the fuzzing `ImeFuzzTest`; section 12 of [testing.md](testing.md)).

## 10. Open questions and unverified items

| Item | Contents |
|---|---|
| Limit of the input history | Provisionally decided as 1024 code points ([decisions/0004](../decisions/0004-ime-editor-details.md)). |
| IMEs other than Gboard | The behavior of Samsung Keyboard, SwiftKey, and Simeji. Their place in the matrix of target IMEs is in [decisions/0017](../decisions/0017-ime-scope.md). IMEs that hold English words as composing text are handled by direct input (section 3). |
| Autocorrect of misspellings, and undoing it | Not observed even on a real device (`teh` is not corrected). |
| Handwriting input, voice input | Unverified. Handwriting gestures are not accepted (6.4, [decisions/0017](../decisions/0017-ime-scope.md)). Handwriting input is also a factor in the minSdk decision. |
