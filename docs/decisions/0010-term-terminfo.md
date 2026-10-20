# TERM and terminfo

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.1 (TERM / terminfo), section 8, section 5 of [conformance-references.md](../research/conformance-references.md), [decisions/0009](0009-emulation-scope.md)

## Background

Apps on the host decide which sequences to send by looking at the terminfo that the environment variable `TERM` points to. vtfx has no connection to the host, so the app sets `TERM` (SSH pty-req, the PTY's environment variables). Which value to recommend, and how much of its terminfo to support, needed to be decided.

## Decision

- Recommend `TERM=xterm-256color` to apps. Also recommend `COLORTERM=truecolor` (since truecolor SGR is supported). Write this in the KDoc (M13) and the sample app's documents.
- Do not distribute a custom terminfo (`vtfx` or the like). Many environments have no way to install terminfo on the host, and an unknown `TERM` makes many apps behave worse.
- The status for the features of Debian's `xterm-256color` (ncurses 6.5) as of 2026-10 is as follows.

| terminfo feature | Status |
|---|---|
| Basic movement and editing (`cup`, `csr`, `ich`, `dch`, `il`, `dl`, `ech`, `ed`, `el`, `el1`, `rep`, `indn`, `rin`, `hpa`, `vpa`, `cbt`, `tbc`, `hts`) | Supported |
| `am`, `xenl`, `bce`, `mir`, `msgr` | Supported (xenl is the pending wrap, bce is erasing with the background color) |
| Character attributes (`bold`, `dim`, `sitm`, `smul`, `blink`, `rev`, `invis`, `smso`, `sgr`, `sgr0`, `setaf`, `setab`, `colors#256`) | Supported (blinking is not displayed) |
| `smacs`, `rmacs`, `acsc` | Supported (DEC Special Graphics) |
| `smcup`, `rmcup` (1049 and the title stack) | Supported (the title stack pushes only titles, as in 0009) |
| `civis`, `cnorm`, `cvvis`, `Ss`, `Se` | Supported |
| `smkx`, `rmkx`, key capabilities (`kcuu1` and so on, keys with modifiers, function keys) | Supported for cursor keys, editing keys, F1–F12, and the keypad (including `kent` and the like in application mode) |
| `kmous`, `XM` (SGR mouse) | Supported. Correspondence with touch interaction is in 0012 |
| `BE`, `BD`, `PS`, `PE` (bracketed paste) | Supported |
| `fe`, `fd` (focus events) | Supported |
| `Ms` (OSC 52) | Supported; reading and writing are denied by default, and writing has a limit when the app allows it (0006) |
| `RV`, `XR`, `u6`–`u9` (queries) | Supported |
| `E3` (erasing scrollback) | Supported |
| `flash` (toggling DECSCNM) | Supported |
| `smglr`, `smglp`, `smgrp`, `mgc` (left and right margins) | Not supported (0009) |
| `initc`, `oc`, `Cs`, `Cr` (setting colors) | Not supported. Ignored (0009) |
| `smm`, `rmm` (meta key, 1034) | Ignored. Alt is sent as an ESC prefix |
| `mc0`, `mc4`, `mc5` (printing) | Not supported. Ignored |

## Alternatives considered

- **A `TERM` that assumes truecolor, such as `xterm-direct`**: Some apps misinterpret 256-color specifications. `COLORTERM` is enough.
- **Distribute a custom terminfo**: Not taken, for the reasons above. If it becomes necessary, consider it after the final review.

## Impact of reversing

To change the recommended `TERM`, redo the table of this document, and implement the unsupported features that the new `TERM` uses.
