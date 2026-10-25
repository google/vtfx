# Research: what programs send with the kitty graphics protocol

A record of what programs that show images sent, and of how kitty behaved, as observed on 2026-10-16 while [decisions/0080](../decisions/0080-kitty-graphics.md) was written. It is not kept up to date; the behavior vtfx implements is in section 9 of [design/emulation.md](../design/emulation.md).

The programs ran on the development machine (kitty 0.48.2, timg 1.6.3, fastfetch 2.57.1) in a pseudo-terminal that answered as vtfx does, and kitty itself was checked under Xvfb. The recordings are made by `tools/recordings/record_graphics.py` (section 10 of [testing.md](../design/testing.md)).

## 1. Programs

- `kitten icat` asks with `a=q` and then DA1, tries files and shared memory (`t=t`, `t=s`), falls back to direct data when they fail, and sends RGB or RGBA compressed with zlib (`f=24`, `o=z`) in chunks of 128 KiB of base64, with `a=T`, `q=2`, and `C=1` when asked to. With `--unicode-placeholder` it sends PNG with a virtual placement (`U=1`) and prints placeholder cells, coloring them with `38:2:R:G:B`.
- timg detects kitty only by name (XTVERSION or `TERM=xterm-kitty`); with `-pk` it sends PNG (`f=100`) in chunks of 4096 with `a=T,q=2`, then CR LF, relying on the cursor ending on the last row of the image.
- `fastfetch --logo-type kitty` sends compressed RGBA with `a=T`; `kitty-direct` sends a file name (`t=f`), which cannot work over SSH; `kitty-icat` runs `kitten icat`. It asks for the cursor position (DSR 6) after the image, to know where the image left it.

## 2. kitty

- After a placement, kitty moves the cursor to the column after the image and to the image's last row (not below it), wraps to the next row if that column is past the right edge, scrolls if that row is below the scrolling region, and places at the cursor's column even with a pending wrap.
- Its replies have the form `ESC _ G i=<id>[,I=<number>][,p=<placement>];<OK or CODE:message> ESC \`.
