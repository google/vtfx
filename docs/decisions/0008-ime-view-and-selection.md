# Use a View for the IME connection. Accept selections that end at the cursor

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: 6.1 and 6.2 of [ime-contract.md](../design/ime-contract.md), [decisions/0007](0007-compose-session.md), `TerminalInputView`, `ImeEditor.setSelection`, `scripts/e2e_ime.py`

## Background

In the emulator e2e (`scripts/e2e_ime.py`), only `en-double-space` failed (expected `ok \x7f. \r`, got `ok . \r`). Recording the IME calls showed that Gboard called `replaceText(2, 3, ". ")` on the second space, but vtfx's `InputConnection` received `finishComposingText`, `setSelection(2, 3)`, and `commitText(". ")`.

The cause is that Compose's `PlatformTextInputModifierNode` wraps the `InputConnection` in a `NullableInputConnectionWrapper`. This wrapper does not forward `replaceText` (API 34), so the interface's default implementation (select the range, then commit) is used. vtfx rejected selections other than at the end, so the text was appended instead of replaced.

With the default implementation, a `replaceText` outside the range also becomes "`commitText` after a failed selection", and "do nothing if outside the range" in 6.1 of the contract cannot be kept. For example, an autocorrect arriving after a reset becomes an append of the corrected text.

## Decision

1. **Use a `View` placed behind the terminal (`TerminalInputView`) for the IME connection.** `onCreateInputConnection` returns the `TerminalInputConnection` as is. It does not go through Compose's wrapper, so all IME calls arrive as the contract expects. The `View` has the same size as the grid, draws nothing, and receives no touches (the grid's `Canvas` is in front, and a tap moves focus to the `View` and shows the keyboard). The coordinates match the grid, which also simplifies computing `CursorAnchorInfo`. Physical keys are received in `onKeyDown` of the focused `View`.
2. **`setSelection` accepts ranges that end at the cursor (the end of the editing buffer).** The next `commitText` or `setComposingText` replaces the selected text. Other edits clear the selection first. It is not accepted when there is composing text. `getSelectedText` returns the selected text. The selected range is conveyed to the IME with `updateSelection` and `getSurroundingText`. Since the range ends at the cursor, invariant 1 (no text after the cursor) holds.

Because of 1, 2 is no longer used on Gboard's path, but it is kept because it lets vtfx respond correctly to IMEs that replace by selecting and committing.

## Alternatives considered

- **Keep Compose's API and accept `setSelection` with any range**: `replaceText` within the range would become correct, but `replaceText` outside the range could not be rejected (the default implementation ignores the failure of `setSelection` and calls `commitText`).
- **Drop a `commitText` that follows a rejected `setSelection` in the same batch**: A guess that depends on the call order of the default implementation, and it may drop a legitimate `commitText`.

## Impact of reversing

If Compose's wrapper starts forwarding `replaceText`, `TerminalInputView` can go back to `PlatformTextInputModifierNode` (the M4 implementation is not kept: the history before publication was squashed into one commit). In that case, check with the e2e's `en-double-space` as well.
