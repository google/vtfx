# IME scope (matrix, disclosure, rewriting, immediate sending of Latin letters)

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.3 and section 8 (the matrix of target IMEs, the range of surrounding text disclosed to the IME, handling requests to rewrite committed text, a means to send Latin letters one at a time and compatibility with Japanese input), [ime-contract.md](../design/ime-contract.md), [research/ime-languages.md](../research/ime-languages.md), [research/ime-gboard-device.md](../research/ime-gboard-device.md)

## Background

Section 8 of the requirements asks for four IME items to be decided. Immediate sending of Latin letters relied on Gboard's measured behavior (it commits Latin letters one at a time with `commitText`), so with IMEs that hold English words as composing text, apps that act on each key, like vim's normal mode, could not be used.

## Decision

- **Matrix of target IMEs**: Gboard's Japanese (QWERTY) and English are required, and checked by the spec tests (`spec/ime`); Gboard's Korean (두벌식) is for reports; Gboard's voice and handwriting input and other IMEs (Samsung Keyboard, SwiftKey, Simeji) are unverified, and checked by humans on real devices. The table is in section 13 of [ime-contract.md](../design/ime-contract.md).
- **Range disclosed to the IME**: Option B in section 6 of [research/ime-languages.md](../research/ime-languages.md). The IME is shown only the input history (up to its limit) and the composing text, not the screen contents (invariant 4 of the contract). Personalized learning is always disabled (section 3 of the contract).
- **Rewriting committed text**: Rewrites within the input history (autocorrect, choosing candidates, double-space period) are accepted, and the difference is sent as DELs and resending (4.1 of the contract). Deletions before the input history become DELs up to the limit of the input history (6.1 of the contract). Rewrites take effect as expected on the host only while the host is in a line-editing state (4.3 of the contract).
- **Immediate sending of Latin letters**: By default the IME is told `TYPE_CLASS_TEXT`, with which Gboard commits English one letter at a time and Japanese and Korean conversion work. For IMEs that hold English words as composing text, the app can switch to **direct input** (`TerminalSession.directInput`), which tells the IME `VISIBLE_PASSWORD` and `NO_SUGGESTIONS`. Many IMEs then offer no Japanese or Korean input, so the user switches depending on the situation. See section 13 of the contract.

## Alternatives considered

- **Always use `VISIBLE_PASSWORD`**: Japanese and Korean cannot be entered (section 3 of the contract).
- **Automatically switch to direct input on the alternate screen (vim, less, and so on)**: Japanese could no longer be entered in vim's insert mode. Whether on the alternate screen does not match whether in normal mode.
- **Send English composing text one character at a time**: It breaks invariant 2, that composing text is not sent, and cannot be distinguished from Japanese composing text.

## Impact of reversing

- To drop `directInput`, remove `TerminalSession.directInput` and the parameter of `TerminalInputConnection.configure` (a public API change).
- To change the matrix, change the targets of `spec/ime` (owned by humans), the languages in `scripts/e2e_ime.py`, and section 13 of ime-contract.md.
