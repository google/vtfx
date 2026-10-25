# Images: the kitty graphics protocol

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 4.1, 4.2, 5.1, 6.1, 6.2, and 6.5, sections 9 and 10 of [emulation.md](../design/emulation.md), section 3 of [gate-policy.md](../design/gate-policy.md), section 4.5 of [boundaries.md](../design/boundaries.md), section 3.1 of [session-and-threads.md](../design/session-and-threads.md), [0079](0079-emulation-scope.md), [0081](0081-resource-limits.md), [0083](0083-gate.md), [0084](0084-session-and-threads.md), [0089](0089-performance.md), [0087](0087-public-api.md), the kitty documentation (`graphics-protocol.rst`, kitty 0.48), [research/kitty-graphics-programs.md](../research/kitty-graphics-programs.md)

## Background

Programs that show images in the terminal (image viewers such as `kitten icat` and timg, `fastfetch`, file managers such as yazi, editors such as Neovim with snacks.nvim) use the kitty graphics protocol, and through tmux too, by its Unicode placeholders. What they send is recorded in [research/kitty-graphics-programs.md](../research/kitty-graphics-programs.md).

vtfx runs mostly on Android over SSH, so the images are on the host, and only the data sent in the escape codes themselves can reach the terminal. Host output is untrusted (requirement 6.1): the protocol lets the host make the terminal decode images, hold their pixels, and send replies, and the memory used because of host output must stay within the configured limits (section 10.1 of [emulation.md](../design/emulation.md)).

## 1. Scope

The protocol is implemented in `vtfx-core`, enabled by default and bounded by `TerminalLimits.maxImageBytes`, with ordinary placements and Unicode placeholders. Only direct transmission is supported: files, temporary files, and shared memory are refused permanently, and animation is refused (requirements 4.1 and 4.2, section 9.1 of [emulation.md](../design/emulation.md)).

**Why.** Over SSH, files and shared memory would name files of the device, and `t=t` asks the terminal to delete the file. Without animation, programs show the first frame.

**Alternatives considered.**
- **Sixel first**: many programs support it, but it has no ids and no deletion, needs a palette, and draws at pixel positions that do not follow the cells, which fits the terminal model worse. It may come later.
- **Only Unicode placeholders**: simpler, but programs that do not see tmux use ordinary placements, and placeholders work only where the program knows of them.

**Impact of reversing.** To turn the protocol off by default: set the default of `TerminalLimits.maxImageBytes` to 0. To remove it: drop the APC dispatch in `Emulator`, the `graphics` package of vtfx-core, `TerminalQuery.GraphicsReply` and its encoding in vtfx-gate's `Replies`, the anchors in `Line` and `CompactLine`, and the image layer of `TerminalRenderer`; remove section 9 of [emulation.md](../design/emulation.md) and move images back among the unsupported features in its section 4.

## 2. Following kitty, and the replies

1. Where the protocol leaves room, vtfx follows kitty: the parsing of control data (an unknown key or a malformed value makes the whole command ignored), base64 decoding, when replies are sent, the eviction order, the cursor movement after a placement, and which erasures delete placements (sections 9.2, 9.4, and 9.5 of [emulation.md](../design/emulation.md)).
2. Replies carry only a fixed set of statuses with constant messages, and vtfx-gate builds them like the other replies (section 9.2 of [emulation.md](../design/emulation.md), section 3 of [gate-policy.md](../design/gate-policy.md)).

**Why.** Programs are written and tested against kitty. kitty's messages include values such as file names, and no reply may contain text from the host (requirement 6.1).

**Alternatives considered.**
- **Answering with kitty's messages**: they contain host text (file names, values), which replies must not.

## 3. Decoding

PNG is decoded in Kotlin by vtfx-core, not by Android's decoders, and its colors are taken as sRGB, ignoring color profiles and gamma, as kitty does. Pixels are kept decoded, 4 bytes each. Section 9.3 of [emulation.md](../design/emulation.md) describes the decoding; section 4.5 of [boundaries.md](../design/boundaries.md) keeps the platform's decoders away from images from the host.

**Why.** The platform's decoder is native code, which hostile data would then reach. A Kotlin decoder runs the same on the JVM, where it is tested and fuzzed, and checks the size in the header against the limits before it allocates anything. Pixels are kept decoded so that the display does not decode while drawing.

**Alternatives considered.**
- **Decode with Android's `ImageDecoder` or `BitmapFactory`**: less code and faster, but hostile data would reach native code, the core could not decode on the JVM, and the size could only be checked from the header that the core reads separately from the decoder.

## 4. One memory limit for both screens

`TerminalLimits.maxImageBytes` bounds the images of both screens together (sections 9.4 and 10.2 of [emulation.md](../design/emulation.md)).

**Why.** The memory of images is then bounded by one value; kitty has one quota per screen. Commands that look at every placement do work up to the number of placements, 1024, for a few bytes of output, below the size of a screen of cells that [0081](0081-resource-limits.md) allows.

**Alternatives considered.**
- **One quota per screen, as kitty**: the memory would be bounded by twice the setting, which is harder to explain.

## 5. The size of one image

1. One image takes at most 64 MiB of decoded pixels (16,777,216 pixels at 4 bytes each), whatever `maxImageBytes` is (`Graphics.MAX_BYTES_PER_IMAGE`). A larger image is refused with `EFBIG`, as an image larger than `maxImageBytes` already is, before its pixels are allocated: from the size in the command for RGB and RGBA, and from the PNG header for PNG (sections 9.4 and 10.2 of [emulation.md](../design/emulation.md)).
2. `maxImageBytes` keeps its range (0 to 1 GiB) and stays the budget of all images of both screens together. Above 64 MiB it lets more images be kept at once, not larger ones.
3. The limit is not configurable and not public API, like `Graphics.MAX_SIDE`.

**Why.** Without it, one image could take all of `maxImageBytes`, up to 10000 by 10000 pixels (`Graphics.MAX_SIDE`), 400 MB decoded. The display makes an `ARGB_8888` bitmap of the same size for each image and draws it on a hardware-accelerated canvas, and Android's `RecordingCanvas` throws on a bitmap of more than 100 MiB (or more, if the device says so). With `maxImageBytes` above 100 MiB, a host could crash the app with one image of about 5200 by 5200 pixels, sent as a few hundred kilobytes of compressed data: a crash from untrusted input (requirement 6.1) at a setting that the API allows. 64 MiB is below the 100 MiB at which Android refuses to draw a bitmap, with room for the display to change the bitmap's format, and above every image of the default setting (32 MiB), so the default behavior does not change. 16,777,216 pixels are 4096 by 4096, larger than the screens of the devices vtfx runs on, or 5461 by 3072 for a wide image. The limit follows from what the display can draw, not from what an app wants to spend, so it is not configurable.

**Alternatives considered.**
- **Scale the display's bitmaps down** (in `vtfx-compose`) instead of limiting the core: the core would still hold up to 400 MB for one image, which the display would never show at that size, and every display would need the same care.
- **Lower the largest `maxImageBytes` to 64 MiB or 100 MiB**: also avoids the crash, but takes away the setting for apps that want many images kept, and changes the public API's range.
- **Lower `Graphics.MAX_SIDE`**: a square limit would still allow a 10000 by 10000 image's worth of a long strip, or refuse wide images that fit in memory; bounding the pixels bounds the memory directly.

**Impact of reversing.** Remove `Graphics.MAX_BYTES_PER_IMAGE`, compute the largest number of pixels in `Graphics.decode` from `maxImageBytes` alone, and remove the row from section 10.2 of [emulation.md](../design/emulation.md). The display would then have to scale large images down, or the crash comes back with `maxImageBytes` above 100 MiB.

## 6. Placements anchored to lines

1. Placements are anchored to line objects, so that they move with the text (section 9.5 of [emulation.md](../design/emulation.md)). What happens to a placement when its line goes away is topic 7.
2. The model takes the size of a cell from the display when it places an image, and a placement keeps its size in cells; a session without a display takes a default size (section 9.5 of [emulation.md](../design/emulation.md)).
3. A transmission that spans a change of screen is refused (section 9.4 of [emulation.md](../design/emulation.md)).

**Why.** Anchored to line objects, placements move with the text when the screen scrolls, in a scrolling region, and into the scrollback, at no cost per line feed. Keeping the size in cells means that the model never depends on the display after placing; if the font changes, the image is scaled with the cells. A transmission that spans a change of screen would give an image that belongs to a screen that is no longer shown.

**Alternatives considered.**
- **Placements as cells** (each cell keeps a reference to a piece of an image): scrolling, the scrollback, and erasing would follow the existing code, but reflow would rewrap images into pieces, and text written over an image would erase it, which the protocol forbids.
- **Absolute line numbers for placements**: moving them on every scroll of a region would cost work per placement for each line feed.

## 7. Placements whose top row scrolls off the top

1. When the line of a placement's top row scrolls off the top of the screen without going to the scrollback, or leaves the scrollback, also by a change of size, the placement moves down to the line that takes that line's place (the new top row, or the new oldest line of the scrollback) and remembers how many of its rows are above that line (`Placement.rowsAbove`). It goes once none of its rows are left. Its position, size, and source rectangle do not change: the rows above the top of the grid are cut when it is drawn, as for an image that starts in the scrollback (section 9.5 of [emulation.md](../design/emulation.md)).
2. Lines deleted (DL) and lines scrolled off the top margin of a region below the top of the screen still take their placements.
3. The memory estimate of a scrollback line (`CompactLine.estimatedBytes`) does not count placements moved to it. The limit on placements bounds them, as it bounds the placements in the scrollback.

**Why.** A line goes away without going to the scrollback on the alternate screen and with `scrollbackLines = 0`, and the oldest line goes when it leaves the scrollback. A placement that went with the line of its top row would be deleted while its other rows are still on the screen: a full-screen program on the alternate screen that places an image as large as the screen at the top left would lose it at once, since the cursor moves past the bottom right of the image, which scrolls the screen by one line ([kitty-graphics-programs.md](../research/kitty-graphics-programs.md)). kitty keeps such an image while a row of it is still shown. In a region below the top, images are drawn from their anchor and not cut at the margins, so an image moved down to the region's new top row would be drawn over the rows above the region, which do not scroll, and would appear to move up over them on each scroll. Deleting lines does not scroll text off the screen.

**Alternatives considered.**
- **Cut the image as its rows leave**, changing its source rectangle and size, as kitty does at the margins of a region: the source rectangle is in whole pixels, and a row of cells is rarely a whole number of pixels of the image, so the image would be scaled differently after each cut. It would also allow keeping placements in regions, but needs a source rectangle in fractions of pixels in `ImagePlacement`, an API change, for a case that programs that show images rarely meet.
- **Keep placements also in regions below the top, drawn over the rows above**: draws scrolled-out images over the fixed lines of a program, such as a status line.
- **Anchor placements to their bottom row instead**: the top row would then decide nothing, but erasing, deletion by position, and reflow all start from the top-left cell, and placements below the bottom of the screen would need another rule.

**Impact of reversing.** Reversing brings back the deletion of an image as soon as its top row goes. It removes `Placement.rowsAbove` and its use in `GraphicsLayer.forEachAnchored`, the moving of anchors in `ScreenBuffer.scrollUp`, `Scrollback.add`, and `Scrollback.replaceAll`, and `CompactLine.addAnchors`, and restores section 9.5 of emulation.md.

## 8. Drawing and the bitmaps of a display

1. Images under the text are also under the backgrounds of cells, which kitty does only below a z-index of -1073741824 (section 9.6 of [emulation.md](../design/emulation.md)).
2. Each display keeps its own bitmaps (section 9.6 of [emulation.md](../design/emulation.md)).
3. The bitmaps of images that the last frame did not draw are kept up to a fixed 32 MiB per display, the least recently drawn going first, besides going after 60 frames, so that a display holds at most `maxImageBytes` plus 32 MiB of bitmaps (sections 9.6 and 10.2 of [emulation.md](../design/emulation.md)).
4. The sizes of the bitmaps are counted as 4 bytes a pixel from the image, the size of an `ARGB_8888` bitmap, rather than read from the bitmap.

**Why.** The display caches each line with its backgrounds, and drawing images between the two would give up the cache. Each session numbers its images from 1, so bitmaps shared across sessions would show one session's image in place of another's. Bitmaps are kept after a frame stops drawing them so that an image scrolled out of view and back is not made again. Bounded only by frames, a host that sends a new image on every frame and deletes the previous one keeps the core at one image while the display keeps the bitmaps of the last 60, about 60 times `maxImageBytes`; zlib makes such images cheap to send, so a few megabytes of output could fill the native heap. The budget is a constant, not a multiple of `maxImageBytes`: the session does not expose its limits to the display, and the bitmaps kept only spare making a bitmap again, which is a copy of pixels the core already holds, not a decode. 32 MiB is the default of `maxImageBytes`, enough to keep the images of a screen that scrolls a little.

**Alternatives considered.**
- **Drop every bitmap that the frame did not draw**: the simplest bound, but an image that scrolls out of view and back, or that a frame in between does not draw, would be made again each time.
- **A budget of twice `maxImageBytes`**: follows the setting, but needs the limits of the session in the display, a new API, for no gain in safety over a constant.
- **`Bitmap.recycle` for the bitmaps that go**: frees the native memory sooner, but a bitmap may still be in a recorded line or frame, and the boundaries allow only `createBitmap` of `Bitmap`. The memory goes with the garbage collector.

**Impact of reversing.** Removing the budget of item 3 brings back memory beyond the configured limits. Changing its value changes only `ImageBitmaps.MAX_UNDRAWN_BYTES` and sections 9.6 and 10.2 of emulation.md.

## 9. Unicode placeholders

1. U+10EEEE is one cell wide even when Ambiguous characters are wide (it is a private use character, which UAX #11 classifies as Ambiguous), as in kitty (sections 7.1 and 9.7 of [emulation.md](../design/emulation.md)).
2. The table of placeholder diacritics is generated from `third_party/unicode` by the rule kitty uses, which gives the same 297 characters in the same order as kitty's `rowcolumn-diacritics.txt`; no file is taken from kitty (section 9.7 of [emulation.md](../design/emulation.md)).
3. Placeholder cells are not text for copying, accessibility, and searching (section 9.7 of [emulation.md](../design/emulation.md)).

**Why.** kitty is GPL-3.0. Copying, accessibility, and searching should not see private use characters.

## 10. API

`TerminalLimits.maxImageBytes` is a parameter added before 1.0 ([0087](0087-public-api.md)). `TerminalSession.resize` takes the size of a cell in pixels, and `TerminalSessionListener.onResize` gains an overload with the size of the text area in pixels, which calls the existing one by default; the app passes the pixels on to the host. The images that the display draws are internal API (`@InternalVtfxApi`). See section 9.8 of [emulation.md](../design/emulation.md) and section 3.1 of [session-and-threads.md](../design/session-and-threads.md).

**Why.** The default of the overload keeps listeners written before it working. Programs read the pixel sizes on the host, so the app passes them on. Only vtfx-compose reads the images, and the public API does not grow until an app needs it.
