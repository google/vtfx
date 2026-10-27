# Recordings of the output of real apps

Made by `tools/recordings/record.py`. Owned by agents (not an oracle owned by humans). Usage and the relation to tests are in section 10 of [testing.md](../docs/design/testing.md).

| File | Contents |
|---|---|
| `<name>.out` | The bytes the app wrote (80 × 24, `TERM=xterm-256color`) |
| `<name>.screen.txt` | The characters on the screen when replayed into the reference terminal (tmux) |
| `<name>.screen.ansi` | A capture of the same screen with attributes (`tmux capture-pane -e`) |
| `<name>.json` | The versions of the app and the reference, and the size |

| Name | Program |
|---|---|
| `vim` | `vim --clean` (line numbers, search highlight, insert mode with wide characters, horizontal split) |
| `less` | `less -N` (scrolling, search highlight, status line) |
| `htop` | `htop --no-mouse` (colors, meters, process tree) |
| `tmux` | `tmux` (status line, split panes, borders, colored and wide text) |
| `neovim` | `nvim --clean` (truecolor default scheme, vertical split, search highlight, wide characters) |
| `emacs` | `emacs -nw -Q` (incremental search, horizontal split with mode-lines, CJK editing) |
| `mc` | `mc --nosubshell` (Midnight Commander dual-pane directory view, box drawing, CJK filenames) |
| `btop` | `btop --force-utf` (rounded borders, truecolor gradients, Braille graphs, synchronized output) |
| `tig` | `tig` (commit log and split diff view on a synthetic Git repository) |
| `fzf` | `fzf` (fuzzy finder with border, query highlight, and preview pane) |
| `ncdu` | `ncdu --color dark` (disk usage browser with half-block usage bars on an imported JSON tree) |

In the `htop` and `btop` recordings, to avoid keeping the process list of the development machine they were recorded on, the alphanumeric characters in the user and command columns of the process list are replaced with `x`, `X`, and `0` (`scrub_htop` and `scrub_btop` in `record.py`), and `btop` hides host disk, network, GPU, and hostname fields via its generated `btop.conf`. In the `mc` recording, `scrub_mc` replaces the user, host, and temporary directory in OSC 0 and OSC 7 strings. Recording again applies the same replacements automatically.

To record again (example):

```
# If the apps are not on the system, extract their packages and add them to PATH.
apt-get download htop tmux neovim neovim-runtime emacs-nox emacs-common emacs-bin-common \
  mc mc-data btop tig fzf ncdu
for f in *.deb; do dpkg -x "$f" root; done
PATH=$PWD/root/usr/bin:$PATH \
  LD_LIBRARY_PATH=$PWD/root/usr/lib/x86_64-linux-gnu \
  VIMRUNTIME=$PWD/root/usr/share/nvim/runtime \
  MC_DATADIR=$PWD/root/usr/share/mc \
  tools/recordings/record.py recordings
```

To regenerate only the reference screens, add `--reference-only`.

## Images (`graphics/`)

Programs that show images with the kitty graphics protocol, recorded by `tools/recordings/record_graphics.py` ([decisions/0080](../docs/decisions/0080-kitty-graphics.md)). The script answers the programs' queries as vtfx does, in a terminal of 80 × 24 cells of 10 × 20 pixels, and gives them an image it makes (64 × 48 pixels), so the recordings hold no file of the machine. tmux knows nothing of the protocol, so there is no reference screen: `GraphicsRecordingTest` in `vtfx-core` checks where the images are and where the cursor ends, and `replay <name>` in the sample app shows them.

| File | Contents |
|---|---|
| `graphics/<name>.out` | The bytes the program wrote |
| `graphics/<name>.json` | The command, the versions of the programs, the size, and the queries the program asked |

| Name | Program |
|---|---|
| `timg` | `timg -pk` (timg recognizes kitty only by name, so a vtfx user asks for the protocol) |
| `icat` | `kitten icat`, which finds the protocol with a query |
| `icat-placeholder` | `kitten icat --unicode-placeholder` |
| `fastfetch` | `fastfetch --logo-type kitty`, with only its color blocks beside the logo |

To record again: `tools/recordings/record_graphics.py recordings/graphics` with timg, kitty's `kitten`, and fastfetch on `PATH`.
