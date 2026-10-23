# Placements whose top row scrolls off the top

- Status: Provisional
- Date: 2026-10-23
- Related: sections 9.5 and 10 of [emulation.md](../design/emulation.md), [0038](0038-kitty-graphics.md), `Placement.rowsAbove` in `vtfx-core`

## Background

A placement is anchored to the line of its top row ([0038](0038-kitty-graphics.md)), and went with that line when the line went away. A line goes away without going to the scrollback on the alternate screen, with `scrollbackLines = 0`, and in a scrolling region below the top; and the oldest line goes when it leaves the scrollback. So a placement of several rows was deleted as soon as its top row scrolled off, while its other rows were still on the screen. A full-screen program on the alternate screen that places an image as wide and as tall as the screen at the top left lost it at once: the cursor moves past the bottom right of the image, which scrolls the screen by one line ([kitty-graphics-programs.md](../research/kitty-graphics-programs.md)). kitty keeps such an image while a row of it is still shown.

## Decision

- **When the line of a placement's top row scrolls off the top of the screen without going to the scrollback, or leaves the scrollback, the placement moves down to the line that takes that line's place** (the new top row, or the new oldest line of the scrollback) and remembers how many of its rows are above that line (`Placement.rowsAbove`). It goes once none of its rows are left. Its position, its size, and its source rectangle do not change: the rows above the top of the grid are cut when it is drawn, as for an image that starts in the scrollback. Section 9.5 of [emulation.md](../design/emulation.md) describes the result.
- **Lines deleted (DL) and lines scrolled off the top margin of a region below the top of the screen still take their placements.** Images are drawn from their anchor and not cut at the margins, so an image moved down to the region's new top row would be drawn over the rows above the region, which do not scroll, and would appear to move up over them on each scroll. Deleting lines does not scroll text off the screen.
- **The memory estimate of a scrollback line does not count placements moved to it.** The limit on placements bounds them, as it bounds the placements in the scrollback.

## Alternatives considered

- **Cut the image as its rows leave**, changing its source rectangle and size, as kitty does at the margins of a region: the source rectangle is in whole pixels, and a row of cells is rarely a whole number of pixels of the image, so the image would be scaled differently after each cut. It would also allow keeping placements in regions, but needs a source rectangle in fractions of pixels in `ImagePlacement`, an API change, for a case that programs that show images rarely meet.
- **Keep placements also in regions below the top, drawn over the rows above**: draws scrolled-out images over the fixed lines of a program, such as a status line.
- **Anchor placements to their bottom row instead**: the top row would then decide nothing, but erasing, deletion by position, and reflow all start from the top-left cell, and placements below the bottom of the screen would need another rule.

## Impact of reversing

Reversing brings back the deletion of an image as soon as its top row goes. It removes `Placement.rowsAbove` and its use in `GraphicsLayer.forEachAnchored`, the moving of anchors in `ScreenBuffer.scrollUp` and `Scrollback.add`, and `CompactLine.addAnchors`, and restores section 9.5 of emulation.md.
