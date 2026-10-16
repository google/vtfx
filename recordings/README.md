# Recordings of the output of real apps

Made by `tools/recordings/record.py`. Owned by agents (not an oracle owned by humans). Usage and the relation to tests are in section 10 of [testing.md](../docs/design/testing.md).

| File | Contents |
|---|---|
| `<name>.out` | The bytes the app wrote (80 × 24, `TERM=xterm-256color`) |
| `<name>.screen.txt` | The characters on the screen when replayed into the reference terminal (tmux) |
| `<name>.screen.ansi` | A capture of the same screen with attributes (`tmux capture-pane -e`) |
| `<name>.json` | The versions of the app and the reference, and the size |

In the htop recording, to avoid keeping the process list of the development machine it was recorded on, the alphanumeric characters in the USER and Command columns of the process list are replaced with `x`, `X`, and `0` (`scrub_htop` in `record.py`). The layout, attributes, and tree lines are as recorded. Recording again does the same replacement automatically.

To record again (example):

```
# If htop and tmux are not on the system, extract the packages and add them to PATH.
apt-get download htop tmux libevent-core-2.1-7t64 libjemalloc2
for f in *.deb; do dpkg -x "$f" root; done
PATH=$PWD/root/usr/bin:$PATH LD_LIBRARY_PATH=$PWD/root/usr/lib/x86_64-linux-gnu \
  tools/recordings/record.py recordings
```

To regenerate only the reference screens, add `--reference-only`.

## Images (`graphics/`)

Programs that show images with the kitty graphics protocol, recorded by `tools/recordings/record_graphics.py` ([decisions/0038](../docs/decisions/0038-kitty-graphics.md)). The script answers the programs' queries as vtfx does, in a terminal of 80 × 24 cells of 10 × 20 pixels, and gives them an image it makes (64 × 48 pixels), so the recordings hold no file of the machine. tmux knows nothing of the protocol, so there is no reference screen: `GraphicsRecordingTest` in `vtfx-core` checks where the images are and where the cursor ends, and `replay <name>` in the sample app shows them.

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
