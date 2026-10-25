# The scope of terminal emulation

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 4.2, 5.1, 5.2, 5.4, 6.1, 6.2, 6.5, and section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md), sections 1 to 8 and 9.7 of [emulation.md](../design/emulation.md), sections 2 to 4 of [compose-input.md](../design/compose-input.md), section 3 of [gate-policy.md](../design/gate-policy.md), [fonts.md](../design/fonts.md), [conformance-references.md](../research/conformance-references.md), [terminal-comparison-2026-10.md](../research/terminal-comparison-2026-10.md), [0081](0081-resource-limits.md), [0083](0083-gate.md), [0014](0014-performance.md), [0029](0029-import-without-gradle.md), [0085](0085-compose-interaction.md), [0080](0080-kitty-graphics.md)

## Background

Requirement 5.1 asks for the range of supported escape sequences and modes to be documented, and for deviations from the references to be recorded; requirement 5.2 asks for a policy on fullwidth and Ambiguous characters, grapheme clusters, and the Unicode version. Host output is untrusted (requirement 6.1). Apps on the host choose what to send from the terminfo that `TERM` names, and count widths with libc's `wcwidth()`; where vtfx and the host disagree, cursor positions shift and the screen breaks. alacritty_terminal, the terminal library of Alacritty, has features that vtfx lacks; topics 3 and 8 say which of them vtfx takes, apart from those left out for reasons of their own: setting colors from the host (topic 3), searching with regular expressions ([0085](0085-compose-interaction.md)), and creating PTYs (section 4.2 of [REQUIREMENTS.md](../REQUIREMENTS.md)).

## 1. References

Take the proposal of [conformance-references.md](../research/conformance-references.md): syntax follows Williams's state machine (deviations in topic 2), features from DEC follow DEC STD 070, xterm extensions follow ctlseqs, and individual extensions follow their own specifications. When the interpretation is unclear, match the behavior of the original xterm. The layers are in section 1 of [emulation.md](../design/emulation.md).

## 2. Parsing

Apply Williams's state machine to code points after UTF-8 decoding, replace malformed UTF-8 with U+FFFD by maximal subparts, and ignore unrecognized control functions rather than treat them as errors. The deviations are in section 2 of [emulation.md](../design/emulation.md).

**Why.** Williams's state machine assumes an 8-bit terminal and does not cover UTF-8 or the extensions since xterm (subparameters with `:`, OSC terminated by BEL). The reasons for each deviation:
- **C1 control characters are ignored.** In UTF-8 they arrive only as 2-byte sequences, and almost no legitimate software sends them as C1, while executing them creates a path that slips past filters that only remove ESC. alacritty, kitty, and wezterm handle them the same way.
- **`:` separates subparameters**, for SGR's `38:2::R:G:B` and `4:3` (ITU T.416, xterm).
- **BEL also ends OSC**, the convention of xterm and of the many apps that follow it.
- **A string aborted with CAN or SUB is discarded**: it is safer not to execute an aborted string. Same as xterm.
- **Sequences with three or more intermediate characters are ignored**: no supported sequence has three or more.
- **A character beyond ASCII where a final byte is expected ends the sequence without dispatching it.** Final bytes are ASCII in ECMA-48. Dispatching such a character would let a code point above U+FFFF act as the ASCII character with the same low 16 bits (U+1006D as `m`, U+10063 after ESC as RIS), which a filter that checks final bytes as ASCII would not recognize.
- **A malformed UTF-8 byte does not take the correct bytes after it along** (section 3.9 of the Unicode Standard, the WHATWG Encoding standard).

**Alternatives considered.**
- **Execute C1 (some settings of xterm's UTF-8 mode)**: not taken, for the reason above.
- **Apply the state machine to bytes and decode only the printable parts as UTF-8** (xterm's approach): strings such as OSC titles also contain UTF-8, so decoding first is more consistent.

**Impact of reversing.** To execute C1, stop ignoring C1 in `Parser.advance`, add Williams's "anywhere" transitions (0x90 DCS, 0x9B CSI, 0x9C ST, 0x9D OSC, and so on), and update section 2 of emulation.md. There is no impact on the spec tests.

## 3. Supported and unsupported features

Support the features in section 3 of [emulation.md](../design/emulation.md), and not those in section 4. The lower bound is what the `xterm-256color` terminfo declares (topic 5). The title stack (XTWINOPS 22, 23) is supported although titles are not reported. The keyboard protocols (the kitty keyboard protocol and xterm's modifyOtherKeys) are decided apart, in [0083](0083-gate.md).

**Why.** The reasons for leaving features out:

| Feature | Reason |
|---|---|
| Left and right margins (DECLRMM 69, DECSLRM) | The implementation affects a lot (inserting and deleting lines, scrolling, and wrapping). The xterm-256color terminfo has `smglr`, but major apps (vim, less, htop, tmux in the default configuration) do not use it. |
| 132-column mode (DECCOLM 3) | The terminal size is determined by the display size. |
| Reports of the title and icon name (XTWINOPS 20, 21) | Reports become a command injection path (section 3 of [gate-policy.md](../design/gate-policy.md)). |
| Icon name (OSC 1), a title stack index (the third parameter of XTWINOPS 22, 23) | Android screens have no icon name. The index is an xterm extension that major apps do not use. |
| Checksums of the screen contents (DECRQCRA) | They become a path for leaking the contents. esctest does not need them enabled: its harness computes them in the test code. |
| Setting colors (setting OSC 4, 10, 11, 12; 104, 110–112) | Colors are decided by the app's style. Queries are answered. |
| Protection attribute for selective erase (DECSCA) | Major apps do not use it. |
| Printing (MC), sounds other than the bell | Outside the scope of terminal emulation. |
| Image protocols other than kitty's, such as sixel | Out of scope (section 4.2 of [REQUIREMENTS.md](../REQUIREMENTS.md)); why the kitty graphics protocol was taken rather than sixel is in [0080](0080-kitty-graphics.md). |
| C1 control characters (8-bit) | As in topic 2. |
| XTGETTCAP (DCS + q) | Replies would include terminfo strings. The TERM policy (topic 5) is enough. |
| Rectangle editing and column operations (DECCRA, DECERA, DECFRA, DECSERA, DECIC, DECDC, DECBI, DECFI) | VT400 and later features that major apps do not use. |
| Reverse wraparound (DECSET 45), the more(1) workaround (DECSET 41), saving and restoring DEC modes (XTSAVE, XTRESTORE) | xterm extensions that major apps do not use. |
| Conformance level (DECSCL), DSR extensions (printer, UDK, keyboard, locator, and so on) | There is no corresponding device or switch. |
| Setting the modes KAM, SRM, DECBKM, DECHEBM, DECNRCM, DECPEX, DECPFF, DECSCLM, DECCOLM, DECLRMM | Setting is ignored, and DECRQM reports them as reset, as xterm's reports do. |
| The mouse pointer shape (OSC 22) | The pointer icon is vtfx's (section 3 of [compose-input.md](../design/compose-input.md)). A shape chosen by the host would compete with the link's, and major apps do not send it. |
| The cursor shape by OSC 50 (`CursorShape=`) | An extension of Konsole and iTerm2 that DECSCUSR covers. In xterm, OSC 50 sets the font instead, which vtfx does not let the host do either ([fonts.md](../design/fonts.md)), so the number does not mean the same in every terminal. |
| Urgency hints (mode 1042) | The mode makes the bell mark the window as urgent, which X11 has and Android does not. The session already reports bells to the app (`TerminalSessionListener.onBell`), and the app decides how to tell the user. |

The title stack is supported because push and pop are complete within the terminal, and vim saves the title with `CSI 22;2t` and `CSI 22;1t` at startup (`recordings/vim.out`) and restores it at exit, so without the stack vim's title would remain in the shell after leaving vim.

The keyboard protocols are decided apart because they change the bytes that the gate sends for keys, which the gate builds from the key, its modifiers, and the terminal modes ([0083](0083-gate.md)): modifyOtherKeys stays within what xterm encodes, while the kitty keyboard protocol goes beyond it, so taking it changes what the gate may send.

**Alternatives considered.**
- **Support OSC 22**: the pointer icon over links would depend on what the host last set, for a sequence that major apps do not send.
- **Support OSC 50 as `CursorShape=`**: gives a second way to do what DECSCUSR does, under a number that means the font in xterm.

**Impact of reversing.** To support more, add handling to `Emulator`, add tests to `EmulationCoverageTest`, and update sections 3 to 5 of emulation.md and `tools/esctest/reasons.toml`; for OSC 22, also the pointer icon in `terminalMouse` of `vtfx-compose`. If replies are involved, also update the gate's `Replies` and the list of allowed replies in `AttackTest`.

## 4. Deviations from the references

The deviations are in section 5 of [emulation.md](../design/emulation.md).

**Why.**
- **OSC 52 data that is not base64 is ignored** rather than clearing the selection: it is safer not to act on malformed input.
- **The REP count, the string limit, and the depth and length of the title stack** follow the resource limits ([0081](0081-resource-limits.md)).
- **1 of XTWINOPS 22 and 23 does nothing**, as in VTE: vtfx has no icon name, so it does not add invisible state. With usage like vim's, the resulting title is the same as in xterm.
- **DECSCUSR 0 returns to the app's default** rather than a blinking block, for the customizability of requirement 6.5. VTE, kitty, and Windows Terminal also take 0 as "return to the default".

**Impact of reversing.** As in topic 3: change `Emulator` and `EmulationCoverageTest`, and update section 5 of emulation.md and `tools/esctest/reasons.toml`.

## 5. TERM and terminfo

vtfx has no connection to the host, so the app sets `TERM` (SSH pty-req, the PTY's environment variables).
- Recommend `TERM=xterm-256color` and `COLORTERM=truecolor` to apps, in the KDoc and the sample app's documents.
- Do not distribute a custom terminfo (`vtfx` or the like).
- Take the features of `xterm-256color` (Debian, ncurses 6.5) as the lower bound of the supported scope; the features left out are left out for the reasons in topic 3. The status of each feature is in section 6 of [emulation.md](../design/emulation.md).

**Why.** `COLORTERM=truecolor` is recommended since truecolor SGR is supported. Many environments have no way to install terminfo on the host, and an unknown `TERM` makes many apps behave worse.

**Alternatives considered.**
- **A `TERM` that assumes truecolor, such as `xterm-direct`**: some apps misinterpret 256-color specifications. `COLORTERM` is enough.
- **Distribute a custom terminfo**: not taken, for the reasons above. Consider it if it becomes necessary.

**Impact of reversing.** To change the recommended `TERM`, redo the table in section 6 of emulation.md, and implement the unsupported features that the new `TERM` uses.

## 6. Unicode version, character width, and grapheme clusters

The rules are in section 7 of [emulation.md](../design/emulation.md).
1. **Width rules**: the width table is generated from the Unicode Character Database with the rules of glibc's `utf8_gen.py`. The generator reads copies of the UCD files in `third_party/unicode` and does not download them. The table is generated by `scripts/generate_unicode_tables.py` and committed ([0029](0029-import-without-gradle.md)).
2. **Unicode version**: 16.0.0. Move to a newer version after the libc of major hosts does.
3. **East Asian Ambiguous**: width 1 by default; `AmbiguousWidth.Wide` makes it 2. Matching the host's setting is the app's responsibility, and is written in the KDoc.
4. **Grapheme clusters**: widths are counted per code point, and zero-width characters are attached to the preceding cell, so a sequence of emoji joined with ZWJ takes cells for each emoji. As an exception, a pair of regional indicators (a flag) is combined into one 2-cell character. A mode that counts a grapheme cluster as one unit (the mode 2027 proposal) is not supported.
5. **Box-drawing and block elements** are drawn as shapes rather than font glyphs.
6. **CJK characters** are drawn with Android's system font fallback, and the glyphs of Han characters (which differ between Japanese, Chinese, and Korean) can be chosen with `TerminalStyle.locale`, defaulting to the system locale.

**Why.**
1. The widths then agree with `wcwidth()` on hosts with the same Unicode version, which most apps on the host use (or their own table with the same rules). The UCD files are not downloaded so as not to put load on unicode.org.
2. 16.0.0 is the version of glibc 2.41 in Debian 13. The widths of characters assigned in a newer version would disagree with hosts whose libc is older.
3. `wcwidth()` returns 1 in almost all locales (including ja_JP.UTF-8); `Wide` is for users of vim's `ambiwidth=double` and the like on the host.
4. Counting per code point matches the sum of `wcwidth()` on the host: drawing a ZWJ sequence in 2 cells as one emoji would disagree with the width the host counted. The two regional indicators take 1 + 1 cells anyway, so combining them does not change the number of cells, and they can be drawn as a flag. Counting a cluster as one unit needs agreement with the host.
5. Font glyphs do not reach the edges of the cell and leave gaps between cells.

**Alternatives considered.**
- **Use the latest Unicode (18.0)**: the widths of newly assigned characters would disagree with the host. The libc of major hosts is still at 16 or earlier.
- **Draw a grapheme cluster as one unit**: looks nicer, but disagrees with the width the host counted and breaks the cursor.

**Impact of reversing.** To change the version, replace the files in `third_party/unicode` as its README describes, regenerate the table with `scripts/generate_unicode_tables.py`, and check the width examples in `EmulationCoverageTest`. To change the handling of grapheme clusters, change `Emulator.print` and `Line`, and section 7 of emulation.md.

## 7. Underline color, underline styles, overline, and the cursor's text color

The resulting behavior is in section 8 of [emulation.md](../design/emulation.md).
1. **Underline color**: SGR 58 and 59 are supported in the forms of 38 and 48. The current rendition is a `Rendition` (the `CellStyle` and the underline color), and `CellStyle` does not change. Cells keep the underline color in a side array of the line, allocated only when a color is written, as the hyperlink ids are. A cell gets the color only while the rendition underlines; the exception is the placeholder of an image, which keeps the underline color without an underline, since the color gives the placement id (sections 8.1 and 9.7 of emulation.md). `LineSnapshot.underlineColorAt` exposes it, and DECRQSS reports it.
2. **Drawing**: `TerminalRenderer` draws underlines and overlines as shapes over whole cells, in every style, anchored to the cell grid, instead of `Paint.isUnderlineText` under the glyphs. Underlined spaces are underlined too, as in xterm.
3. **The cursor's text color**: `TerminalColors.cursorText` sets the color of the text under a focused block cursor, and its default keeps the earlier behavior (the default background color). The host cannot set it.

**Why.**
1. The 64 bits of `CellStyle` are full: 26 bits for each of the two colors, 3 for the underline style, and 8 flags leave one bit, while a color takes 26. The side array costs 4 bytes per cell, only in lines that use the color; the scrollback's budget ([0081](0081-resource-limits.md)) counts it, so the worst case stays within the budget, and such lines keep fewer lines, as lines with hyperlinks do. Output without SGR 58 pays one null check per written cell, and nothing in the comparison of lines without arrays, so the speed of plain text ([0014](0014-performance.md)) is unaffected beyond that check. Storing the color only while the rendition underlines means that a color left set after the underline ends (common, since `SGR 24` does not reset it) takes no memory, and a cell does not carry a color it does not show.
2. Apps that underline a whole row (vim's `cursorline`, status lines) show it to the end, and runs of different styles line up.
3. The text under a block cursor was always drawn in the default background color, which an app could not change. Setting colors with OSC 10 to 12 remains unsupported (topic 3).

**Alternatives considered.**
- **Pack the underline color into `CellStyle`**: no room. Widening `CellStyle` to two `Long`s would double the style memory of every cell, against the targets of [0014](0014-performance.md), for a feature most output does not use.
- **Intern colors and keep an index in the free bit of `CellStyle`**: one bit is not an index. Taking bits from the colors would break 24-bit color.
- **A side table keyed by an index in the style (as `CompactLine` keys clusters)**: more code than a per-cell array for little gain, since lines with underline colors are rare and the scrollback budget bounds the worst case.
- **Store the color in cells whether or not they are underlined**: simpler, but allocates arrays for apps that set SGR 58 once and then underline only sometimes, and makes `underlineColorAt` report colors that are not shown.
- **Keep `Paint.isUnderlineText` for single underlines**: it draws only under glyphs, so underlined spaces would show nothing, and a run of single and curly underlines would not line up.

**Impact of reversing.**
- To drop the underline color: remove `Rendition.underlineColor`, the side array in `Line` and `CompactLine`, and `LineSnapshot.underlineColorAt` (a public API change), and make SGR 58 skip its parameters again.
- To return to glyph-only underlines: replace `drawDecorations` in `TerminalRenderer` with `Paint.isUnderlineText`, and re-record the screenshot goldens.
- To drop `cursorText`: remove the property, which is a public API change.
- In each case, update section 8 of emulation.md.

## 8. Selection and scrolling features of alacritty_terminal

1. **A vi-like keyboard mode** for moving through the scrollback and selecting is not offered.
2. **Block selection** is not offered for now.
3. **Words bounded by punctuation are to be supported.** A word is now the run of non-blank characters around a cell (section 4 of [compose-input.md](../design/compose-input.md)); words will also end at a set of delimiters. The set, and whether the app can change it, are decided with the implementation, in a decision of their own.
4. **Alternate scroll (mode 1007) is to be supported.** vtfx always turns swipes and the wheel on the alternate screen into arrow keys (sections 2 and 3 of [compose-input.md](../design/compose-input.md)). The mode will be set by default, keeping this behavior; while the host resets it, swipes and the wheel on the alternate screen send nothing, and DECRQM reports it. It costs a flag in `TerminalModes`.

**Why.**
1. The library is used mostly by touch, where such a mode is of little use, and its keys would compete with the app's shortcuts ([0085](0085-compose-interaction.md)). Revisit if desktop use with a keyboard ([0085](0085-compose-interaction.md)) asks for it.
2. It helps to copy a column of a table, but it has no natural gesture on touch, and the rules of copying (the trailing blanks, a wide character at either edge, rows that autowrap joins) would have to be decided again for a rectangle. Revisit when there is demand.
3. With runs of non-blank characters, a double click or a long press on `"foo/bar.txt"` or `(https://example.com)` takes the quotes or the parentheses too.
4. The host cannot turn the conversion off, so an app on the alternate screen in which the arrow keys do something else (a REPL's history) gets them anyway.

**Alternatives considered.**
- **A vi-like mode in the library**: a large state machine of keys and a second cursor, mainly for keyboard use, which the app could build if the library let it set the selection.
- **Block selection now**: the rules of copying a rectangle and a gesture for touch would have to be designed for a use that nobody has asked for yet.
- **Keep the words as runs of non-blank characters**: the simplest rule, but the usual targets of a double click, paths and URLs in quotes or parentheses, need the selection fixed by hand on a phone.
- **Leave mode 1007 out**: few apps reset it, but the conversion that it controls is always on, and the cost is small.

**Impact of reversing.**
- Items 1 and 2: add the mode or the shape of selection to `TerminalSelection` and `TerminalDisplay`, the gestures, the copying, and section 4 of compose-input.md.
- Item 3: once implemented, go back to the runs of non-blank characters in the word selection of `vtfx-compose`.
- Item 4: once implemented, remove the flag and its report, and turn swipes and the wheel on the alternate screen into arrow keys again in every case.
