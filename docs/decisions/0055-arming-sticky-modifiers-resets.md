# Arming sticky modifiers resets the input history

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 5.4, section 8 of [compose-input.md](../design/compose-input.md), section 6.3 of [ime-contract.md](../design/ime-contract.md), [0033](0033-sticky-modifiers-and-composing-text.md), [0054](0054-resets-during-batch-edits.md), `TerminalSession.stickyModifiers`, `ImeEditor.sendPendingInput`

## Background

[0033](0033-sticky-modifiers-and-composing-text.md) made arming sticky modifiers send the composing text first, without them, and reset the input history only if there was composing text, so that tapping Ctrl did not restart the IME every time. But the IME can still edit the committed text it sees after arming: Gboard corrects the last word on a space or a candidate tap, and reconverts with `setComposingRegion`. The difference of such an edit is DELs and the text sent again, and the session gave the modifiers to the first character of that text. Typing `teh`, arming Ctrl, and taking the correction `the` sent `teh`, two DELs, and Ctrl+H; with reconversion, Ctrl+T. A security review found it; depending on the word, the host could get Ctrl+C or Ctrl+D.

## Decision

This replaces the item of [0033](0033-sticky-modifiers-and-composing-text.md) on arming without composing text.

- Arming sticky modifiers always resets the input history after sending the composing text and the edits not sent yet. The text typed before arming is then out of the IME's reach: a correction of it is rejected as outside the edit buffer, and the modifiers apply only to what is typed after.
- The reset restarts the IME only if the IME saw an edit since the last restart (section 5.2 of ime-contract.md). Tapping Ctrl with nothing typed since then does not restart it; arming after typing a word restarts it once.

## Alternatives considered

- **Apply the modifiers only to text beyond what was sent when they were armed**: the boundary would have to follow the IME's later edits of that text, which [0033](0033-sticky-modifiers-and-composing-text.md) already rejected; a correction that also adds a typed character (`teh` and a space corrected to `the `) would make the boundary ambiguous.
- **Never apply the modifiers to text that follows DELs in the same difference**: a correction is not always sent with DELs (an insertion is not), and text typed after arming inside the same batch would lose the modifier.

## Impact of reversing

Make `ImeEditor.sendPendingInput` reset only if there was composing text, and revise `pendingInputWithoutComposingTextResets` in `ImeEditorTest`, the tests of corrections after arming in `TerminalSessionTest`, section 8 of [compose-input.md](../design/compose-input.md), and section 6.3 of [ime-contract.md](../design/ime-contract.md).
