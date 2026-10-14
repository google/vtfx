# Supported scope of terminal emulation

- Status: Approved (2026-10-13, approved by a human in the final review. Color settings from the host and left and right margins are not supported)
- Date: 2026-10-12
- Related: Requirement 5.1, section 8 (the references to conform to, and the range of supported sequences), [conformance-references.md](../research/conformance-references.md), [decisions/0001](0001-parser.md), [decisions/0006](0006-gate-defaults.md), [decisions/0010](0010-term-terminfo.md), `Emulator` in `vtfx-core`

## Background

Requirement 5.1 asks for the range of supported escape sequences and modes to be documented, and for deviations from the references to be recorded.

## Decision

### References

Take the proposal of [conformance-references.md](../research/conformance-references.md). Syntax follows Williams's state machine (deviations in 0001), features from DEC follow DEC STD 070, xterm extensions follow ctlseqs, and individual extensions follow their own specifications. When the interpretation is unclear, match the behavior of the original xterm.

### Supported

| Category | Supported | Tests |
|---|---|---|
| C0 control characters | BEL, BS, HT, LF, VT, FF, CR, SO, SI, CAN, SUB | `spec/core`, `TerminalTest`, `EmulationCoverageTest` |
| ESC | DECSC, DECRC, IND, NEL, HTS, RI, RIS, DECKPAM, DECKPNM, DECALN, character set designation (ASCII, DEC Special Graphics, UK into G0–G3) | Same as above |
| Cursor | CUU, CUD, CUF, CUB, CNL, CPL, CHA, HPA, HPR, CUP, HVP, VPA, VPR, CHT, CBT | `spec/core` |
| Erasing and editing | ED (0–3), EL, DECSED, DECSEL (selective erase is treated as normal erase), ECH, ICH, DCH, IL, DL, REP | `spec/core`, `TerminalTest` |
| Scrolling | DECSTBM, SU, SD, scrollback (main screen only, saved on scrolling from the top edge) | `spec/core`, `TerminalTest` |
| SGR | 0–9, 21–29, 30–49, 53, 55, 58, 59, 90–107. 256 colors and truecolor (both the `;` and `:` forms). Underline styles (4:0–4:5). Underline color (58, 59; added 2026-10-14, [0030](0030-underline-color-and-decorations.md)) | `TerminalTest` |
| ANSI modes | IRM (4), LNM (20) | `spec/core`, `TerminalTest` |
| DEC modes | 1, 5, 6, 7, 9, 12, 25, 47, 66, 1000, 1002, 1003, 1004, 1005, 1006, 1015, 1047, 1048, 1049, 2004, 2026 | `TerminalTest`, `EmulationCoverageTest` |
| Cursor shape | DECSCUSR (0–6) | `EmulationCoverageTest`, screenshots |
| Queries | DA1, DECID, DA2, DSR 5, DSR 6, DECXCPR, DECRQM, DECRQSS (`m`, `r`, ` q`), XTVERSION, XTWINOPS 18, `?` of OSC 4 / 10 / 11 / 12 | `EmulationCoverageTest`, the gate's `RepliesAndMouseTest`, `AttackTest` |
| OSC | 0, 2 (title), 8 (hyperlinks), 52 (clipboard; the policy is 0006) | `EmulationCoverageTest`, `AttackTest` |
| Title stack | 0 and 2 of XTWINOPS 22 (save) and 23 (restore). Only titles are pushed. The limits on depth and length are in 0002 (added 2026-10-13) | `EmulationCoverageTest.SavedTitles` |
| Mouse | Tracking (9, 1000, 1002, 1003) and encodings (default, 1005, 1006, 1015). The gate assembles the reports (`HostWriter.sendMouse`). Connecting it to touch interaction is M9 | `RepliesAndMouseTest` |
| synchronized output | 2026. While set, drawing is held back, and drawing happens when it is reset. If it is not reset within 200 ms of starting to hold back, drawing happens even if output continues | `TerminalSessionTest` |

### Not supported

| Feature | Reason |
|---|---|
| Left and right margins (DECLRMM 69, DECSLRM) | The implementation affects a lot (inserting and deleting lines, scrolling, and wrapping). The xterm-256color terminfo has `smglr`, but major apps (vim, less, htop, tmux in the default configuration) do not use it. The corresponding esctest cases are reported as "unsupported" (M7). |
| 132-column mode (DECCOLM 3) | The terminal size is determined by the display size. |
| Reports of the title and icon name (XTWINOPS 20, 21) | Reports become a command injection path (0006). |
| Icon name (OSC 1), specifying a title stack number (the third parameter of XTWINOPS 22, 23) | Android screens have no icon name. Specifying a number is an xterm extension that major apps do not use. Requests with a third parameter are ignored as a whole. |
| Checksums of the screen contents (DECRQCRA) | They become a path for leaking the contents. There is also no need to enable them for esctest: the esctest harness (`EsctestHost`) intercepts only this request and computes it in the test code (section 11 of testing.md). |
| Setting colors (setting OSC 4, 10, 11, 12; 104, 110–112) | Colors are decided by the app's style. Queries are answered. |
| Protection attribute for selective erase (DECSCA) | Major apps do not use it. DECSED and DECSEL become normal erases. |
| Displaying blinking characters, cursor blinking | Drawing is M9 or later. The model keeps the blinking state. |
| Printing (MC), sounds other than the bell, images such as sixel (requirement 4.2) | Outside the scope of terminal emulation. |
| C1 control characters (8-bit) | Ignored, as in 0001. |
| XTGETTCAP (DCS + q) | Replies would include terminfo strings. The TERM policy (0010) is enough. |
| Rectangle editing and column operations (DECCRA, DECERA, DECFRA, DECSERA, DECIC, DECDC, DECBI, DECFI) | VT400 and later features that major apps do not use. |
| Reverse wraparound (DECSET 45), the more(1) workaround (DECSET 41), saving and restoring DEC modes (XTSAVE, XTRESTORE) | xterm extensions that major apps do not use. |
| Conformance level (DECSCL), DSR extensions (printer, UDK, keyboard, locator, and so on) | There is no corresponding device or switch. |
| Setting the modes KAM, SRM, DECBKM, DECHEBM, DECNRCM, DECPEX, DECPFF, DECSCLM, DECCOLM, DECLRMM | DECRQM reports them as reset (some as permanently reset), and setting is ignored. Same as xterm's reports. |

### Deviations

| Item | Reference | vtfx | Reason |
|---|---|---|---|
| OSC 52 data that is not base64 | xterm clears the selection | Ignored | It is safer not to act on malformed input. |
| REP count | No limit | Up to the number of cells on the screen | Resource limits (0002). |
| OSC and DCS beyond the string limit | Not specified | The whole is dropped | Truncating and executing would change the meaning (0002). |
| 1 of XTWINOPS 22, 23 (icon name only) | Pushes an entry with only the icon name. Pop takes the top entry and fills missing values from the entries below | Does nothing (same as VTE). 0 and 2 push only the title | vtfx has no icon name, so it does not add invisible state. With usage like vim's, which pushes the title and the icon name separately and restores them separately, the resulting title is the same as xterm (added 2026-10-13). |
| Limit of the title stack | A ring of 10 | 10, plus a total length up to the limit of OSC strings; beyond that, the oldest are dropped | Resource limits (0002). |
| DECSCUSR 0 | Blinking block (ctlseqs) | Returns to the app's default (`TerminalStyle.cursorShape`, `cursorBlinking`). The same for RIS | For customizability in requirement 6.5 (added in M13). Like VTE, kitty, and Windows Terminal, 0 means "return to the default". The app's default is shown until the host sets DECSCUSR 1–6 or mode 12. |

### How the underline color came to be supported

Initially the underline color was not supported, because there is no free space in the 64 bits of the cell style, and its parameters were skipped. It is now kept in a side array of the line that is allocated only when used ([0030](0030-underline-color-and-decorations.md)). Supported on 2026-10-14.

### How the title stack came to be supported

Initially the title stack was not supported, as "it means little without reports", but this was a mistake. Push and pop are complete within the terminal and are useful even without reports. vim saves with `CSI 22;2t` and `CSI 22;1t` at startup (`recordings/vim.out`) and restores at exit. Without support, vim's title remains in the shell after leaving vim. Supported on 2026-10-13.

## Impact of reversing

To support more, add handling to `Emulator`, add tests to `EmulationCoverageTest`, and update the tables of this document. If replies are involved, also update the gate's `Replies` and the list of allowed replies in `AttackTest`.
