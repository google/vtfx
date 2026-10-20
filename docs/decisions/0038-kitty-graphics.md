# The kitty graphics protocol

- Status: Provisional (2026-10-20, a human, nya, decided that the protocol stays enabled by default and that the requirements be rewritten to put it in scope; the rest of this decision is not yet reviewed)
- Date: 2026-10-16
- Related: Requirements 4.1 and 4.2 (image protocols), 5.1, 6.1, 6.2, 6.5, section 9 of [emulation.md](../design/emulation.md), [0001](0001-parser.md), [0002](0002-resource-limits.md), [0006](0006-gate-defaults.md), [0009](0009-emulation-scope.md), [0013](0013-resize-and-lifecycle.md), [0014](0014-performance.md), the kitty documentation (`graphics-protocol.rst`, kitty 0.48), [research/kitty-graphics-programs.md](../research/kitty-graphics-programs.md)

## Background

Requirement 4.2 left image protocols such as sixel and kitty out of scope "in the initial stage", and [0009](0009-emulation-scope.md) listed images among the unsupported features. The initial development is over, and on 2026-10-16 a human asked for the kitty graphics protocol to be implemented. Programs that show images in the terminal (image viewers such as `kitten icat` and timg, `fastfetch`, file managers such as yazi, editors such as Neovim with snacks.nvim) use it, and through tmux too, by its Unicode placeholders. What these programs send was recorded in [research/kitty-graphics-programs.md](../research/kitty-graphics-programs.md).

vtfx runs mostly on Android over SSH, so the images are on the host, and only the data sent in the escape codes themselves can reach the terminal. Host output is untrusted (requirement 6.1): the protocol lets the host make the terminal decode images, hold their pixels, and send replies.

## Decision

Implement the protocol in `vtfx-core` with direct transmission, the formats RGB, RGBA, and PNG, placements anchored to lines, deletion, queries, and Unicode placeholders, enabled by default and bounded by `TerminalLimits.maxImageBytes`. The scope, the replies, the limits, and the behavior of placements are in section 9 of [emulation.md](../design/emulation.md), and the limits also in section 10. The choices and their reasons:

- **Only direct transmission.** Files, temporary files, and shared memory are refused permanently: over SSH they would name files of the device, and `t=t` asks the terminal to delete the file. Animation is refused; programs show the first frame.
- **Follow kitty where the protocol leaves room**: the parsing of control data (an unknown key or a malformed value makes the whole command ignored), base64 decoding, when replies are sent, the eviction order, the cursor movement after a placement, and which erasures delete placements. Programs are written and tested against kitty.
- **Replies carry only a fixed set of statuses with constant messages.** kitty's messages include values such as file names; no reply may contain text from the host (requirement 6.1). vtfx-gate builds them like the other replies.
- **PNG is decoded in Kotlin by vtfx-core, not by Android's decoders.** The platform's decoder is native code, which hostile data would then reach; a Kotlin decoder runs the same on the JVM, where it is tested and fuzzed, and checks the size in the header against the limits before it allocates anything. Colors are taken as sRGB, ignoring color profiles and gamma, as kitty does.
- **Pixels are kept decoded**, 4 bytes each, so that the display does not decode while drawing.
- **One memory limit for both screens together**, so that the memory of images is bounded by one value; kitty has one quota per screen. Commands that look at every placement do work up to the number of placements, 1024, for a few bytes of output, below the size of a screen of cells that [0002](0002-resource-limits.md) allows.
- **Placements are anchored to line objects**, so that they move with the text when the screen scrolls, in a scrolling region, and into the scrollback, at no cost per line feed, and go with their lines.
- **The model takes the size of a cell from the display when it places an image, and a placement keeps its size in cells**, so that the model never depends on the display afterwards; if the font changes, the image is scaled with the cells. A session without a display takes a default size.
- **A transmission that spans a change of screen is refused**, since its image would belong to a screen that is no longer shown.
- **Images under the text are also under the backgrounds of cells**, which kitty does only below a z-index of -1073741824: the display caches each line with its backgrounds, and drawing between the two would give up the cache.
- **Each display keeps its own bitmaps**: each session numbers its images from 1, so bitmaps shared across sessions would show one session's image in place of another's.
- **U+10EEEE is one cell wide even when Ambiguous characters are wide** (it is a private use character, which UAX #11 classifies as Ambiguous), as in kitty.
- **The table of placeholder diacritics is generated from `third_party/unicode`** by the rule kitty uses, which gives the same 297 characters in the same order as kitty's `rowcolumn-diacritics.txt`. No file is taken from kitty, which is GPL-3.0.
- **Placeholder cells are not text** for copying, accessibility, and searching, which should not see private use characters.
- **API**: `TerminalLimits.maxImageBytes` is a new parameter (before 1.0, [0036](0036-adding-parameters.md)). `TerminalSession.resize` takes the size of a cell in pixels, and `TerminalSessionListener.onResize` gains an overload with the size of the text area in pixels, which calls the existing one by default so that listeners written before keep working; the app passes the pixels on to the host, since programs read them there. The images that the display draws are internal API (`@InternalVtfxApi`): only vtfx-compose reads them, and the public API does not grow until an app needs it.

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
- To remove it: drop the APC dispatch in `Emulator`, the `graphics` package of vtfx-core, `TerminalQuery.GraphicsReply` and its encoding in vtfx-gate's `Replies`, the anchors in `Line` and `CompactLine`, and the image layer of `TerminalRenderer`; remove section 9 of [emulation.md](../design/emulation.md) and move images back among the unsupported features in its section 4.
