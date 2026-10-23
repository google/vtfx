# A limit on the size of one image

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-22
- Related: Requirements 6.1 and 6.5, sections 9.4 and 10.2 of [emulation.md](../design/emulation.md), [0002](0002-resource-limits.md), [0038](0038-kitty-graphics.md) (which this supplements), `Graphics.MAX_BYTES_PER_IMAGE`, `ImageBitmaps` in `vtfx-compose`

## Background

[0038](0038-kitty-graphics.md) bounds the images of the kitty graphics protocol by one value, `TerminalLimits.maxImageBytes`, which an app may set up to 1 GiB. One image could take all of it, up to 10000 by 10000 pixels (`Graphics.MAX_SIDE`), 400 MB decoded.

The display makes one `ARGB_8888` bitmap of the same size for each image and draws it on a hardware-accelerated canvas. Android's `RecordingCanvas` throws when it is asked to draw a bitmap of more than 100 MiB (or more, if the device says so), so with `maxImageBytes` above 100 MiB a host could crash the app with one image of about 5200 by 5200 pixels, sent as a few hundred kilobytes of compressed data. That is a crash from untrusted input (requirement 6.1) at a setting that the API allows. On 2026-10-22 a human (nya) decided that the core bounds the size of one image, and that the range of `maxImageBytes` stays as it is.

## Decision

1. **One image takes at most 64 MiB of decoded pixels (16,777,216 pixels at 4 bytes each)**, whatever `maxImageBytes` is (`Graphics.MAX_BYTES_PER_IMAGE`). A larger image is refused with `EFBIG`, as an image larger than `maxImageBytes` already is, before its pixels are allocated: from the size in the command for RGB and RGBA, and from the PNG header for PNG.
2. **`maxImageBytes` keeps its range** (0 to 1 GiB) and stays the budget of all images of both screens together. Above 64 MiB it lets more images be kept at once, not larger ones.
3. **The limit is not configurable and not public API**, like `Graphics.MAX_SIDE`: it follows from what the display can draw, not from what an app wants to spend.

64 MiB is below the 100 MiB at which Android refuses to draw a bitmap, with room for the display to change the bitmap's format, and above every image of the default setting (32 MiB), so the default behavior does not change. 16,777,216 pixels are 4096 by 4096, larger than the screens of the devices vtfx runs on, or 5461 by 3072 for a wide image.

## Alternatives considered

- **Scale the display's bitmaps down** (in `vtfx-compose`) instead of limiting the core: the core would still hold up to 400 MB for one image, which the display would never show at that size, and every display would need the same care.
- **Lower the largest `maxImageBytes` to 64 MiB or 100 MiB**: also avoids the crash, but takes away the setting for apps that want many images kept, and changes the public API's range.
- **Lower `Graphics.MAX_SIDE`**: a square limit would still allow a 10000 by 10000 image's worth of a long strip, or refuse wide images that fit in memory; bounding the pixels bounds the memory directly.

## Impact of reversing

Remove `Graphics.MAX_BYTES_PER_IMAGE` and compute the largest number of pixels in `Graphics.decode` from `maxImageBytes` alone, and remove the row from section 10.2 of [emulation.md](../design/emulation.md). The display would then have to scale large images down, or the crash comes back with `maxImageBytes` above 100 MiB.
