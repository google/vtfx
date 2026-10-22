# Text from the app is not shown to the IME

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1, sections 1, 11, and 13 of [ime-contract.md](../design/ime-contract.md), [0004](0004-ime-editor-details.md), [0050](0050-ime-text-of-several-lines-is-a-paste.md), `TerminalSession.sendText`, `ImeEditor.typeText`

## Background

`TerminalSession.sendText` sent the app's text "as if typed": it committed it into the input history, as the IME's commits are. The IME could then read it with `getTextBeforeCursor`, `getSurroundingText`, and `getExtractedText`, and the next connection's initial surrounding text, until the next reset. Requirement 6.1 does not trust the IME, and an app may well send a saved password or a snippet with `sendText`; nothing in its documentation said that the IME would see it. A paste, by contrast, never entered the input history.

`sendText` also only added its text to the history during a batch edit of the IME, so that it reached the host only when the IME ended its batch.

## Decision

- `sendText` commits and sends the composing text, then sends its text, at once even during a batch edit of the IME, and then resets the input history. The IME never sees the app's text.
- The reset restarts the IME only if the IME saw an edit since the last restart (section 5.2 of ime-contract.md), so that a key bar that sends text does not restart the IME on every tap while nothing is typed.
- Each line break of the text is still sent as Enter ([0050](0050-ime-text-of-several-lines-is-a-paste.md)).

## Alternatives considered

- **Keep the text in the input history, so that the IME can correct it, and document that the IME sees it**: correcting the app's text is of little use, and an app would have to know not to send secrets with it.
- **Treat the text as a paste**: commands with a trailing CR from a key bar would no longer run with bracketed paste enabled.

## Impact of reversing

Make `ImeEditor.typeText` add the text to the history as `commitText` does, and revise `typedTextIsNotKeptInTheHistory` and the related tests in `ImeEditorTest` and `TerminalSessionTest`, the KDoc of `TerminalSession.sendText`, sections 1 and 11 of [ime-contract.md](../design/ime-contract.md), and [usage.md](../usage.md).
