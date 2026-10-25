# Terminal emulation

This document describes how `vtfx-core` interprets host output, as it is now: the references it follows, the supported and unsupported control functions, the recommended `TERM`, character widths, attributes, images, and the resource limits. The reasons are in the decisions linked from each section. Where the module sits in the whole is in section 3.1 of [architecture.md](architecture.md), and how the emulation is tested is in [testing.md](testing.md).

## 1. References

The references are divided into layers. Layers with smaller numbers take precedence. When the interpretation of a reference is unclear, vtfx matches the behavior of Thomas Dickey's xterm ([decisions/0079](../decisions/0079-emulation-scope.md)).

| Layer | Target | Reference |
|---|---|---|
| 1. Syntax | Rules for splitting host output into sequences | ECMA-48, and Paul Williams's DEC ANSI parser state machine (vt100.net/emu/dec_ansi_parser), with the deviations in section 2 |
| 2. Meaning of features from DEC | Cursor movement, margins, the pending state of end-of-line wrapping, origin mode, and so on | DEC STD 070, supplemented by manuals such as the VT510's |
| 3. xterm extensions | SGR 256 colors and truecolor, the alternate screen (1049), bracketed paste (2004), SGR mouse (1006), focus events (1004), OSC 52 | xterm ctlseqs |
| 4. Individual extensions | Synchronized output (2026), OSC 8 hyperlinks, underline styles and colors, the kitty graphics protocol | Their own specifications (Contour's specification of synchronized output, Egmont Koblinger's OSC 8 specification, the kitty documentation) |

The lower bound of the supported scope is the features of the terminfo entry `xterm-256color` (section 6). Deviations from the references are in sections 2 and 5, and their reasons in the decisions linked there.

## 2. Parsing

`Parser` applies Williams's state machine to code points after UTF-8 decoding ([decisions/0079](../decisions/0079-emulation-scope.md)), with these deviations:

| Item | State machine | vtfx |
|---|---|---|
| C1 control characters (U+0080–U+009F) | Executed as control functions | Ignored (neither displayed nor executed) |
| `:` in parameters | Invalidates the sequence | Separates subparameters (SGR `38:2::R:G:B`, `4:3`) |
| OSC terminator | ST only | ST or BEL |
| Aborting strings with CAN or SUB | Dispatches OSC and DCS, then aborts | Discards the string |
| Three or more intermediate characters | Not specified | The sequence is ignored |
| A character beyond ASCII where an escape or control sequence expects its final byte | Not specified (the state machine reads bytes) | Ends the sequence without dispatching it |

- Malformed UTF-8 is replaced with U+FFFD following section 3.9 of the Unicode Standard (maximal subpart) and the WHATWG Encoding standard. A malformed byte does not take the correct bytes after it along.
- OSC, DCS, and APC strings are collected up to `TerminalLimits.maxStringLength` UTF-16 units; a longer string is discarded whole (section 10). APC is ended only by ST, not BEL, and the control characters in it are dropped. SOS and PM strings are ignored.
- Parameters are clamped to 65535, and those after the 32nd are dropped (section 10).
- Unrecognized control functions are ignored rather than treated as errors.
- Every state handles every input in constant time, so the parser always makes progress.

## 3. Supported control functions

`Emulator` implements the following ([decisions/0079](../decisions/0079-emulation-scope.md)).

| Category | Supported | Tests |
|---|---|---|
| C0 control characters | BEL, BS, HT, LF, VT, FF, CR, SO, SI, CAN, SUB | `spec/core`, `TerminalTest`, `EmulationCoverageTest` |
| ESC | DECSC, DECRC, IND, NEL, HTS, RI, RIS, DECKPAM, DECKPNM, DECALN, DECID, character set designation (ASCII, DEC Special Graphics, UK into G0–G3; SO and SI select G1 and G0) | Same as above |
| Cursor | CUU, CUD, CUF, CUB, CNL, CPL, CHA, HPA, HPR, CUP, HVP, VPA, VPR, CHT, CBT, TBC | `spec/core` |
| Erasing and editing | ED (0–3), EL, DECSED, DECSEL (selective erase is a normal erase), ECH, ICH, DCH, IL, DL, REP | `spec/core`, `TerminalTest` |
| Scrolling | DECSTBM, SU, SD, the scrollback (main screen only, saved on scrolling from the top edge; ED 3 clears it) | `spec/core`, `TerminalTest` |
| SGR | 0–9, 21–25, 27–29, 30–49, 53, 55, 58, 59, 90–97, 100–107. 256 colors and truecolor (both the `;` and `:` forms). Underline styles (4:0–4:5) and the underline color (58, 59; section 8) | `TerminalTest` |
| ANSI modes | IRM (4), LNM (20) | `spec/core`, `TerminalTest` |
| DEC modes | 1, 5, 6, 7, 9, 12, 25, 47, 66, 1000, 1002, 1003, 1004, 1005, 1006, 1015, 1047, 1048, 1049, 2004, 2026 | `TerminalTest`, `EmulationCoverageTest` |
| Resets | DECSTR, RIS. RIS keeps the scrollback, the title, and the saved titles, as xterm does | `TerminalTest`, `EmulationCoverageTest` |
| Cursor shape | DECSCUSR (0–6) | `EmulationCoverageTest`, screenshots |
| Queries | DA1, DECID, DA2, DSR 5, DSR 6, DECXCPR, DECRQM (ANSI and DEC modes), DECRQSS (`m`, `r`, ` q`), XTVERSION, the flags of the kitty keyboard protocol (`CSI ? u`), XTWINOPS 14 (with 0 or 2) and 16 (sizes in pixels, section 9), XTWINOPS 18, `?` of OSC 4, 10, 11, and 12 | `EmulationCoverageTest`, `GraphicsTest`, the gate's `RepliesAndMouseTest`, `AttackTest` |
| OSC | 0, 2 (title), 8 (hyperlinks), 52 (clipboard; the policy is in section 4.1 of [gate-policy.md](gate-policy.md)) | `EmulationCoverageTest`, `AttackTest` |
| Images | The kitty graphics protocol (APC `G`), section 9 | `GraphicsTest`, `GraphicsRecordingTest`, `ResourceExhaustionTest`, the gate's `RepliesAndMouseTest` and `AttackTest`, screenshots |
| Title stack | 0 and 2 of XTWINOPS 22 (save) and 23 (restore). Only titles are pushed. The limits on depth and length are in section 10 | `EmulationCoverageTest.SavedTitles` |
| Mouse | Tracking (9, 1000, 1002, 1003) and encodings (default, 1005, 1006, 1015). The gate assembles the reports (`HostWriter.sendMouse`); touch and pointer input are mapped to them as in [decisions/0012](../decisions/0012-touch-and-selection.md) and [decisions/0031](../decisions/0031-desktop-pointer-and-terminal-state.md) | `RepliesAndMouseTest` |
| Keyboard | The kitty keyboard protocol: `CSI = flags ; mode u`, `CSI > flags u`, `CSI < n u`, and `CSI ? u`, with a stack of flags for each screen. Only flags 1 (disambiguate escape codes) and 4 (report alternate keys) are kept and acted on: the stacks drop the other flags, so the query reports only what vtfx does. As in kitty, an omitted mode of `CSI =` is 1 and an omitted count of `CSI <` is 1, while 0 sets nothing and pops nothing; a full stack drops its oldest entry (section 10); RIS and DECSTR empty both stacks. The gate encodes the keys (section 2 of [gate-policy.md](gate-policy.md), [decisions/0083](../decisions/0083-gate.md)) | `EmulationCoverageTest.KeyboardFlags`, the gate's `KeyEncoderTest` |
| Synchronized output | 2026. While it is set, drawing is held back, and drawing happens when it is reset. If it is not reset within 200 ms of the first output held back, drawing happens even if output continues (`TerminalDisplay` in `vtfx-compose`) | `TerminalSessionTest`, `TerminalDisplayTest` |

The replies to queries are built by `vtfx-gate` from the state, never from the request (section 3 of [gate-policy.md](gate-policy.md)).

## 4. Not supported

| Feature | Handling |
|---|---|
| Left and right margins (DECLRMM 69, DECSLRM) | Ignored; DECRQM reports DECLRMM as reset |
| 132-column mode (DECCOLM 3) | Ignored; DECRQM reports reset. The terminal size is determined by the display size |
| Reports of the title and icon name (XTWINOPS 20, 21) | Not answered |
| Icon name (OSC 1), a title stack index (the third parameter of XTWINOPS 22, 23), and 1 of XTWINOPS 22, 23 (icon name only) | Ignored; requests with a third parameter are ignored as a whole |
| Checksums of the screen contents (DECRQCRA) | Not answered. The esctest harness computes them in the test code (section 11 of [testing.md](testing.md)) |
| Setting colors (setting OSC 4, 10, 11, 12; OSC 104, 110–112) | Ignored. The colors come from the app's style; queries are answered |
| Protection attribute for selective erase (DECSCA) | Ignored. DECSED and DECSEL are normal erases |
| Drawing blinking characters (SGR 5, 6) | The model keeps the attribute, but the display does not blink the text. The cursor blinks when the host asks for it (DECSCUSR, mode 12; [decisions/0012](../decisions/0012-touch-and-selection.md)) |
| Printing (MC), sounds other than the bell, image protocols other than kitty's, such as sixel | Ignored |
| C1 control characters (8-bit) | Ignored (section 2) |
| XTGETTCAP (DCS + q) | Not answered |
| Rectangle editing and column operations (DECCRA, DECERA, DECFRA, DECSERA, DECIC, DECDC, DECBI, DECFI) | Ignored |
| Reverse wraparound (DECSET 45), the more(1) workaround (DECSET 41), saving and restoring DEC modes (XTSAVE, XTRESTORE) | Ignored; BS and CUB stop at the left edge |
| Conformance level (DECSCL), DSR extensions (printer, UDK, keyboard, locator, and so on) | Ignored |
| Setting the modes KAM, SRM, DECBKM, DECHEBM, DECNRCM, DECPEX, DECPFF, DECSCLM, DECCOLM, DECLRMM | Ignored. DECRQM reports them as reset, and DECHCCM and some ANSI modes as permanently reset, as xterm does |
| The mouse pointer shape (OSC 22), the cursor shape by OSC 50 (`CursorShape=`; the font in xterm), urgency hints (DECSET 1042) | Ignored |

The reasons for each are in [decisions/0079](../decisions/0079-emulation-scope.md). The esctest2 cases that fail because of these are listed with their reasons in `tools/esctest/reasons.toml`.

## 5. Deviations from the references

| Item | Reference | vtfx | Reason |
|---|---|---|---|
| OSC 52 data that is not base64 | xterm clears the selection | Ignored | [decisions/0079](../decisions/0079-emulation-scope.md) |
| REP count | No limit | Up to the number of cells on the screen | [decisions/0081](../decisions/0081-resource-limits.md) |
| OSC, DCS, and APC beyond the string limit | Not specified | The whole string is dropped | [decisions/0081](../decisions/0081-resource-limits.md) |
| 1 of XTWINOPS 22, 23 (icon name only) | Pushes an entry with only the icon name. Pop takes the top entry and fills missing values from the entries below | Does nothing, as in VTE. 0 and 2 push only the title. With usage like vim's, which saves and restores the title and the icon name separately, the resulting title is the same as in xterm | [decisions/0079](../decisions/0079-emulation-scope.md) |
| Limit of the title stack | A ring of 10 | 10, and a total length up to the string limit; beyond that, the oldest are dropped | [decisions/0081](../decisions/0081-resource-limits.md) |
| DECSCUSR 0 | Blinking block (ctlseqs) | Returns to the app's default (`TerminalStyle.cursorShape`, `cursorBlinking`), as in VTE, kitty, and Windows Terminal. RIS does the same. The app's default is shown until the host sets DECSCUSR 1–6 or mode 12 | [decisions/0079](../decisions/0079-emulation-scope.md) |
| Window title (OSC 0, 2) | Passed as sent | Cut to 1024 UTF-16 units, without C0 and C1 controls, DEL, U+2028, U+2029, and the bidirectional formatting characters, so that an app can show it outside the terminal | [decisions/0081](../decisions/0081-resource-limits.md) |

The deviations of the syntax are in section 2.

## 6. TERM and terminfo

vtfx has no connection to the host, so the app sets `TERM` (SSH pty-req, the environment of a PTY). The recommended values are `TERM=xterm-256color` and `COLORTERM=truecolor`. vtfx does not distribute a terminfo entry of its own ([decisions/0079](../decisions/0079-emulation-scope.md)).

The support for the features of `xterm-256color` (Debian, ncurses 6.5) is as follows.

| terminfo feature | Status |
|---|---|
| Basic movement and editing (`cup`, `csr`, `ich`, `dch`, `il`, `dl`, `ech`, `ed`, `el`, `el1`, `rep`, `indn`, `rin`, `hpa`, `vpa`, `cbt`, `tbc`, `hts`) | Supported |
| `am`, `xenl`, `bce`, `mir`, `msgr` | Supported (`xenl` is the pending wrap, `bce` is erasing with the background color) |
| Character attributes (`bold`, `dim`, `sitm`, `smul`, `blink`, `rev`, `invis`, `smso`, `sgr`, `sgr0`, `setaf`, `setab`, `colors#256`) | Supported (blinking is not drawn) |
| `smacs`, `rmacs`, `acsc` | Supported (DEC Special Graphics) |
| `smcup`, `rmcup` (1049 and the title stack) | Supported (the title stack pushes only titles, section 3) |
| `civis`, `cnorm`, `cvvis`, `Ss`, `Se` | Supported |
| `smkx`, `rmkx`, key capabilities (`kcuu1` and so on, keys with modifiers, function keys) | Supported for cursor keys, editing keys, F1–F12, and the keypad (including `kent` and the like in application mode) |
| `kmous`, `XM` (SGR mouse) | Supported |
| `BE`, `BD`, `PS`, `PE` (bracketed paste) | Supported |
| `fe`, `fd` (focus events) | Supported |
| `Ms` (OSC 52) | Supported; reading and writing are denied by default, and writing has a limit when the app allows it (section 4.1 of [gate-policy.md](gate-policy.md)) |
| `RV`, `XR`, `u6`–`u9` (queries) | Supported |
| `E3` (erasing the scrollback) | Supported |
| `flash` (toggling DECSCNM) | Supported |
| `smglr`, `smglp`, `smgrp`, `mgc` (left and right margins) | Not supported (section 4) |
| `initc`, `oc`, `Cs`, `Cr` (setting colors) | Not supported; ignored (section 4) |
| `smm`, `rmm` (meta key, 1034) | Ignored. Alt is sent as an ESC prefix |
| `mc0`, `mc4`, `mc5` (printing) | Not supported; ignored |

## 7. Unicode and character width

The policy is in [decisions/0079](../decisions/0079-emulation-scope.md).

### 7.1 Width rules

- `CharWidth` gives the width of a code point from tables generated from the Unicode Character Database 16.0.0 in `third_party/unicode` by `scripts/generate_unicode_tables.py`. The generated `UnicodeWidthTables.kt` is committed, and `scripts/check.sh` fails if it is not what the script generates ([decisions/0029](../decisions/0029-import-without-gradle.md)). The rules are those of glibc's `utf8_gen.py`:
  - Width 0: general categories Mn, Me, and Cf (except U+00AD), Hangul medial vowel and final consonant jamo (U+1160–U+11FF, U+D7B0–U+D7FF), and U+200B.
  - Width 2: East_Asian_Width W or F, including unassigned code points in the CJK ranges (the `@missing` defaults).
  - East Asian Ambiguous (East_Asian_Width A): width 1 by default, 2 with `AmbiguousWidth.Wide` (an argument of `Terminal` and `TerminalSession`). U+10EEEE, the placeholder of the kitty graphics protocol, is width 1 either way (section 9.7).
  - Everything else has width 1.
- To move to another Unicode version, replace the UCD files as [third_party/unicode/README.md](../../third_party/unicode/README.md) describes and run the script.

### 7.2 Grapheme clusters

- Widths are counted per code point, to match the sum of `wcwidth()` on the host. A zero-width character (combining characters, ZWJ, variation selectors) is attached to the character in the preceding cell; with nothing there, it is dropped. A cell holds at most 32 UTF-16 units, the character included (section 10).
- A sequence of emoji joined with ZWJ takes the cells of each emoji.
- A pair of regional indicators (a flag) is combined into one character two cells wide, which is the width the two indicators take anyway. They are not combined across the end of a line (with a pending wrap), in insert mode, or across different hyperlinks.
- Mode 2027 (counting a grapheme cluster as one unit) is not supported.
- Editing keeps a wide character whole. An operation that splits one (printing over, erasing, or deleting one half of it, or inserting cells at its right half) erases both halves. ICH and insert mode at its left half shift it whole, as in xterm; one pushed past the right edge by half is dropped.

### 7.3 Drawing of characters

- Box-drawing (U+2500–U+257F) and block elements (U+2580–U+259F) are drawn as shapes by `BoxDrawing` in `vtfx-compose`, not with font glyphs, so that they join across cells. Light, heavy, double, rounded-corner, diagonal, and dashed lines (drawn as solid) are drawn. Most of them are East Asian Ambiguous: when they are two cells wide (`AmbiguousWidth.Wide`), the shape fills both cells, so that lines join and run through the middle of the character.
- CJK characters are drawn with Android's system font fallback. The glyphs of Han characters follow `TerminalStyle.locale`, which defaults to the system locale.
- A glyph may reach beyond its cell: combining marks stacked on a character, or a glyph wider than its cell (U+FDFD, cuneiform). Within the `Terminal`, this is left as fonts draw it; the `Terminal` clips its drawing to its bounds, padding included, so that what the host writes never draws over the app's UI around it.

## 8. Character attributes and decorations

The underline color, the drawing of underlines and overlines, and the cursor's text color are explained in [decisions/0079](../decisions/0079-emulation-scope.md).

### 8.1 The rendition and the cells

- The current rendition is a `Rendition`: a `CellStyle` (64 bits: two colors, the underline style, and the flags) and the underline color.
- SGR 58 takes the same forms as 38 and 48 (`58;5;N`, `58;2;R;G;B`, `58:5:N`, `58:2::R:G:B`, `58:2:R:G:B`). SGR 59 and SGR 0 reset it, and so do DECSTR and RIS. DECSC and DECRC save and restore it with the rest of the rendition.
- A line keeps the underline colors of its cells in a side array (`IntArray` of `TerminalColor` bits, 0 for none), allocated when the first color is written. A line without underline colors has no array, and a scrollback line keeps one only if a cell has a color. The array follows the cells through every edit, reflow, and the scrollback. The scrollback's memory budget counts it (section 10).
- A cell gets the underline color only while the rendition underlines, so a color left set after the underline ends takes no memory. Erased cells have no underline color. The exception is the placeholder of an image, which keeps the underline color without an underline, since it gives the placement id (section 9.7).
- `LineSnapshot.underlineColorAt(column)` exposes it; `TerminalColor.Default` means the color of the text. `contentEquals` compares it.
- DECRQSS for SGR reports it as `58;5;N` or `58;2;R;G;B`, and reports the rendition's color even if the rendition does not underline.

### 8.2 Drawing

- `TerminalRenderer` draws underlines and overlines as shapes over whole cells (`drawDecorations`). Spaces written with an underline are underlined too, as in xterm. Invisible cells (SGR 8) are not decorated. Under a focused block cursor, the decorations are drawn again over the block, in the color of the text under the cursor.
- Position and thickness come from the font (`Paint.underlinePosition` and `underlineThickness`, at least 1 px). Single is one line; double is two lines, two thicknesses apart; curly is a wave with one period per cell; dotted is dots one thickness long; dashed is one dash per cell. The patterns are anchored to the cell grid, so that runs drawn separately join. Decorations that would leave the cell are moved up.
- The underline is drawn in the cell's underline color if it has one, else in the color of the text (with faint and inverse applied). Overline and strikethrough use the color of the text.
- OSC 8 hyperlinks are underlined with a single line if the cell has no underline of its own.

### 8.3 The cursor's text color

- `TerminalColors.cursorText` is the color of the text, and its decorations, under a focused block cursor. The default `Color.Unspecified` takes the default background color.
- In reverse video (DECSCNM) it is ignored: the cursor takes the default foreground and the text the default background.
- The host cannot set it (section 4); OSC 12 queries report `cursor`.

## 9. Images: the kitty graphics protocol

vtfx implements the kitty graphics protocol (kitty 0.48) with direct transmission. It is on by default, and `TerminalLimits.maxImageBytes = 0` turns it off: APC strings are then ignored and queries are not answered. The reasons are in [decisions/0080](../decisions/0080-kitty-graphics.md). The code is in the `graphics` package of `vtfx-core`, and the images that the display draws are in `TerminalSnapshot` as internal API (`@InternalVtfxApi`).

### 9.1 Scope

| Supported | Not supported |
|---|---|
| Transmission of direct data (`t=d`), in chunks (`m`) | Files, temporary files, and shared memory (`t=f`, `t`, `s`): refused with `EBADF` |
| Formats RGB, RGBA, and PNG (`f=24`, `32`, `100`), with or without zlib (`o=z`) | Animation (`a=f`, `a`, `c`): refused with `EINVAL` |
| Actions transmit, transmit and display, put, query, delete (`a=t`, `T`, `p`, `q`, `d`) | Relative placements (`P`, `Q`, `H`, `V`): the keys are ignored and the placement is placed at the cursor |
| Image ids and numbers (`i`, `I`), placement ids (`p`), source rectangles (`x`, `y`, `w`, `h`), cell offsets (`X`, `Y`), sizes in cells (`c`, `r`), z-index (`z`), cursor policy (`C`), quiet (`q`) | Usage hints (`N`): ignored |
| Every kind of deletion (`d=a` to `Z`), except of animation frames (`d=f`, `F`, which do nothing) | |
| Unicode placeholders (`U=1` and U+10EEEE) | |
| Pixel sizes: XTWINOPS 14 (the text area; also for 14;2) and 16 (a cell), and the pixel size given to the app for the host's window size | XTWINOPS 15 (the screen) and 14;1 are not answered |

### 9.2 Commands and replies

- APC strings that start with `G` are graphics commands; other APC strings are ignored.
- The control data follows kitty's parser: a key that kitty does not know, a value that is not a number where one is expected, a number beyond 32 bits, or an unknown action makes the whole command ignored without a reply.
- The payload is base64 with or without padding. Each chunk is decoded on its own, up to its first character that is not base64.
- A command other than a continuation ends a transmission that did not finish. RIS abandons it.
- A reply is sent only when the command has an image id (`i`) or number (`I`); `q=1` suppresses `OK`, and `q=2` every reply. Chunks before the last are not answered.
- A reply carries the numbers of the command (for an image number, also the id vtfx gave the new image) and one of a fixed set of statuses: `OK`, `EINVAL`, `ENOENT`, `ENODATA`, `EBADPNG`, `EFBIG`, `EBADF`, each with a constant message. No reply contains text from the host. `vtfx-gate` encodes them like the other replies (`TerminalQuery.GraphicsReply`).

### 9.3 Decoding

- PNG is decoded by `PngDecoder` in `vtfx-core`, in Kotlin. It decodes every color type, bit depth, and interlacing, and checks the size in the header against the limits before it allocates anything. Colors are taken as sRGB, ignoring color profiles and gamma.
- zlib data (`o=z`, and the image data of PNG) is inflated with `java.util.zip.Inflater`, up to the size the image needs.
- Pixels are kept decoded, 4 bytes each. The display makes a bitmap of an image when it first draws it, and keeps it for a while after the image is no longer drawn (section 9.6).

### 9.4 Storage and eviction

- Each screen has its own images and placements, so the images of the main screen stay while a program runs on the alternate screen. The memory limit counts both screens together (section 10).
- When a new image does not fit, the oldest images go first, those without placements before those with them. An image larger than the limit by itself, or larger than 64 MiB of decoded pixels whatever the limit ([decisions/0080](../decisions/0080-kitty-graphics.md)), is refused with `EFBIG`. Beyond 1024 images on a screen, the oldest goes; beyond 1024 placements, the oldest placement goes.
- An image without an id or number goes with its last placement.
- A transmission that is still in chunks when the screen changes (entering or leaving the alternate screen) is refused with `EINVAL` when it ends.

### 9.5 Placements and the text

- A placement is anchored to the cell at the cursor, also when a wrap is pending: to the line object, so that it moves with its line when the screen scrolls, in a scrolling region too, when lines are inserted or deleted, and into the scrollback. When its line leaves the scrollback, or scrolls off the top of the screen without going to the scrollback (the alternate screen, or no scrollback), the placement moves down to the line that takes its place, as long as rows of the image are left on that line or below; the rows above are cut when it is drawn ([decisions/0080](../decisions/0080-kitty-graphics.md)). When a line is deleted without going to the scrollback otherwise (scrolling a region below the top, deleted lines), the placement goes with it. A placement deleted while its line is in the scrollback is also removed from that line, so that deleted placements do not take memory beyond the limit on placements. The rows of the image below its anchor are drawn over whatever is there.
- When the size changes, an anchor moves with the cell it is on, and a line that has only an anchor is kept as if it had content. The image is not rewrapped: it is drawn from its anchor and cut at the right edge. Placements on lines that leave the scrollback when the size changes, or the screen without a scrollback, move down as when scrolling. On the alternate screen, anchors beyond the new width go.
- Clearing the screen (ED 2) and RIS delete the placements visible on the screen; leaving the alternate screen deletes the alternate screen's images and placements. Other erasing (ED 0 and 1, EL, ECH, DCH) leaves images alone. ED 3 deletes the placements in the scrollback with it.
- The size in cells of a placement without `c` and `r` is its size in pixels (plus the cell offset) divided by the size of a cell, rounded up, taken when it is placed. The display gives the size of a cell to the session (`TerminalSession.resize`); until it does, placements take 10 × 20 pixels, and XTWINOPS 14 and 16 are not answered. Once placed, a placement keeps its size in cells, and the image is scaled with the cells if the font changes.
- Unless `C=1`, the cursor moves after a placement as in kitty: to the column after the image, on the image's last row; to the start of the next row if that column is past the right edge; scrolling the region if that row is below the bottom margin.

### 9.6 Drawing

- Images with a negative z-index are drawn under the text and under the backgrounds of cells, the others over the text, before the selection and the cursor.
- An image is drawn from its anchor, scaled to its size in cells, and cut at the edges of the grid.
- Each display (a session shown by a `Terminal`) keeps its own bitmaps, since each session numbers its images from 1.
- A display keeps the bitmap of an image that it no longer draws, scrolled out of view or deleted, for 60 frames, so that an image scrolled back into view is not made again; of these, it keeps at most 32 MiB, dropping the least recently drawn first (`ImageBitmaps.MAX_UNDRAWN_BYTES`), so that a host that replaces an image on every frame does not build up bitmaps beyond the limit of the core ([decisions/0080](../decisions/0080-kitty-graphics.md)). The bitmaps of the images drawn in a frame are bounded by `maxImageBytes`.

### 9.7 Unicode placeholders

- U+10EEEE is one cell wide even when East Asian Ambiguous characters are wide. The diacritics that encode its row, column, and the high byte of the image id are combining characters, kept in the cell's cluster like others.
- The table of diacritics (`PlaceholderDiacritics.kt`) is generated from `third_party/unicode` by `scripts/generate_unicode_tables.py`, by kitty's rule (general category Mn, combining class 230, without decomposition, without 19 characters that normalization can join), limited to characters of Unicode 6.0 or earlier with `DerivedAge.txt`. This gives the same 297 characters in the same order as kitty's `rowcolumn-diacritics.txt`.
- The foreground color gives the image id (8 bits for an indexed color, 24 for a direct color, and the high byte from the third diacritic); the underline color gives the placement id. Missing diacritics are inferred from the cell to the left, by the rules of the protocol.
- The display draws a placeholder cell as the part of the image that its row and column select, with the image fitted into the virtual placement's columns and rows keeping its aspect ratio, and centered in them.
- Placeholder cells are not text: `LineSnapshot.textAt` and `text()` report them as blank, so that copying, accessibility, and searching do not see private use characters.

### 9.8 API

- `TerminalLimits.maxImageBytes` sets the memory of the images (section 10).
- `TerminalSession.resize` takes the size of a cell in pixels, which a Compose `Terminal` passes. `TerminalSessionListener.onResize` has an overload with the size of the text area in pixels, which calls the overload without it by default. The app passes the pixels on to the host (SSH window-change, TIOCSWINSZ).

## 10. Resource limits

Host output is untrusted (requirement 6.1). The policy and the reasons for each limit are in [decisions/0081](../decisions/0081-resource-limits.md); the limits of images are explained in [decisions/0080](../decisions/0080-kitty-graphics.md).

### 10.1 Policy

- Memory used because of host output is bounded by the configured limits. Input beyond a limit is silently truncated or dropped, never an exception or a crash.
- Processing time is linear, with a small constant factor, in the number of input bytes. A few bytes of input do no work beyond the size of the screen.
- For notifications to the main thread caused by host output (redraws, following the scrollback, restarts of the IME's input), at most one of each kind waits for the main thread. The session reports bells and titles to the app at most once per `feed`, on the thread that called it; an app that passes them to the main thread coalesces them itself ([usage.md](../usage.md)).
- Values given by the app (the screen size, `TerminalLimits`) out of range are programming errors, and `IllegalArgumentException` is thrown.

### 10.2 Limits

| Target | Limit | Setting | When exceeded |
|---|---|---|---|
| Columns, rows | 2048 | Not configurable (`TerminalLimits.MAX_DIMENSION`) | `resize` and the like throw (an error of the app) |
| Scrollback lines | 10,000 by default (at most 1,000,000) | `TerminalLimits.scrollbackLines` | The oldest lines are dropped |
| Scrollback memory | 96 bytes per line and 13 bytes per cell, for `scrollbackLines` lines of the current width, by the estimate of `CompactLine` (15.8 MiB for 10,000 lines of 120 columns) | Follows `scrollbackLines` and the width (`ScreenBuffer.BYTES_PER_LINE`, `BYTES_PER_CELL`) | The oldest lines are dropped, even before there are `scrollbackLines` of them. The newest line is kept even if it alone is over the budget |
| Length of OSC, DCS, and APC strings | 256 Ki UTF-16 units by default (at most 16 Mi) | `TerminalLimits.maxStringLength` | The whole string is dropped (not truncated and executed) |
| Number of parameters | 32 | Not configurable (`Params.MAX_COUNT`) | The 33rd and later are dropped |
| Parameter values | 65535 | Not configurable (`ProtocolLimits.MAX_PARAMETER`) | Set to 65535 |
| Text of a cell (a character and its combining characters) | 32 UTF-16 units | Not configurable (`Line.MAX_CLUSTER_LENGTH`) | Later combining characters are dropped |
| REP (`CSI Ps b`) count | The number of cells on the screen (columns × rows). In insert mode, the cells for the characters on each row are inserted at once, so the work stays within the screen | Not configurable | The count is truncated |
| Length of OSC 8 URIs | 2083 characters (the recommendation of the OSC 8 specification) | Not configurable (`Emulator.MAX_URI_LENGTH`) | Not made a link |
| Length of OSC 8 ids | 250 UTF-16 units (as VTE limits it) | Not configurable (`Emulator.MAX_LINK_ID_LENGTH`) | Not made a link |
| Length of the window title | 1024 UTF-16 units, after removing the characters of section 5 | Not configurable (`Emulator.MAX_TITLE_LENGTH`) | Cut, without splitting a surrogate pair |
| Entries of a stack of the kitty keyboard protocol's flags | 8 per screen, as in kitty | Not configurable (`KeyboardFlagStack.MAX_DEPTH`) | The oldest entry is dropped |
| Saved titles (XTWINOPS 22) | 10, with a total length up to the string limit, counted in UTF-16 units | The count is not configurable (`Emulator.MAX_SAVED_TITLES`); the length follows `TerminalLimits.maxStringLength` | The oldest are dropped |
| Hyperlinks held at once | 4096 | Not configurable (`Emulator.MAX_HYPERLINKS`) | Links not referenced by any cell are dropped, and if that is still not enough, the new link is not attached. The search for referenced links (every cell of the screens and the scrollback) happens at most once per 2048 requests for new links; if the table is full in between, new links are not attached |
| Pixels of the images of the kitty graphics protocol | 32 MiB of decoded pixels (4 bytes each) by default, for both screens together (at most 1 GiB; 0 turns the protocol off) | `TerminalLimits.maxImageBytes` | The oldest images are dropped, those without placements first. An image larger than the limit by itself is refused with `EFBIG` |
| Data of a transmission in progress | `maxImageBytes` of decoded base64 | Follows `maxImageBytes` | Refused with `EFBIG` when it ends |
| Width and height of an image | 10000 pixels each (as kitty) | Not configurable (`Graphics.MAX_SIDE`) | Refused with `EFBIG` before anything is allocated |
| Pixels of one image | 64 MiB of decoded pixels (16,777,216 pixels), or `maxImageBytes` if smaller, below the size of a bitmap that Android refuses to draw ([decisions/0080](../decisions/0080-kitty-graphics.md)) | Not configurable (`Graphics.MAX_BYTES_PER_IMAGE`) | Refused with `EFBIG` before the pixels are allocated |
| Images, placements | 1024 of each per screen | Not configurable (`Graphics.MAX_IMAGES`, `MAX_PLACEMENTS`) | The oldest are dropped |
| Columns and rows of a placement, and the width and height of the area it is drawn in | `TerminalLimits.MAX_DIMENSION` cells | Not configurable | Clamped, also a side computed from the image's aspect ratio when only `c` or `r` is given |
| Bitmaps of images that a display no longer draws | 32 MiB per display, each for 60 frames | Not configurable (`ImageBitmaps.MAX_UNDRAWN_BYTES`, `KEEP_FRAMES` in `vtfx-compose`) | The least recently drawn are dropped (section 9.6) |

- String lengths are counted in UTF-16 units, which bound the memory of the held strings directly.
- Commands of the graphics protocol that look at every placement (deletions by position, clearing the screen) do work up to 1024 placements for a few bytes of output.
- Decoding an image takes memory beyond its pixels until the command ends: the data of the transmission (up to `maxImageBytes`), the data inflated from `o=z` (up to the same), and for PNG the inflated image data, up to 8 bytes per pixel (16-bit RGBA) and a byte per row, about twice the pixels of the image and so at most about 128 MiB (`Graphics.MAX_BYTES_PER_IMAGE`). The images already kept stay meanwhile.
- Resolving the Unicode placeholders of a snapshot looks up the virtual placement of each cell in a table made once per image, so the work is linear in the cells and the placements, not their product.
- The tests of the limits are listed in section 12 of [testing.md](testing.md).

## 11. Verification

- The spec tests in `spec/core` (section 3 of [testing.md](testing.md)) and the unit tests named in section 3 check the behavior. `EmulationCoverageTest` groups tests by the features of section 3.
- esctest2 runs against vtfx for reports, not as a gate (section 11 of [testing.md](testing.md)). The reasons for failing cases are in `tools/esctest/reasons.toml`.
- The output of real programs is recorded and replayed (section 10 of [testing.md](testing.md)).
