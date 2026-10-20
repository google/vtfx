# Underline color, underline styles, overline, and the cursor's text color

- Status: Approved (2026-10-15, approved by a human in a review of the review queue)
- Date: 2026-10-14
- Related: Requirement 5.1, 6.2, 6.5, section 8 of [emulation.md](../design/emulation.md), [0002](0002-resource-limits.md), [0009](0009-emulation-scope.md), [0014](0014-performance.md), `Rendition` and `Line` in `vtfx-core`, `TerminalRenderer` in `vtfx-compose`

## Background

Some attributes that programs use were not drawn: curly and dotted underlines, the underline color (SGR 58), and overline. The color of the text under the cursor could not be set either.

- `vtfx-core` already parsed the underline styles (SGR 4:0 to 4:5) and overline (SGR 53, 55), but the display drew every underline as a single line with `Paint.isUnderlineText`, under the glyphs only, and did not draw overlines.
- [0009](0009-emulation-scope.md) left the underline color unsupported, because the 64 bits of `CellStyle` are full: 26 bits for each of the two colors, 3 for the underline style, and 8 flags leave one bit, while a color takes 26.
- The text under a block cursor was always drawn in the default background color, which an app could not change.

## Decision

The resulting behavior is in section 8 of [emulation.md](../design/emulation.md).

- **Underline color in the core**: support SGR 58 and 59 in the forms of 38 and 48. The current rendition becomes a `Rendition` (the `CellStyle` and the underline color); `CellStyle` does not change. Cells keep the underline color in a side array of the line, allocated only when a color is written, as the hyperlink ids already are. A cell gets the color only while the rendition underlines, so that a color left set after the underline ends (common, since `SGR 24` does not reset it) takes no memory, and a cell never carries a color it does not show. `LineSnapshot.underlineColorAt` exposes it, and DECRQSS reports it.
- **Memory**: the cost is 4 bytes per cell, only in lines that use the color. The scrollback's budget ([0002](0002-resource-limits.md)) counts the array, so the worst case stays within the budget; such lines keep fewer lines, as lines with hyperlinks already do. Output without SGR 58 pays one null check per written cell, and nothing in the comparison of lines without arrays, so the speed of plain text ([0014](0014-performance.md)) is unaffected beyond that check.
- **Drawing**: draw underlines and overlines as shapes over whole cells in `TerminalRenderer`, in every style, anchored to the cell grid, instead of `Paint.isUnderlineText` under the glyphs. Underlined spaces are underlined too, as in xterm, so that apps that underline a whole row (vim's `cursorline`, status lines) show it to the end.
- **The cursor's text color**: add `TerminalColors.cursorText`, the color of the text under a focused block cursor. The default keeps the earlier behavior. The host cannot set it: setting colors with OSC 10 to 12 remains unsupported ([0009](0009-emulation-scope.md)).

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
- In each case, update section 8 of [emulation.md](../design/emulation.md).
