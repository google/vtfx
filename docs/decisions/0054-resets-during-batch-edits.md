# Resets during batch edits, and commits that the IME did not make

- Status: Provisional
- Date: 2026-10-22
- Related: Sections 5, 6.3, 6.4, and 11 of [ime-contract.md](../design/ime-contract.md), [0004](0004-ime-editor-details.md), [0026](0026-session-module.md), [0033](0033-sticky-modifiers-and-composing-text.md), `ImeEditor.requestReset`, `ImeEditor.onKeyDown`

## Background

A security review found two gaps in when the editing model resets its input history.

- A reset during a batch edit of the IME (host output that moved the cursor to another line, a paste, a key other than BS) only marked the reset as pending until the batch ended, and kept the input history. The edits that the IME made later in the same batch were then accepted against the old history, and their difference sent DELs that erased the host's new prompt, the pasted text, or the text at the cursor after an arrow key. Outside a batch edit, the same happened while there was composing text, since the reset waited for it.
- A key that committed the composing text without the IME making it (a hardware key that the IME did not consume, or a key or text from the app's API) committed and sent the text, but the IME was told nothing, or only an updated selection, which IMEs do not use to drop their composition. The IME went on composing the text that the host had already received, and committed it again later (`か` composing, `x` from a key bar, then `な` sent `かxかな`).

## Decision

1. **A reset empties the input history at once**, also during a batch edit and while there is composing text. The edits not sent yet are sent first, even during a batch edit, as a paste already does: they were made before the reset. The composing text stays, since the IME still has it. Only `restartInput` waits, until the batch edit ends and the composing text is gone, since restarting would commit the composition. The edits that the IME makes after the reset apply to the empty history: a replacement of the old text is rejected as outside the edit buffer, and a deletion of it is sent as BS (section 6.1). This applies to every reset, including the one after arming sticky modifiers, whose reset [0033](0033-sticky-modifiers-and-composing-text.md) made wait for the end of the batch.
2. **A key that commits composing text resets.** When a character key or BS commits the composing text, from the IME, a hardware keyboard, or the app (`sendKey`), the input history is reset after the key is sent, so that the IME restarts and drops its composition. This applies to `TerminalSession.sendText` too, which resets after sending in any case ([0056](0056-app-text-not-shown-to-the-ime.md)). A character key without composing text does not reset, so that the IME can still correct what was typed one key at a time.

## Alternatives considered

- **Keep the pending reset and drop the edits that follow it in the batch**: the IME would think its edits were made, and the edit buffer that it sees would no longer be what vtfx holds.
- **Drop the edits not sent yet at the reset instead of sending them**: the user's committed text would be lost; sending it is what would have happened had the batch ended just before the reset.
- **Tell the IME with `updateSelection` only, from every path that commits composing text**: Gboard keeps its composition after an `updateSelection` that moves the composing region, as the review's tests showed; only a restart reliably drops it. It also needs a path from the session's API to the view's connection.

## Impact of reversing

Item 1: make `ImeEditor.requestReset` only mark the reset again and revise the tests of resets during batch edits in `ImeEditorTest` and `TerminalSessionTest`, and `HostileIme.Host`, whose check of the host's line relies on the history being empty after a reset. Item 2: remove the reset from the key path of `ImeEditor.onKeyDown`, and revise its tests and section 6.3 of [ime-contract.md](../design/ime-contract.md).
