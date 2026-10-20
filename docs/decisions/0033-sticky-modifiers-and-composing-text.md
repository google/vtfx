# Sticky modifiers and the composing text

- Status: Provisional
- Date: 2026-10-15
- Related: Requirement 5.4, section 8 of [compose-input.md](../design/compose-input.md), [0012](0012-touch-and-selection.md), [ime-contract.md](../design/ime-contract.md) section 6.3, `TerminalSession.stickyModifiers`, `ImeEditor.sendPendingInput`

## Background

`TerminalSession.stickyModifiers` applies Ctrl or Alt from an app's key bar to the next typed key or character ([0012](0012-touch-and-selection.md)). The session applied them to the first character of the next text that the IME editing model sent. But the operations of a key, `sendText`, and a paste first commit and send the composing text, which the user typed before arming the modifier. That text took the modifier instead of the key or text after it. For example, with `cat` composing, Ctrl armed, and Left tapped, the host got Ctrl+C, `at`, and a plain Left; a paste after the composing text `d` sent Ctrl+D (found in a security review).

The session cannot tell the composing text that an operation commits first from the text the user types after arming: both arrive as text from the editing model. Composing text can also be typed after arming, for example with an IME that keeps a whole English word composing until a space; that text must take the modifier.

## Decision

The rules are in section 8 of [compose-input.md](../design/compose-input.md) and section 6.3 of [ime-contract.md](../design/ime-contract.md).

Arming sticky modifiers (setting `stickyModifiers` to modifiers that are not empty, while they differ from the current ones) first commits the composing text and sends it, with the edits not sent yet, without the new modifiers, as a key would before itself (ime-contract.md section 6.3). If there was composing text, the input history is then reset, so that the IME, which still has the composing text, restarts. The modifiers then apply only to what is typed after arming. Clearing them sends nothing.

- The pending input is sent even during a batch edit of the IME, as a paste does, since the app's call is not part of the IME's edit. The reset then waits for the end of the batch.
- Arming without composing text sends only the edits not sent yet, if any, and does not reset, so that tapping Ctrl does not restart the IME every time.
- If modifiers were already armed and the app adds another one, the composing text typed after the first takes the first, as it would have when it was committed.

## Alternatives considered

- **Apply the modifiers only to the key or text of the operation itself, and send the text it commits first without them**: the composing text typed after arming (for example, a letter that Gboard keeps composing) would no longer take the modifier, which breaks Ctrl with such IMEs.
- **Remember how much text existed when the modifiers were armed, and skip the modifier for it**: the IME can edit that text afterwards (replace, reconvert, delete), so the boundary would have to follow the edits; the decision above gets the same result without that state.

## Impact of reversing

Remove the call to `ImeEditor.sendPendingInput` from the setter of `TerminalSession.stickyModifiers` (and the method if unused), and revise `armingStickyModifiersSendsTheComposingTextWithoutThem` and the related tests in `TerminalSessionTest`, section 8 of [compose-input.md](../design/compose-input.md), and section 6.3 of [ime-contract.md](../design/ime-contract.md).
