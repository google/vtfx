# IME scope (matrix, disclosure, rewriting, immediate sending of Latin letters)

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.3 and section 8 (the matrix of target IMEs, the range of surrounding text disclosed to the IME, handling requests to rewrite committed text, a means to send Latin letters one at a time and compatibility with Japanese input), [ime-contract.md](../design/ime-contract.md), [research/ime-languages.md](../research/ime-languages.md), [research/ime-gboard-device.md](../research/ime-gboard-device.md)

## Background

The four IME items in section 8 of the requirements were decided individually in the IME contract and research documents between M2 and M5, but had not been gathered as a decision. Also, immediate sending of Latin letters relied on Gboard's measured behavior (it commits Latin letters one at a time with `commitText`), so with IMEs that hold English words as composing text, apps that act on each key, like vim's normal mode, cannot be used.

## Decision

### Matrix of target IMEs

| IME | Languages | Position | How it is checked |
|---|---|---|---|
| Gboard | Japanese (QWERTY), English | Required. Checked by the spec tests (`spec/ime`) | Trace replay (gate), emulator e2e (report) |
| Gboard | Korean (두벌식) | For reports | Trace replay, emulator e2e |
| Gboard | Voice input, handwriting input | Unverified. Handwriting gestures are not accepted (6.4 of the contract) | Humans check on real devices |
| Samsung Keyboard, SwiftKey, Simeji | Japanese, English | Unverified. Working is the goal but not guaranteed | Humans check on real devices ([device-checks.md](../device-checks.md)) |

### Range disclosed to the IME

Option B in section 6 of [research/ime-languages.md](../research/ime-languages.md). The IME is shown only the text committed through the IME and sent since the last reset (the input history, up to 1024 code points) and the composing text, not the screen contents (invariant 4 of the contract). Personalized learning is always disabled (section 3 of the contract).

### Rewriting committed text

Rewrites within the input history (autocorrect, choosing candidates, double-space period) are accepted, and the difference between before and after is sent as DELs and resending (4.1 of the contract). Deletions before the input history become DELs up to the limit of the input history (6.1 of the contract, [0004](0004-ime-editor-details.md)). Rewrites take effect as expected on the host only while the host is in a line-editing state (4.3 of the contract).

### Immediate sending of Latin letters

- By default (`TerminalSession.directInput` is `false`), the IME is told `TYPE_CLASS_TEXT`. Gboard's English commits one character at a time, so each key reaches the host (measured). Japanese and Korean conversion also work as is.
- For IMEs that hold English words as composing text, there is **direct input**, which the app can switch (`TerminalSession.directInput = true`). The IME is told `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`, making it commit one character at a time without predictive text or composing text. Many IMEs do not offer Japanese or Korean input in this case, so the two cannot coexist. The user switches depending on the situation (e.g. "Direct" on the sample app's extra key bar).
- Switching commits and sends the composing text, resets the input history, and restarts IME input.
- Keys from a physical keyboard are sent directly as keys if the IME does not consume them (6.3 of the contract). `sendKey` and `sendText` from an extra key bar are also sent immediately.

## Alternatives considered

- **Always use `VISIBLE_PASSWORD`**: Japanese and Korean cannot be entered (section 3 of the contract).
- **Automatically switch to direct input on the alternate screen (vim, less, and so on)**: Japanese could no longer be entered in vim's insert mode. Whether on the alternate screen does not match whether in normal mode.
- **Send English composing text one character at a time**: It breaks invariant 2, that composing text is not sent, and cannot be distinguished from Japanese composing text.

## Impact of reversing

- To drop `directInput`, remove `TerminalSession.directInput` and the parameter of `TerminalInputConnection.configure` (a public API change).
- To change the matrix, change the targets of `spec/ime` (owned by humans) and the languages in `scripts/e2e_ime.py`.
