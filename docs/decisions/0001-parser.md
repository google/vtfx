# Parser syntax, and deviations from the reference

- Status: Approved (2026-10-13, approved by a human in the final review)
- Date: 2026-10-12
- Related: Requirement 5.1, layer 1 of [conformance-references.md](../research/conformance-references.md), `vtfx-core/src/main/kotlin/com/google/vtfx/core/parser/Parser.kt`

## Background

The rules for splitting host output into sequences needed to be decided. The research document proposes Paul Williams's DEC ANSI parser state machine as the layer 1 reference. However, this state machine assumes an 8-bit terminal and does not cover the handling of UTF-8 or extensions since xterm (subparameters with `:`, OSC terminated by BEL).

## Decision

Apply Williams's state machine to code points after UTF-8 decoding. It deviates in the following points.

| Item | State machine | vtfx | Reason |
|---|---|---|---|
| C1 control characters (U+0080–U+009F) | Executed as control functions | Ignored (neither displayed nor executed) | In UTF-8 they arrive only as 2-byte sequences, and almost no legitimate software sends them as C1. On the other hand, executing C1 creates a path that slips past filters that only remove ESC. alacritty, kitty, and wezterm handle them the same way. |
| `:` in parameters | Invalidates the sequence | Separates subparameters | SGR's `38:2::R:G:B` and `4:3` (ITU T.416, xterm). |
| OSC terminator | ST only | ST or BEL | The convention of xterm and of many apps that follow it. |
| Aborting strings with CAN or SUB | Dispatches OSC and DCS, then aborts | Discards the string | It is safer not to execute an aborted string. Same as xterm. |
| Three or more intermediate characters | Not specified | The sequence is ignored | No supported sequence has three or more. |
| A character beyond ASCII where an escape or control sequence expects its final byte | Not specified (the state machine reads bytes) | Ends the sequence without dispatching it | Final bytes are ASCII in ECMA-48. Dispatching such a character let a code point above U+FFFF act as the ASCII character with the same low 16 bits (U+1006D as `m`, U+10063 after ESC as RIS), which a filter that checks final bytes as ASCII would not recognize (added 2026-10-15 after a security review). |

Malformed UTF-8 is replaced with U+FFFD following section 3.9 of the Unicode Standard (maximal subpart) and the WHATWG Encoding standard. A malformed byte does not drag the correct bytes after it along.

Unrecognized control functions are ignored rather than treated as errors.

## Alternatives considered

- **Execute C1 (some settings of xterm's UTF-8 mode)**: Not taken, for the reason above.
- **Apply the state machine to bytes and decode only the printable parts as UTF-8** (xterm's approach): Strings such as OSC titles also contain UTF-8, so decoding first is more consistent.

## Impact of reversing

To execute C1, stop ignoring C1 in `Parser.advance` and add Williams's "anywhere" transitions (0x90 DCS, 0x9B CSI, 0x9C ST, 0x9D OSC, and so on). There is no impact on the spec tests.
