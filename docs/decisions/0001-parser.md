# Parser syntax, and deviations from the reference

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.1, sections 1 and 2 of [emulation.md](../design/emulation.md), [conformance-references.md](../research/conformance-references.md), `vtfx-core/src/main/kotlin/com/google/vtfx/core/parser/Parser.kt`

## Background

The rules for splitting host output into sequences needed to be decided. The research document proposes Paul Williams's DEC ANSI parser state machine as the layer 1 reference. However, this state machine assumes an 8-bit terminal and does not cover the handling of UTF-8 or extensions since xterm (subparameters with `:`, OSC terminated by BEL).

## Decision

Apply Williams's state machine to code points after UTF-8 decoding, replace malformed UTF-8 with U+FFFD by maximal subparts, and ignore unrecognized control functions rather than treat them as errors. The deviations from the state machine are listed in section 2 of [emulation.md](../design/emulation.md). Their reasons:

- **C1 control characters are ignored.** In UTF-8 they arrive only as 2-byte sequences, and almost no legitimate software sends them as C1. On the other hand, executing C1 creates a path that slips past filters that only remove ESC. alacritty, kitty, and wezterm handle them the same way.
- **`:` separates subparameters**, for SGR's `38:2::R:G:B` and `4:3` (ITU T.416, xterm).
- **BEL also ends OSC**, the convention of xterm and of the many apps that follow it.
- **A string aborted with CAN or SUB is discarded.** It is safer not to execute an aborted string. Same as xterm.
- **Sequences with three or more intermediate characters are ignored.** No supported sequence has three or more.
- **A character beyond ASCII where a final byte is expected ends the sequence without dispatching it.** Final bytes are ASCII in ECMA-48. Dispatching such a character would let a code point above U+FFFF act as the ASCII character with the same low 16 bits (U+1006D as `m`, U+10063 after ESC as RIS), which a filter that checks final bytes as ASCII would not recognize (found in a security review).
- **A malformed UTF-8 byte does not take the correct bytes after it along** (section 3.9 of the Unicode Standard, the WHATWG Encoding standard).

## Alternatives considered

- **Execute C1 (some settings of xterm's UTF-8 mode)**: Not taken, for the reason above.
- **Apply the state machine to bytes and decode only the printable parts as UTF-8** (xterm's approach): Strings such as OSC titles also contain UTF-8, so decoding first is more consistent.

## Impact of reversing

To execute C1, stop ignoring C1 in `Parser.advance` and add Williams's "anywhere" transitions (0x90 DCS, 0x9B CSI, 0x9C ST, 0x9D OSC, and so on), and update section 2 of [emulation.md](../design/emulation.md). There is no impact on the spec tests.
