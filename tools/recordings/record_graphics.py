#!/usr/bin/env python3
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Records the output of real programs that show images with the kitty graphics protocol.

Each scenario runs a program in a pseudo terminal of 80 x 24 cells of 10 x 20 pixels, with
TERM=xterm-256color and none of the variables by which programs recognize kitty, and answers its
queries as vtfx does (docs/decisions/0038): DA1, XTVERSION with vtfx's name, the pixel sizes
(XTWINOPS 14 and 16, and the PTY's window size), DSR 6 with the cursor of a simple tracker, and
graphics commands with OK for direct data and EBADF for files and shared memory. Every byte the
program writes is recorded in OUT_DIR/<scenario>.out, with the versions and the queries it asked in
OUT_DIR/<scenario>.json. GraphicsRecordingTest in vtfx-core replays them, and the sample app shows
them with "replay".

The image is made here (a checkerboard in four colors with a transparent corner), so the recordings hold no file of
the machine. fastfetch shows only its color blocks, not the machine's information.

Usage: record_graphics.py OUT_DIR [SCENARIO ...]

The programs must be on PATH: timg, kitten (kitty), fastfetch.
"""

import fcntl
import json
import os
import pty
import re
import select
import shutil
import struct
import subprocess
import sys
import tempfile
import termios
import time
import zlib

COLUMNS, ROWS = 80, 24
CELL_WIDTH, CELL_HEIGHT = 10, 20
NAME = "vtfx(0.1)"

ESC = b"\x1b"
SEQUENCE = re.compile(rb"\x1b(\[[\x30-\x3f]*[\x20-\x2f]*[\x40-\x7e]|_[^\x1b]*\x1b\\|\][^\x07\x1b]*(?:\x07|\x1b\\)|P[^\x1b]*\x1b\\)")


def png(width, height):
    """A PNG of four quarters, red, green, blue, and yellow, each a checkerboard of squares of 8
    pixels in its color and a darker one, transparent in the bottom right. The edges are sharp, so
    that scaling and cutting show in screenshots."""
    colors = ((255, 0, 0), (0, 200, 0), (0, 0, 255), (230, 200, 0))
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            alpha = 0 if x >= width * 3 // 4 and y >= height * 3 // 4 else 255
            color = colors[(2 if y >= height // 2 else 0) + (1 if x >= width // 2 else 0)]
            if (x // 8 + y // 8) % 2:
                color = tuple(c // 2 for c in color)
            rows += bytes((*color, alpha))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


def scenarios(image):
    return {
        # timg recognizes kitty only by name, so a vtfx user asks for the protocol with -pk.
        "timg": ["timg", "-pk", "-g40x10", image],
        # kitten icat asks with a=q and falls back from files to direct data.
        "icat": ["kitten", "icat", "--align", "left", image],
        "icat-placeholder": ["kitten", "icat", "--align", "left", "--unicode-placeholder", image],
        # fastfetch places a logo and writes beside it; only the color blocks, no machine data.
        "fastfetch": ["fastfetch", "--logo", image, "--logo-type", "kitty", "--logo-width", "16",
                      "--structure", "colors", "--pipe", "false"],
    }


def versions():
    def first_line(command):
        try:
            # Without a terminal on stdin: timg --version waits for an answer from the terminal.
            return subprocess.run(command, capture_output=True, text=True, check=False, stdin=subprocess.DEVNULL,
                                  timeout=10).stdout.splitlines()[0]
        except (OSError, IndexError, subprocess.TimeoutExpired):
            return "unknown"
    return {
        "timg": first_line(["timg", "--version"]),
        "kitten": first_line(["kitten", "--version"]),
        "fastfetch": first_line(["fastfetch", "--version"]),
    }


class Responder:
    """Answers the queries in the program's output, tracking the cursor roughly for DSR 6."""

    def __init__(self, fd):
        self.fd = fd
        self.pending = bytearray()
        self.queries = []
        self.row, self.column = 1, 1

    def reply(self, data):
        os.write(self.fd, data)

    def feed(self, data):
        self.pending += data
        while True:
            start = self.pending.find(ESC)
            text = self.pending if start < 0 else self.pending[:start]
            self.track_text(text)
            if start < 0:
                self.pending.clear()
                return
            del self.pending[:start]
            match = SEQUENCE.match(self.pending)
            if not match:
                if len(self.pending) > 1 and self.pending[1:2] not in b"[_]P":
                    del self.pending[:2]
                    continue
                return
            self.handle(bytes(match.group(0)))
            del self.pending[:match.end()]

    def track_text(self, text):
        for byte in text:
            if byte == 0x0D:
                self.column = 1
            elif byte == 0x0A:
                self.row = min(ROWS, self.row + 1)
            elif byte >= 0x20 and (byte & 0xC0) != 0x80:
                self.column = min(COLUMNS, self.column + 1)

    def handle(self, sequence):
        if sequence.startswith(b"\x1b_G"):
            control = sequence[3:-2].split(b";", 1)[0].decode("ascii", "replace")
            keys = dict(item.split("=", 1) for item in control.split(",") if "=" in item)
            self.queries.append("graphics " + ",".join(f"{k}={v}" for k, v in keys.items() if k in "atfTUmq"))
            if keys.get("m") == "1":
                return
            image_id = keys.get("i")
            if image_id is None or keys.get("q") == "2":
                return
            if keys.get("t", "d") != "d":
                self.reply(f"\x1b_Gi={image_id};EBADF:only direct transmission is supported\x1b\\".encode())
            elif keys.get("q") != "1":
                self.reply(f"\x1b_Gi={image_id};OK\x1b\\".encode())
            return
        if sequence.startswith(b"\x1b["):
            body = sequence[2:]
            final = body[-1:]
            parameters = body[:-1]
            if final == b"H" or final == b"f":
                values = [int(v) if v else 1 for v in parameters.split(b";")] + [1, 1]
                self.row, self.column = values[0], values[1]
            replies = {
                b"c": b"\x1b[?62;22c",
                b"0c": b"\x1b[?62;22c",
                b">q": f"\x1bP>|{NAME}\x1b\\".encode(),
                b"14t": f"\x1b[4;{ROWS * CELL_HEIGHT};{COLUMNS * CELL_WIDTH}t".encode(),
                b"16t": f"\x1b[6;{CELL_HEIGHT};{CELL_WIDTH}t".encode(),
                b"18t": f"\x1b[8;{ROWS};{COLUMNS}t".encode(),
                b"5n": b"\x1b[0n",
                b"6n": f"\x1b[{self.row};{self.column}R".encode(),
            }
            if body in replies:
                self.queries.append("CSI " + body.decode("ascii", "replace"))
                self.reply(replies[body])


def record(command, env):
    pid, fd = pty.fork()
    if pid == 0:
        fcntl.ioctl(sys.stdout.fileno(), termios.TIOCSWINSZ,
                    struct.pack("HHHH", ROWS, COLUMNS, COLUMNS * CELL_WIDTH, ROWS * CELL_HEIGHT))
        os.execvpe(command[0], command, env)
    responder = Responder(fd)
    output = bytearray()
    deadline = time.time() + 20
    while time.time() < deadline:
        ready, _, _ = select.select([fd], [], [], 0.2)
        if not ready:
            finished, _ = os.waitpid(pid, os.WNOHANG)
            if finished:
                break
            continue
        try:
            data = os.read(fd, 65536)
        except OSError:
            break
        if not data:
            break
        output += data
        responder.feed(data)
    else:
        os.kill(pid, 9)
        raise RuntimeError(f"{command[0]} did not finish")
    os.close(fd)
    os.waitpid(pid, 0)
    return bytes(output), responder.queries


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    out_dir = argv[1]
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        image = os.path.join(work, "image.png")
        with open(image, "wb") as f:
            f.write(png(64, 48))
        all_scenarios = scenarios(image)
        names = argv[2:] or list(all_scenarios)
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("KITTY", "TERM", "WEZTERM", "GHOSTTY", "TMUX", "VTE", "KONSOLE", "ITERM",
                                      "LC_TERMINAL", "COLORTERM", "WAYLAND", "DISPLAY"))}
        env.update(TERM="xterm-256color", COLORTERM="truecolor", HOME=work, LANG="C.UTF-8")
        for name in names:
            command = all_scenarios[name]
            if shutil.which(command[0]) is None:
                raise SystemExit(f"{command[0]} is not on PATH")
            output, queries = record(command, env)
            # The temporary directory's name would differ on each run.
            output = output.replace(work.encode(), b"/tmp/work")
            with open(os.path.join(out_dir, f"{name}.out"), "wb") as f:
                f.write(output)
            meta = {
                "command": [part.replace(work, "/tmp/work") for part in command],
                "columns": COLUMNS,
                "rows": ROWS,
                "cell": [CELL_WIDTH, CELL_HEIGHT],
                "versions": versions(),
                "queries": queries,
            }
            with open(os.path.join(out_dir, f"{name}.json"), "w") as f:
                json.dump(meta, f, indent=2)
                f.write("\n")
            print(f"{name}: {len(output)} bytes, {len(queries)} queries")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
