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

"""Records the output of real terminal apps, and the screen a reference terminal shows for it.

Each scenario runs an app in a pseudo terminal of a fixed size with TERM=xterm-256color, types a
fixed sequence of keys, and records every byte the app writes. The recording is then replayed in
tmux, the reference terminal, and its screen is captured, as text (<scenario>.screen.txt) and with
SGR sequences for the attributes (<scenario>.screen.ansi). vtfx-core's RecordingReplayTest replays
the recordings and compares its screen with the reference (docs/design/testing.md section 10).

Usage: record.py [--reference-only] OUT_DIR [SCENARIO ...]

With --reference-only, the existing recordings are kept and only the reference screens are made
again.

The apps must be on PATH: vim, less, htop, and tmux. The recordings depend on the versions of the
apps, which are written to OUT_DIR/<scenario>.json.
"""

import fcntl
import json
import os
import pty
import re
import select
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import termios
import time

COLUMNS, ROWS = 80, 24

SAMPLE_TEXT = "\n".join(
    [f"{i:3d}: The quick brown fox jumps over the lazy dog." for i in range(1, 31)]
    + ["日本語のテキスト。全角の文字が二つのセルを占める。", "한국어 텍스트", "混在 mixed テキスト", "", "end"]
) + "\n"

ESC = "\x1b"


def scenarios(work):
    sample = os.path.join(work, "sample.txt")
    tmux_socket = os.path.join(work, "tmux.sock")
    return {
        # vim: line numbers, a search highlight, insert mode with wide characters, and a split.
        "vim": (["vim", "--clean", "-c", "set number hlsearch", sample], [
            (1.5, ":30\r"), (0.5, "/fox\r"), (0.5, "o日本語を入力した行" + ESC), (0.5, ":split\r"),
            (0.5, "\x17j"), (0.5, "G"), (1.0, ""),
        ]),
        # less: scrolling, a search, and a status line.
        "less": (["less", "-N", sample], [
            (1.0, " "), (0.5, "/日本\r"), (0.5, "k"), (1.0, ""),
        ]),
        # htop: colors, meters, and a process list that is redrawn.
        "htop": (["htop", "--no-mouse", "--delay=10"], [
            (2.0, ""), (1.0, "t"), (1.5, ""),
        ]),
        # tmux: a status line, split panes, and drawing of borders.
        "tmux": (["tmux", "-S", tmux_socket, "-f", "/dev/null", "new-session"], [
            (1.5, "\x02%"), (1.0, "echo left\r"), (0.5, "\x02\""), (1.0, "printf '\\033[31mred\\033[0m 日本語\\n'\r"),
            (1.0, "\x02o"), (0.5, "echo again\r"), (1.5, ""),
        ]),
    }


# The rows and columns (1-based, inclusive) of htop's process list that name the users and commands
# of the machine that recorded it: the USER and Command columns, between the header and the
# function key bar.
HTOP_PROCESS_ROWS = range(12, ROWS)
HTOP_PRIVATE_COLUMNS = [range(9, 20), range(68, COLUMNS + 1)]

ESCAPE_SEQUENCE = re.compile(r"\x1b(\[[0-?]*[ -/]*[@-~]|[()][0-9A-Za-z]|.)", re.DOTALL)


def scrub_htop(data):
    """Returns the htop recording with the users and commands in the process list replaced.

    The letters and digits that land in those cells are replaced with x, X, and 0, so the layout,
    the attributes, and the tree lines stay as recorded. The cursor is tracked through the only
    movements that htop's output uses (CUP, VPA, CHA, and CR); any other control fails, so that a
    new version of htop cannot leave a cell unscrubbed silently.
    """
    text = data.decode("utf-8")
    row, column = 1, 1
    out = []
    position = 0
    while position < len(text):
        match = ESCAPE_SEQUENCE.match(text, position)
        if match:
            sequence = match.group(0)
            position = match.end()
            out.append(sequence)
            final = sequence[-1]
            if sequence.startswith("\x1b[") and final in "HdG":
                parameters = [int(p) if p else 1 for p in sequence[2:-1].split(";")]
                if final == "H":
                    row, column = (parameters + [1])[:2]
                elif final == "d":
                    row = parameters[0]
                else:
                    column = parameters[0]
            continue
        char = text[position]
        position += 1
        if char == "\r":
            column = 1
        elif ord(char) < 0x20:
            raise ValueError(f"unexpected control {ord(char):#x} in the htop recording")
        else:
            if column > COLUMNS:
                raise ValueError("unexpected autowrap in the htop recording")
            if row in HTOP_PROCESS_ROWS and any(column in columns for columns in HTOP_PRIVATE_COLUMNS):
                char = "x" if char.islower() else "X" if char.isupper() else "0" if char.isdigit() else char
            column += 1
        out.append(char)
    return "".join(out).encode("utf-8")


SCRUBBERS = {"htop": scrub_htop}


def record(command, steps, env):
    pid, fd = pty.fork()
    if pid == 0:
        fcntl.ioctl(sys.stdout.fileno(), termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLUMNS, 0, 0))
        os.execvpe(command[0], command, env)
    output = bytearray()

    def read_for(seconds):
        deadline = time.time() + seconds
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                return
            ready, _, _ = select.select([fd], [], [], remaining)
            if ready:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    return
                if not data:
                    return
                output.extend(data)

    for delay, keys in steps:
        read_for(delay)
        if keys:
            os.write(fd, keys.encode("utf-8"))
    read_for(0.5)
    # Stop the app without letting it restore the screen, so that the recording ends on its UI.
    os.kill(pid, signal.SIGKILL)
    os.waitpid(pid, 0)
    os.close(fd)
    return bytes(output)


def reference_screen(recording_path, work):
    """Replays the recording in a tmux of the same size and returns its screen, one line per row.

    The pane's tty is in raw mode, as the apps put it while they ran: output processing would turn
    a bare LF into CR LF, and echo would show tmux's answers to the queries in the recording.
    """
    socket = os.path.join(work, "reference.sock")
    # The status line is turned off by the configuration, before the pane exists. Turning it off
    # later would resize the pane while the recording is replayed.
    config = os.path.join(work, "reference.conf")
    with open(config, "w") as f:
        f.write("set -g status off\n")
    subprocess.run(["tmux", "-S", socket, "-f", config, "new-session", "-d", "-x", str(COLUMNS), "-y", str(ROWS),
                    f"stty raw -echo; cat {recording_path}; sleep 60"], check=True)
    try:
        time.sleep(1.0)
        screen = subprocess.run(["tmux", "-S", socket, "capture-pane", "-p", "-N"], check=True, capture_output=True,
                                text=True).stdout
        # The same screen with SGR sequences for the attributes.
        styled = subprocess.run(["tmux", "-S", socket, "capture-pane", "-p", "-N", "-e"], check=True,
                                capture_output=True, text=True).stdout
    finally:
        subprocess.run(["tmux", "-S", socket, "kill-server"], check=False)
    lines = screen.split("\n")[:ROWS]
    styled_lines = styled.split("\n")[:ROWS]
    return ([line.rstrip() for line in lines] + [""] * (ROWS - len(lines)),
            styled_lines + [""] * (ROWS - len(styled_lines)))


def version(command):
    out = subprocess.run([command, "--version"], capture_output=True, text=True).stdout.strip()
    if not out:
        out = subprocess.run([command, "-V"], capture_output=True, text=True).stdout.strip()
    return out.splitlines()[0] if out else "unknown"


def main():
    args = sys.argv[1:]
    reference_only = args[:1] == ["--reference-only"]
    if reference_only:
        args = args[1:]
    out_dir = args[0]
    names = args[1:]
    os.makedirs(out_dir, exist_ok=True)
    work = tempfile.mkdtemp(prefix="vtfx-record-")
    try:
        with open(os.path.join(work, "sample.txt"), "w", encoding="utf-8") as f:
            f.write(SAMPLE_TEXT)
        env = {
            "PATH": os.environ["PATH"],
            "LD_LIBRARY_PATH": os.environ.get("LD_LIBRARY_PATH", ""),
            "HOME": work,
            "TERM": "xterm-256color",
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "SHELL": "/bin/sh",
            "PS1": "$ ",
            "ENV": "",
        }
        for name, (command, steps) in scenarios(work).items():
            if names and name not in names:
                continue
            path = os.path.join(out_dir, f"{name}.out")
            if reference_only:
                with open(path, "rb") as f:
                    data = f.read()
            else:
                data = SCRUBBERS.get(name, lambda d: d)(record(command, steps, env))
                with open(path, "wb") as f:
                    f.write(data)
            screen, styled = reference_screen(os.path.abspath(path), work)
            with open(os.path.join(out_dir, f"{name}.screen.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(screen) + "\n")
            with open(os.path.join(out_dir, f"{name}.screen.ansi"), "w", encoding="utf-8") as f:
                f.write("\n".join(styled) + "\n")
            meta = {
                "command": command[0],
                "columns": COLUMNS,
                "rows": ROWS,
                "term": "xterm-256color",
                "app_version": version(shutil.which(command[0]) or command[0]),
                "reference": version("tmux"),
                "bytes": len(data),
            }
            with open(os.path.join(out_dir, f"{name}.json"), "w", encoding="utf-8") as f:
                json.dump(meta, f, ensure_ascii=False, indent=2)
                f.write("\n")
            print(f"{name}: {len(data)} bytes")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
