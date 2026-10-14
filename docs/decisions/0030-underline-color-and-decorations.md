# Underline color, underline styles, overline, and the cursor's text color

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-14
- Related: Requirement 5.1, 6.2, 6.5, [0002](0002-resource-limits.md), [0009](0009-emulation-scope.md), [0014](0014-performance.md), `Rendition` and `Line` in `vtfx-core`, `TerminalRenderer` in `vtfx-compose`

## Background

Some attributes that programs use were not drawn: curly and dotted underlines, the underline color (SGR 58), and overline. The color of the text under the cursor could not be set either.

- `vtfx-core` already parsed the underline styles (SGR 4:0 to 4:5) and overline (SGR 53, 55), but the display drew every underline as a single line with `Paint.isUnderlineText`, under the glyphs only, and did not draw overlines.
- [0009](0009-emulation-scope.md) left the underline color unsupported, because the 64 bits of `CellStyle` are full: 26 bits for each of the two colors, 3 for the underline style, and 8 flags leave one bit, while a color takes 26.
- The text under a block cursor was always drawn in the default background color, which an app could not change.

## Decision

### Underline color in the core

- SGR 58 takes the same forms as 38 and 48 (`58;5;N`, `58;2;R;G;B`, `58:5:N`, `58:2::R:G:B`, `58:2:R:G:B`). SGR 59 and SGR 0 reset it, and so do DECSTR and RIS. DECSC and DECRC save and restore it with the rest of the rendition.
- The current rendition is a `Rendition` (the `CellStyle` and the underline color) instead of a `CellStyle` alone. `CellStyle` does not change.
- Cells keep the underline color in a side array of the line (`IntArray`, `TerminalColor` bits, 0 for none), allocated when the first color is written, as the hyperlink ids already are. A line without underline colors has no array, and a scrollback line keeps one only if a cell has a color. The array follows the cells through every edit, reflow, and the scrollback.
- A cell gets the color only while the rendition underlines. A color left set after the underline ends (common, since `SGR 24` does not reset it) takes no memory, and a cell never carries a color it does not show. Erased cells have no color, as they have no underline (xterm's BCE keeps only the background).
- `LineSnapshot.underlineColorAt(column)` exposes it; `TerminalColor.Default` means the color of the text. `contentEquals` compares it, so the display's line cache redraws a line whose underline color changed.
- DECRQSS for SGR reports it as `58;5;N` or `58;2;R;G;B`, in the `;` form that the reply already uses for 38 and 48. It reports the rendition's color even if the rendition does not underline, since that is what SGR set.

Memory: the cost is 4 bytes per cell, only in lines that use the color. The scrollback's budget ([0002](0002-resource-limits.md)) counts the array in `CompactLine.estimatedBytes`, so the worst case stays within the budget; lines with an underline color and a different color in every cell keep fewer lines, as lines with hyperlinks already do. Output without SGR 58 costs nothing more: one null check per written cell, and none in the comparison of lines without arrays. Speed of plain text ([0014](0014-performance.md)) is unaffected beyond that check.

### Drawing

- Underlines and overlines are drawn by `TerminalRenderer` as shapes over whole cells, instead of with `Paint.isUnderlineText` under the glyphs. Spaces written with an underline are underlined too, as in xterm, so that apps that underline a whole row (vim's `cursorline`, status lines) show it to the end. Invisible cells (SGR 8) are not decorated. Under a focused block cursor, the decorations are drawn again over the block, in the color of the text under the cursor.
- Position and thickness come from the font (`Paint.underlinePosition` and `underlineThickness`, at least 1 px), as before. Single is one line; double is two lines, 2 thicknesses apart; curly is a wave with one period per cell; dotted is dots one thickness long; dashed is one dash per cell. The patterns are anchored to the cell grid, so that runs drawn separately join. The decorations are moved up when they would leave the cell.
- The underline color is the cell's underline color if set, else the color of the text (with faint and inverse applied). Overline and strikethrough use the color of the text.
- OSC 8 hyperlinks are underlined with a single line if the cell has no underline of its own; a cell's own underline style wins. Links that patterns find are drawn over the cached lines as before ([0019](0019-extensibility.md)).

### The cursor's text color

- `TerminalColors.cursorText` is the color of the text, and its decorations, under a focused block cursor. The default `Color.Unspecified` keeps the earlier behavior: the default background color.
- In reverse video (DECSCNM) it is ignored, as `cursor` is: the cursor takes the default foreground and the text the default background, so that the cursor stays visible.
- The host cannot set it: setting colors with OSC 10 to 12 remains unsupported ([0009](0009-emulation-scope.md)); OSC 12 queries keep reporting `cursor`.

## Alternatives considered

- **Pack the underline color into `CellStyle`**: no room. Widening `CellStyle` to two `Long`s would double the style memory of every cell, against the targets of [0014](0014-performance.md), for a feature most output does not use.
- **Intern colors and keep an index in the free bit of `CellStyle`**: one bit is not an index. Taking bits from the colors would break 24-bit color.
- **A side table keyed by an index in the style (as `CompactLine` keys clusters)**: more code than a per-cell array for little gain, since lines with underline colors are rare and the scrollback budget bounds the worst case.
- **Store the color in cells whether or not they are underlined**: simpler, but allocates arrays for apps that set SGR 58 once and then underline only sometimes, and makes `underlineColorAt` report colors that are not shown.
- **Keep `Paint.isUnderlineText` for single underlines**: it draws only under glyphs, so underlined spaces would show nothing, and a run of single and curly underlines would not line up.

## Impact of reversing

- To drop the underline color: remove `Rendition.underlineColor`, the side array in `Line` and `CompactLine`, and `LineSnapshot.underlineColorAt` (a public API change), and make SGR 58 skip its parameters again.
- To return to glyph-only underlines: replace `drawDecorations` in `TerminalRenderer` with `Paint.isUnderlineText`, and re-record the screenshot goldens.
- To drop `cursorText`: remove the property, which is a public API change.
