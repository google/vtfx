# Supported scope of terminal emulation

- Status: Approved (2026-10-13, approved by a human in the final review. Color settings from the host and left and right margins are not supported); the exclusion of the underline color superseded by [0030](0030-underline-color-and-decorations.md), and the exclusion of images by [0038](0038-kitty-graphics.md)
- Date: 2026-10-12
- Related: Requirement 5.1, section 8 (the references to conform to, and the range of supported sequences), sections 1, 3, 4, and 5 of [emulation.md](../design/emulation.md), [conformance-references.md](../research/conformance-references.md), [decisions/0001](0001-parser.md), [decisions/0006](0006-gate-defaults.md), [decisions/0010](0010-term-terminfo.md), `Emulator` in `vtfx-core`

## Background

Requirement 5.1 asks for the range of supported escape sequences and modes to be documented, and for deviations from the references to be recorded.

## Decision

### References

Take the proposal of [conformance-references.md](../research/conformance-references.md): syntax follows Williams's state machine (deviations in [0001](0001-parser.md)), features from DEC follow DEC STD 070, xterm extensions follow ctlseqs, and individual extensions follow their own specifications. When the interpretation is unclear, match the behavior of the original xterm. The layers are in section 1 of [emulation.md](../design/emulation.md).

### Scope

Support the features listed in section 3 of [emulation.md](../design/emulation.md), and not those in section 4. The lower bound is what the `xterm-256color` terminfo declares ([0010](0010-term-terminfo.md)). The reasons for leaving features out:

| Feature | Reason |
|---|---|
| Left and right margins (DECLRMM 69, DECSLRM) | The implementation affects a lot (inserting and deleting lines, scrolling, and wrapping). The xterm-256color terminfo has `smglr`, but major apps (vim, less, htop, tmux in the default configuration) do not use it. |
| 132-column mode (DECCOLM 3) | The terminal size is determined by the display size. |
| Reports of the title and icon name (XTWINOPS 20, 21) | Reports become a command injection path ([0006](0006-gate-defaults.md)). |
| Icon name (OSC 1), a title stack index (the third parameter of XTWINOPS 22, 23) | Android screens have no icon name. The index is an xterm extension that major apps do not use. |
| Checksums of the screen contents (DECRQCRA) | They become a path for leaking the contents. esctest does not need them enabled: its harness computes them in the test code. |
| Setting colors (setting OSC 4, 10, 11, 12; 104, 110–112) | Colors are decided by the app's style. Queries are answered. |
| Protection attribute for selective erase (DECSCA) | Major apps do not use it. |
| Printing (MC), sounds other than the bell, image protocols (requirement 4.2) | Outside the scope of terminal emulation. (The kitty graphics protocol is supported, [0038](0038-kitty-graphics.md).) |
| C1 control characters (8-bit) | As in [0001](0001-parser.md). |
| XTGETTCAP (DCS + q) | Replies would include terminfo strings. The TERM policy ([0010](0010-term-terminfo.md)) is enough. |
| Rectangle editing and column operations (DECCRA, DECERA, DECFRA, DECSERA, DECIC, DECDC, DECBI, DECFI) | VT400 and later features that major apps do not use. |
| Reverse wraparound (DECSET 45), the more(1) workaround (DECSET 41), saving and restoring DEC modes (XTSAVE, XTRESTORE) | xterm extensions that major apps do not use. |
| Conformance level (DECSCL), DSR extensions (printer, UDK, keyboard, locator, and so on) | There is no corresponding device or switch. |
| Setting the modes KAM, SRM, DECBKM, DECHEBM, DECNRCM, DECPEX, DECPFF, DECSCLM, DECCOLM, DECLRMM | DECRQM reports them as reset, as xterm's reports do, and setting is ignored. |

The title stack (XTWINOPS 22, 23) is supported although titles are not reported: push and pop are complete within the terminal, and vim saves the title with `CSI 22;2t` and `CSI 22;1t` at startup (`recordings/vim.out`) and restores it at exit, so without the stack vim's title would remain in the shell after leaving vim.

### Deviations

The deviations from the references are listed in section 5 of [emulation.md](../design/emulation.md). Their reasons:

- **OSC 52 data that is not base64 is ignored** rather than clearing the selection: it is safer not to act on malformed input.
- **The REP count, the string limit, and the depth and length of the title stack** follow the resource limits ([0002](0002-resource-limits.md)).
- **1 of XTWINOPS 22 and 23 does nothing**, as in VTE: vtfx has no icon name, so it does not add invisible state. With usage like vim's, the resulting title is the same as in xterm.
- **DECSCUSR 0 returns to the app's default** rather than a blinking block, for the customizability of requirement 6.5. VTE, kitty, and Windows Terminal also take 0 as "return to the default".

## Impact of reversing

To support more, add handling to `Emulator`, add tests to `EmulationCoverageTest`, and update sections 3 to 5 of [emulation.md](../design/emulation.md) and `tools/esctest/reasons.toml`. If replies are involved, also update the gate's `Replies` and the list of allowed replies in `AttackTest`.
