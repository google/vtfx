# The kitty graphics protocol

- Status: Provisional
- Date: 2026-10-16
- Related: Requirement 4.2 (image protocols), 5.1, 6.1, 6.2, 6.5, [0001](0001-parser.md), [0002](0002-resource-limits.md), [0006](0006-gate-defaults.md), [0009](0009-emulation-scope.md), [0013](0013-resize-and-lifecycle.md), [0014](0014-performance.md), the kitty documentation (`graphics-protocol.rst`, kitty 0.48)

## Background

Requirement 4.2 left image protocols such as sixel and kitty out of scope "in the initial stage", and [0009](0009-emulation-scope.md) listed images among the unsupported features. The initial development is over, and on 2026-10-16 a human asked for the kitty graphics protocol to be implemented. Programs that show images in the terminal (image viewers such as `kitten icat` and timg, `fastfetch`, file managers such as yazi, editors such as Neovim with snacks.nvim) use it, and through tmux too, by its Unicode placeholders.

vtfx runs mostly on Android over SSH, so the images are on the host, and only the data sent in the escape codes themselves can reach the terminal. Host output is untrusted (requirement 6.1): the protocol lets the host make the terminal decode images, hold their pixels, and send replies.

What real programs send was recorded on the development machine (kitty 0.48.2, timg 1.6.3, fastfetch 2.57.1) in a pseudo-terminal that answers as vtfx does, and the behavior of kitty itself was checked under Xvfb:

- `kitten icat` asks with `a=q` and then DA1, tries files and shared memory (`t=t`, `t=s`), falls back to direct data when they fail, and sends RGB or RGBA compressed with zlib (`f=24`, `o=z`) in chunks of 128 KiB of base64, with `a=T`, `q=2`, and `C=1` when asked to. With `--unicode-placeholder` it sends PNG with a virtual placement (`U=1`) and prints placeholder cells, coloring them with `38:2:R:G:B`.
- timg detects kitty only by name (XTVERSION or `TERM=xterm-kitty`); with `-pk` it sends PNG (`f=100`) in chunks of 4096 with `a=T,q=2`, then CR LF, relying on the cursor ending on the last row of the image.
- `fastfetch --logo-type kitty` sends compressed RGBA with `a=T`; `kitty-direct` sends a file name (`t=f`), which cannot work over SSH; `kitty-icat` runs `kitten icat`. It asks for the cursor position (DSR 6) after the image, to know where the image left it.
- kitty moves the cursor after a placement to the column after the image and to the image's last row (not below it), wraps to the next row if that column is past the right edge, scrolls if that row is below the scrolling region, and places at the cursor's column even with a pending wrap. Its replies have the form `ESC _ G i=<id>[,I=<number>][,p=<placement>];<OK or CODE:message> ESC \`.

## Decision

### Scope

| Supported | Not supported |
|---|---|
| Transmission of direct data (`t=d`), in chunks (`m`) | Files, temporary files, and shared memory (`t=f`, `t`, `s`): refused with `EBADF`, permanently. Over SSH they would name files of the device, and `t=t` asks the terminal to delete the file |
| Formats RGB, RGBA, and PNG (`f=24`, `32`, `100`), with or without zlib (`o=z`) | Animation (`a=f`, `a`, `c`): refused with `EINVAL`. Programs show the first frame |
| Actions transmit, transmit and display, put, query, delete (`a=t`, `T`, `p`, `q`, `d`) | Relative placements (`P`, `Q`, `H`, `V`): the keys are ignored and the placement is placed at the cursor |
| Image ids and numbers (`i`, `I`), placement ids (`p`), source rectangles (`x`, `y`, `w`, `h`), cell offsets (`X`, `Y`), sizes in cells (`c`, `r`), z-index (`z`), cursor policy (`C`), quiet (`q`) | Usage hints (`N`): ignored |
| Every kind of deletion (`d=a` to `Z`), except of animation frames (`d=f`, `F`, which do nothing) | |
| Unicode placeholders (`U=1` and U+10EEEE) | |
| Pixel sizes: XTWINOPS 14 (the text area) and 16 (a cell), and the pixel size given to the app for the host's window size | XTWINOPS 14;2 and 15 report the same as 14, since vtfx has no window or screen of its own |

### Parsing

- APC (`ESC _`) strings are collected, as OSC and DCS strings are, up to `TerminalLimits.maxStringLength`; a longer one is discarded whole ([0002](0002-resource-limits.md)). Only ST ends an APC, not BEL. APC strings that do not start with `G` are ignored, as SOS and PM strings still are.
- The control data follows kitty's parser: a key that kitty does not know, a value that is not a number where one is expected, a number beyond 32 bits, or an unknown action makes the whole command ignored without a reply, as kitty does.
- The payload is base64 with or without padding. Decoding stops at the first character that is not base64, as in kitty.

### Replies

- A reply is sent only when the command has an image id (`i`) or number (`I`), as in kitty, and `q=1` suppresses `OK` and `q=2` every reply. Chunks before the last are not answered.
- The reply carries the numbers of the command (and, for an image number, the id that vtfx gave the new image) and one of a fixed set of status strings: `OK`, `EINVAL`, `ENOENT`, `ENODATA`, `EBADPNG`, `EFBIG`, `EBADF`, each followed by a constant message. Unlike kitty, whose messages include values such as file names, no reply contains text from the host (requirement 6.1). vtfx-gate builds them like the other replies.

### Decoding and the pixels

- PNG is decoded by vtfx-core, in Kotlin, not by Android's decoders: the decoder of the platform is native code, which hostile data would then reach; a Kotlin decoder runs the same on the JVM, where it is tested and fuzzed, and checks the size in the header against the limits before it allocates anything. Every color type, bit depth, and interlacing of PNG is decoded; colors are taken as sRGB, ignoring color profiles and gamma, as kitty does.
- zlib data (`o=z`, and PNG's image data) is inflated with `java.util.zip.Inflater`, up to the size the image needs, and no further.
- Pixels are kept decoded, 4 bytes each, so that the display does not decode while drawing. The display makes a bitmap of each image once and keeps it until the image is gone.

### Limits

Added to [0002](0002-resource-limits.md):

| Target | Limit | Setting |
|---|---|---|
| Pixels of all images, of both screens together | 32 MiB of decoded pixels by default (8 Mi pixels), at most 1 GiB; 0 turns the protocol off: APC strings are then ignored and queries are not answered | `TerminalLimits.maxImageBytes` |
| Width and height of an image | 10000 pixels each, as kitty | Not configurable |
| Data of a transmission in progress | The size of the decoded image, and at most `maxImageBytes`, of decoded base64 | Follows `maxImageBytes` |
| Images | 1024 per screen | Not configurable |
| Placements | 1024 per screen | Not configurable |
| Columns and rows of a placement | `TerminalLimits.MAX_DIMENSION` | Not configurable |

- When a new image does not fit, the oldest images go first, those without placements before those with them, as kitty does. An image larger than the limit by itself is refused with `EFBIG`. A placement beyond the limit makes the oldest placement go.
- The limit counts both screens together, so that the memory of images is bounded by one value; kitty has one quota per screen.
- Commands that look at every placement (deletions by position, and clearing the screen, which deletes the placements on it) do work up to the number of placements, 1024, for a few bytes of output, which is below the size of a screen of cells that [0002](0002-resource-limits.md) allows.

### Placements and the text

- A placement is anchored to the cell at the cursor: to the line object, so that it moves with its line when the screen scrolls, in a scrolling region too, when lines are inserted or deleted, and into the scrollback. When its line leaves the scrollback, or a line is deleted without going to the scrollback (a scrolling region below the top, the alternate screen, deleted lines), the placement goes with it. The rows of the image below its anchor are drawn over whatever is there.
- Resizing reflows the anchor with the cell it is on ([0013](0013-resize-and-lifecycle.md)); a line that has only an anchor is kept as if it had content. The image is not rewrapped: it is drawn from its anchor and cut at the right edge. On the alternate screen, which is not reflowed, anchors beyond the new width go.
- Clearing the screen (ED 2), RIS, and entering the alternate screen with 1049 delete the placements on that screen, as kitty does; leaving the alternate screen deletes the alternate screen's placements and images. Other erasing (ED 0 and 1, EL, ECH, DCH) leaves images alone, as the protocol says. ED 3 deletes the placements in the scrollback.
- Each screen has its own images, so the images of the main screen stay while a program runs on the alternate screen, as in kitty.
- The size in cells of a placement without `c` and `r` is its size in pixels divided by the size of a cell, rounded up, taken when it is placed. The terminal model needs the size of a cell for that, which the display gives to the session (`TerminalSession.resize`); a session without a display takes 10 × 20 pixels. Once placed, a placement keeps its size in cells; if the font changes, the image is scaled with the cells, so that the model never depends on the display.
- The cursor moves as in kitty (Background), unless `C=1`.
- A transmission that is still in chunks when the screen changes (entering or leaving the alternate screen) is refused with `EINVAL` when it ends, since its images would belong to a screen that is no longer shown.

### Drawing

- Images with a negative z-index are drawn under the text, the others over it, before the selection and the cursor. Images under the text are also under the backgrounds of cells, which kitty does only below a z-index of -1073741824: the display caches each line with its backgrounds, and drawing between the two would give up the cache.
- An image is drawn from its anchor, scaled to its size in cells, and cut at the edges of the grid.

### Unicode placeholders

- U+10EEEE is one cell wide even when East Asian Ambiguous characters are wide (it is a private use character, which UAX #11 classifies as Ambiguous). The diacritics that encode its row, column, and the high byte of the image id are combining characters, kept in the cell's cluster like others.
- The table of diacritics is generated from `third_party/unicode` by the same rule kitty uses (combining class 230, without decomposition, without 19 characters that normalization can join), limited to characters of Unicode 6.0 or earlier with `DerivedAge.txt`, which gives the same 297 characters in the same order as kitty's `rowcolumn-diacritics.txt`. No file is taken from kitty, which is GPL-3.0.
- The display draws a placeholder cell as the part of the image that its row and column select, with the image fitted into the virtual placement's columns and rows keeping its aspect ratio, and centered in them, as in kitty. The foreground color gives the image id (8 bits for an indexed color, 24 for a direct color, and the high byte from the third diacritic); the underline color gives the placement id. Missing diacritics are inferred from the cell to the left, by the rules of the protocol.
- Placeholder cells are not text: `LineSnapshot.textAt` and `text()` report them as blank, so that copying, accessibility, and searching do not see private use characters.

### API

- `TerminalLimits.maxImageBytes` is a new parameter (before 1.0, [0036](0036-adding-parameters.md)).
- `TerminalSession.resize` takes the size of a cell in pixels, which a Compose `Terminal` passes; `TerminalSessionListener.onResize` gains an overload with the size of the text area in pixels, which calls the existing one by default, so that listeners written before keep working. The app passes the pixels on to the host (SSH window-change, TIOCSWINSZ), since programs read them there.
- The images that the display draws are in `TerminalSnapshot`, as internal API (`@InternalVtfxApi`): only vtfx-compose reads them, and the public API does not grow until an app needs it.

## Alternatives considered

- **Sixel first**: many programs support it, but it has no ids and no deletion, needs a palette, and draws at pixel positions that do not follow the cells, which fits the terminal model worse. It may come later.
- **Decode with Android's `ImageDecoder` or `BitmapFactory`**: less code and faster, but hostile data would reach native code, the core could not decode on the JVM, and the size could only be checked from the header that the core reads separately from the decoder.
- **Placements as cells** (each cell keeps a reference to a piece of an image): scrolling, the scrollback, and erasing would follow the existing code, but reflow would rewrap images into pieces, and text written over an image would erase it, which the protocol forbids.
- **Absolute line numbers for placements**: moving them on every scroll of a region would cost work per placement for each line feed.
- **Only Unicode placeholders**: simpler, but programs that do not see tmux use ordinary placements, and placeholders work only where the program knows of them.
- **One quota per screen, as kitty**: the memory would be bounded by twice the setting, which is harder to explain.
- **Answering with kitty's messages**: they contain host text (file names, values), which replies must not.

## Impact of reversing

- To turn the protocol off by default: set the default of `TerminalLimits.maxImageBytes` to 0.
- To remove it: drop the APC dispatch in `Emulator`, the `graphics` package of vtfx-core, `TerminalQuery.Graphics` and its encoding in vtfx-gate's `Replies`, the anchors in `Line` and `CompactLine`, and the image layer of `TerminalRenderer`; restore 0009's row for images.
