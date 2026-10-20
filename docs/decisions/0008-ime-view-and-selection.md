# Use a View for the IME connection. Accept selections that end at the cursor

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Sections 6.1, 6.2, and 12 of [ime-contract.md](../design/ime-contract.md), [decisions/0007](0007-compose-session.md), `TerminalInputView`, `ImeEditor.setSelection`, `scripts/e2e_ime.py`

## Background

In the emulator e2e (`scripts/e2e_ime.py`), `en-double-space` failed (expected `ok \x7f. \r`, got `ok . \r`). Gboard called `replaceText(2, 3, ". ")` on the second space, but vtfx's `InputConnection` received `finishComposingText`, `setSelection(2, 3)`, and `commitText(". ")`.

Compose's `PlatformTextInputModifierNode` wraps the `InputConnection` in a `NullableInputConnectionWrapper`, which does not forward `replaceText` (API 34), so the interface's default implementation (select the range, then commit) is used. vtfx rejected selections other than at the end, so the text was appended instead of replaced.

With the default implementation, a `replaceText` outside the range also becomes "`commitText` after a failed selection", and "do nothing if outside the range" in 6.1 of the contract cannot be kept. For example, an autocorrect arriving after a reset becomes an append of the corrected text.

## Decision

1. **Use a `View` placed behind the terminal (`TerminalInputView`) for the IME connection**, which returns the `TerminalInputConnection` without Compose's wrapper, so that all IME calls arrive as the contract expects (section 12 of [ime-contract.md](../design/ime-contract.md)). Its coordinates match the grid, which also simplifies computing `CursorAnchorInfo`.
2. **`setSelection` accepts ranges that end at the cursor (the end of the editing buffer)**, and the next `commitText` or `setComposingText` replaces the selected text (`setSelection` in section 6.1 of the contract). Since the range ends at the cursor, invariant 1 (no text after the cursor) holds.

Because of 1, 2 is no longer used on Gboard's path, but it is kept because it lets vtfx respond correctly to IMEs that replace by selecting and committing.

## Alternatives considered

- **Keep Compose's API and accept `setSelection` with any range**: `replaceText` within the range would become correct, but `replaceText` outside the range could not be rejected (the default implementation ignores the failure of `setSelection` and calls `commitText`).
- **Drop a `commitText` that follows a rejected `setSelection` in the same batch**: A guess that depends on the call order of the default implementation, and it may drop a legitimate `commitText`.

## Impact of reversing

If Compose's wrapper starts forwarding `replaceText`, `TerminalInputView` can go back to `PlatformTextInputModifierNode`. In that case, check with the e2e's `en-double-space` as well, and update section 12 of ime-contract.md.
