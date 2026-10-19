# The user's copy and paste go through vtfx-gate

- Status: Approved (2026-10-19, decided by a human in a review of the review queue)
- Date: 2026-10-19
- Related: Requirement 6.1, sections 1, 3.3, and 5 of [architecture.md](../design/architecture.md), [0006](0006-gate-defaults.md), [0007](0007-compose-session.md), [0012](0012-touch-and-selection.md), [0027](0027-session-takes-connection.md), [0031](0031-desktop-pointer-and-terminal-state.md), [0041](0041-security-review-by-module.md), `config/boundaries.toml`

## Background

[0007](0007-compose-session.md), [0012](0012-touch-and-selection.md), and [0031](0031-desktop-pointer-and-terminal-state.md) made the user's copy and paste use the `ClipboardAccess` passed to the session. `TerminalSession.copyToClipboard` and `pasteFromClipboard` in vtfx-session, which is not under security review, called `ClipboardAccess.write` and `read` themselves. The OSC 52 policy does not apply to them, as they are the user's actions.

An audit of the security boundary (2026-10-19), which checked the compiled classes of every module, found these two calls to be the only use of a capability outside the gate modules. They contradicted pillar 1 of [architecture.md](../design/architecture.md) (other modules do not even hold the references needed to perform operations with external effects) and section 3.3 (vtfx-gate is solely responsible for them), while opening a URL, also a user's action, already went through `TerminalGate.openUrl`. The pasted bytes already went through `HostWriter.sendPaste`; only the calls to the clipboard were outside the gate.

Because of those calls, `config/boundaries.toml` could not name `ClipboardAccess` and `UrlOpener` either. A change to vtfx-session or vtfx-compose that called `UrlOpener.open` without the gate's URL check, or wrote host output to `ClipboardAccess`, passed `scripts/check_boundaries.py` and was not reported by `scripts/report_protected_changes.py`.

## Decision

1. `TerminalGate` has `copyToClipboard(text)` and `readClipboardForPaste()` for the user's copy and paste. They are for explicit actions of the user, and the OSC 52 policy does not apply to them. `TerminalSession` calls them, sends the pasted text through the IME editing model and `HostWriter.sendPaste` as before, and does not keep the clipboard: it hands the clipboard and the URL opener to the gate when it is constructed, as it does the connection ([0027](0027-session-takes-connection.md)). The public API and the behavior do not change.
2. `config/boundaries.toml` restricts `ClipboardAccess` and `UrlOpener` to `vtfx-gate`, `vtfx-gate-android` (which implements them), `TerminalSession.kt` (which only hands them over), and test sources (`*/src/test/**`). Tests implement fakes of them, and test code is not shipped.
3. Item 4 of [0027](0027-session-takes-connection.md) stays: `TerminalSession.kt` is not put under security review, and [0041](0041-security-review-by-module.md) does not change that. The same exception now covers `ClipboardAccess` and `UrlOpener`. The check keeps the references from leaving the file, but a change that calls them directly inside it is neither caught nor reported; holding against changes written to get around the check is the item "Boundary check against adversarial changes" in [review-queue.md](../review-queue.md).

## Alternatives considered

- **Reword the architecture to allow the user's clipboard operations in vtfx-session**: No change to the code, but the rule for `ClipboardAccess` could then only allow the whole of vtfx-session, so a change that wrote host output to the clipboard without the policy would still pass.
- **Implement the fakes of the tests in a test fixture of vtfx-gate, with no exception for tests**: Stricter, but it adds a test fixture and changes the build files of vtfx-gate, vtfx-session, and vtfx-compose, which are under review, to keep out of test code that is never shipped.
- **Put vtfx-session under security review**: It would match [0041](0041-security-review-by-module.md) and send every direct call to a human, but `TerminalSession.kt` changes often with input and the IME, every such change would wait for review, and it breaks the rule that the modules under review are named with `gate`.

## Impact of reversing

- Remove `copyToClipboard` and `readClipboardForPaste` from `TerminalGate`, and make `TerminalSession` keep the clipboard and call it again.
- Remove the rule for `ClipboardAccess` and `UrlOpener` from `config/boundaries.toml` and its cases from `scripts/tests/test_check_boundaries.py`.
- Revert sections 3.3 and 5 of `architecture.md` and the KDoc of `ClipboardAccess`.
