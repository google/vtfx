# TERM and terminfo

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.1 (TERM / terminfo), section 8, section 6 of [emulation.md](../design/emulation.md), section 5 of [conformance-references.md](../research/conformance-references.md), [decisions/0009](0009-emulation-scope.md)

## Background

Apps on the host decide which sequences to send by looking at the terminfo that the environment variable `TERM` points to. vtfx has no connection to the host, so the app sets `TERM` (SSH pty-req, the PTY's environment variables). Which value to recommend, and how much of its terminfo to support, needed to be decided.

## Decision

- Recommend `TERM=xterm-256color` to apps, and also `COLORTERM=truecolor`, since truecolor SGR is supported. Write this in the KDoc and the sample app's documents.
- Do not distribute a custom terminfo (`vtfx` or the like). Many environments have no way to install terminfo on the host, and an unknown `TERM` makes many apps behave worse.
- Take the features of `xterm-256color` (Debian, ncurses 6.5) as the lower bound of the supported scope ([0009](0009-emulation-scope.md)). The status of each feature is in section 6 of [emulation.md](../design/emulation.md); the features left out are left out for the reasons in 0009.

## Alternatives considered

- **A `TERM` that assumes truecolor, such as `xterm-direct`**: Some apps misinterpret 256-color specifications. `COLORTERM` is enough.
- **Distribute a custom terminfo**: Not taken, for the reasons above. If it becomes necessary, consider it after the final review.

## Impact of reversing

To change the recommended `TERM`, redo the table in section 6 of [emulation.md](../design/emulation.md), and implement the unsupported features that the new `TERM` uses.
