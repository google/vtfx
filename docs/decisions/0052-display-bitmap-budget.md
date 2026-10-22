# A budget for the bitmaps that a display keeps

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1, sections 9.3, 9.6, 10.1, and 10.2 of [emulation.md](../design/emulation.md), [0002](0002-resource-limits.md), [0014](0014-performance.md), [0038](0038-kitty-graphics.md), `ImageBitmaps` in `vtfx-compose`

## Background

The core keeps the pixels of images within `TerminalLimits.maxImageBytes`. A `Terminal` makes a bitmap of each image it draws and kept it until no frame had drawn it for 60 frames, so that an image scrolled out of view and back is not made again, and the bitmaps of deleted images go. The bitmaps were not bounded by bytes: a host that sends a new image on every frame and deletes the previous one keeps the core at one image, while the display keeps the bitmaps of the last 60, about 60 times `maxImageBytes`. zlib makes such images cheap to send, so a few megabytes of output could fill the native heap. Section 10.1 of emulation.md requires the memory used because of host output to be bounded by the configured limits.

## Decision

- **The bitmaps of images that the last frame did not draw are kept up to a fixed 32 MiB per display**, the least recently drawn going first, besides going after 60 frames as before (section 9.6 of [emulation.md](../design/emulation.md)). The bitmaps of the images that a frame draws are those of the snapshot, which the core bounds by `maxImageBytes`, so a display holds at most `maxImageBytes` plus 32 MiB of bitmaps.
- **A constant, not a multiple of `maxImageBytes`**: the session does not expose its limits to the display, and the bitmaps kept only spare making a bitmap again, which is a copy of pixels the core already holds, not a decode. 32 MiB is the default of `maxImageBytes`, enough to keep the images of a screen that scrolls a little.
- **Sizes are counted as 4 bytes a pixel** from the image, the size of an `ARGB_8888` bitmap, rather than read from the bitmap.

## Alternatives considered

- **Drop every bitmap that the frame did not draw**: the simplest bound, but an image that scrolls out of view and back, or that a frame in between does not draw, would be made again each time.
- **A budget of twice `maxImageBytes`**: follows the setting, but needs the limits of the session in the display, a new API, for no gain in safety over a constant.
- **`Bitmap.recycle` for the bitmaps that go**: frees the native memory sooner, but a bitmap may still be in a recorded line or frame, and the boundaries allow only `createBitmap` of `Bitmap`. The memory goes with the garbage collector.

## Impact of reversing

Removing the budget brings back memory beyond the configured limits. Changing the value changes only `ImageBitmaps.MAX_UNDRAWN_BYTES` and sections 9.6 and 10.2 of emulation.md.
