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

The apps must be on PATH: vim, less, htop, tmux, nvim, emacs, mc, btop, tig, fzf, and ncdu. The
recordings depend on the versions of the apps, which are written to OUT_DIR/<scenario>.json.
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
FIXED_MTIME = 1767225600  # 2026-01-01 00:00:00 UTC


def setup_fixtures(work):
    with open(os.path.join(work, "sample.txt"), "w", encoding="utf-8") as f:
        f.write(SAMPLE_TEXT)

    # Midnight Commander: two directories with fixed file sizes and timestamps.
    mc_root = os.path.join(work, "mc")
    mc_left = os.path.join(mc_root, "src")
    mc_right = os.path.join(mc_root, "docs")
    os.makedirs(mc_left, exist_ok=True)
    os.makedirs(mc_right, exist_ok=True)
    for directory, files in [
        (mc_left, [("Emulator.kt", 4820), ("Parser.kt", 3410), ("Reflow.kt", 2190), ("日本語.txt", 256)]),
        (mc_right, [("README.md", 920), ("emulation.md", 6140), ("仕様.md", 1536)]),
    ]:
        for name, size in files:
            path = os.path.join(directory, name)
            with open(path, "w", encoding="utf-8") as f:
                f.write("x" * size)
            os.utime(path, (FIXED_MTIME, FIXED_MTIME))
        os.utime(directory, (FIXED_MTIME, FIXED_MTIME))
    os.utime(mc_root, (FIXED_MTIME, FIXED_MTIME))
    mc_cfg = os.path.join(work, ".config", "mc")
    os.makedirs(mc_cfg, exist_ok=True)
    with open(os.path.join(mc_cfg, "ini"), "w", encoding="utf-8") as f:
        f.write("[Midnight-Commander]\nconfirm_exit=false\n")

    # btop: hide disks, network interfaces, GPU names, battery, and hostname/clock.
    with open(os.path.join(work, "btop.conf"), "w", encoding="utf-8") as f:
        f.write(
            "\n".join(
                [
                    'color_theme = "Default"',
                    "theme_background = true",
                    "truecolor = true",
                    'shown_boxes = "cpu mem proc"',
                    "show_disks = false",
                    'custom_cpu_name = "CPU"',
                    'clock_format = ""',
                    'show_gpu_info = "Off"',
                    'shown_gpus = ""',
                    "show_battery = false",
                    "update_ms = 2000",
                    "save_config_on_exit = false",
                ]
            )
            + "\n"
        )

    # tig: a synthetic Git repository with fixed author, committer, and timestamps.
    repo = os.path.join(work, "repo")
    os.makedirs(repo, exist_ok=True)
    git_env = {
        "PATH": os.environ["PATH"],
        "HOME": work,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_AUTHOR_NAME": "Alice",
        "GIT_AUTHOR_EMAIL": "alice@example.com",
        "GIT_COMMITTER_NAME": "Alice",
        "GIT_COMMITTER_EMAIL": "alice@example.com",
    }
    subprocess.run(["git", "init", "-q", "-b", "main"], cwd=repo, env=git_env, check=True)
    commits = [
        (
            "2026-01-10T09:00:00Z",
            "Initial parser and screen buffer",
            {"Parser.kt": "class Parser {\n    fun parse(input: ByteArray) {}\n}\n"},
        ),
        (
            "2026-01-12T14:30:00Z",
            "Handle wide characters (全角文字) at the margin",
            {
                "Parser.kt": (
                    "class Parser {\n"
                    "    // 全角文字と結合文字の幅を計算する\n"
                    "    fun parse(input: ByteArray) {\n"
                    "        require(input.isNotEmpty())\n"
                    "    }\n"
                    "}\n"
                ),
                "README.md": "# Terminal\n\nSupports UTF-8, 日本語, and 한국어.\n",
            },
        ),
        (
            "2026-01-15T18:15:00Z",
            "Cancel pending wrap on erase in line",
            {
                "Emulator.kt": (
                    "class Emulator {\n"
                    "    var pendingWrap = false\n"
                    "    fun eraseInLine() { pendingWrap = false }\n"
                    "}\n"
                )
            },
        ),
    ]
    for date, message, files in commits:
        for name, content in files.items():
            with open(os.path.join(repo, name), "w", encoding="utf-8") as f:
                f.write(content)
        subprocess.run(["git", "add", "."], cwd=repo, env=git_env, check=True)
        commit_env = {**git_env, "GIT_AUTHOR_DATE": date, "GIT_COMMITTER_DATE": date}
        subprocess.run(["git", "commit", "-q", "-m", message], cwd=repo, env=commit_env, check=True)
    subprocess.run(["git", "tag", "v1.0"], cwd=repo, env=git_env, check=True)

    # fzf: synthetic project files for fuzzy matching and preview.
    fzf_dir = os.path.join(work, "project")
    os.makedirs(os.path.join(fzf_dir, "src"), exist_ok=True)
    os.makedirs(os.path.join(fzf_dir, "doc"), exist_ok=True)
    fzf_files = {
        "src/Emulator.kt": "class Emulator {\n    fun write(text: String) {}\n    // 全角文字の折り返し\n}\n",
        "src/Parser.kt": "class Parser {\n    fun advance(byte: Int) {}\n    // DEC ANSI state machine\n}\n",
        "src/Reflow.kt": "object Reflow {\n    fun reflow(columns: Int) {}\n}\n",
        "doc/emulation.md": "# Emulation\n\nSupported control sequences and SGR attributes.\n",
        "doc/日本語ガイド.md": "# ガイド\n\n端末エミュレータの設計とテスト手順。\n",
    }
    for rel, content in fzf_files.items():
        with open(os.path.join(fzf_dir, rel), "w", encoding="utf-8") as f:
            f.write(content)
    with open(os.path.join(fzf_dir, "files.list"), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(fzf_files.keys())) + "\n")

    # ncdu: synthetic JSON scan so paths and block sizes do not depend on the host filesystem.
    ncdu_tree = [
        1,
        2,
        {"progname": "ncdu", "progver": "1.22", "timestamp": FIXED_MTIME},
        [
            {"name": "~/project", "asize": 4096},
            [
                {"name": "src", "asize": 4096},
                {"name": "Emulator.kt", "asize": 45056, "dsize": 49152},
                {"name": "Parser.kt", "asize": 28672, "dsize": 32768},
                {"name": "Reflow.kt", "asize": 14336, "dsize": 16384},
            ],
            [
                {"name": "docs", "asize": 4096},
                {"name": "emulation.md", "asize": 18432, "dsize": 20480},
                {"name": "仕様.md", "asize": 8192, "dsize": 12288},
            ],
            {"name": "build.gradle.kts", "asize": 3072, "dsize": 4096},
            {"name": "README.md", "asize": 2048, "dsize": 4096},
        ],
    ]
    with open(os.path.join(work, "ncdu.json"), "w", encoding="utf-8") as f:
        json.dump(ncdu_tree, f, ensure_ascii=False)


def scenarios(work):
    sample = os.path.join(work, "sample.txt")
    tmux_socket = os.path.join(work, "tmux.sock")
    return {
        # vim: line numbers, a search highlight, insert mode with wide characters, and a split.
        "vim": (["vim", "--clean", "-c", "set number hlsearch", sample], [
            (1.5, ":30\r"), (0.5, "/fox\r"), (0.5, "o日本語を入力した行" + ESC), (0.5, ":split\r"),
            (0.5, "\x17j"), (0.5, "G"), (1.0, ""),
        ], work, {}),
        # less: scrolling, a search, and a status line.
        "less": (["less", "-N", sample], [
            (1.0, " "), (0.5, "/日本\r"), (0.5, "k"), (1.0, ""),
        ], work, {}),
        # htop: colors, meters, and a process list that is redrawn.
        "htop": (["htop", "--no-mouse", "--delay=10"], [
            (2.0, ""), (1.0, "t"), (1.5, ""),
        ], work, {}),
        # tmux: a status line, split panes, and drawing of borders.
        "tmux": (["tmux", "-S", tmux_socket, "-f", "/dev/null", "new-session"], [
            (1.5, "\x02%"), (1.0, "echo left\r"), (0.5, "\x02\""), (1.0, "printf '\\033[31mred\\033[0m 日本語\\n'\r"),
            (1.0, "\x02o"), (0.5, "echo again\r"), (1.5, ""),
        ], work, {}),
        # neovim: truecolor default theme, vertical split, search highlight, and wide characters.
        "neovim": (["nvim", "--clean", "-c", "set number hlsearch", sample], [
            (1.5, ":30\r"), (0.5, "/fox\r"), (0.5, "o日本語を入力した行" + ESC), (0.5, ":vsplit\r"),
            (0.5, "\x17l"), (0.5, "gg"), (1.0, ""),
        ], work, {}),
        # emacs: incremental search, horizontal split with mode-lines, and CJK editing.
        "emacs": (["emacs", "-nw", "-Q", sample], [
            (1.5, "\x13fox\r"), (0.5, "\x182"), (0.5, "\x18o"), (0.5, ESC + ">"),
            (0.5, "\x10\x10\x10\x01日本語の行\r"), (1.0, ""),
        ], work, {}),
        # mc: dual-pane Midnight Commander with box drawing, colors, and CJK filenames.
        "mc": (["mc", "--nosubshell", os.path.join(work, "mc", "src"), os.path.join(work, "mc", "docs")], [
            (1.5, ESC + "[B"), (0.5, "\t"), (0.5, ESC + "[B"), (1.0, ""),
        ], os.path.join(work, "mc"), {}),
        # btop: rounded borders, truecolor gradients, Braille graphs, and synchronized output.
        "btop": (["btop", "--force-utf", "-c", os.path.join(work, "btop.conf")], [
            (2.0, ""), (1.0, ESC + "[B" + ESC + "[B"), (1.5, ""),
        ], work, {}),
        # tig: commit log and split diff view with colors and wide characters.
        "tig": (["tig"], [
            (1.5, "\r"), (0.5, "j"), (1.0, ""),
        ], os.path.join(work, "repo"), {}),
        # fzf: fuzzy finder with border, query highlight, and preview pane.
        "fzf": (["fzf", "--layout=reverse", "--border", "--info=inline", "--preview=cat {}"], [
            (1.5, "o"), (0.5, ESC + "[B"), (1.0, ""),
        ], os.path.join(work, "project"), {"FZF_DEFAULT_COMMAND": "cat files.list"}),
        # ncdu: disk usage browser with dark color scheme, usage bars, and CJK filenames.
        "ncdu": (["ncdu", "--ignore-config", "--color", "dark", "--graph-style", "half-block", "-f", os.path.join(work, "ncdu.json")], [
            (1.5, ESC + "[B" + ESC + "[B"), (0.5, "\r"), (1.0, ""),
        ], work, {}),
    }


# The rows and columns (1-based, inclusive) of htop's and btop's process lists that name the users
# and commands of the machine that recorded them.
HTOP_PROCESS_ROWS = range(12, ROWS)
HTOP_PRIVATE_COLUMNS = [range(9, 20), range(68, COLUMNS + 1)]
BTOP_PROCESS_ROWS = range(11, ROWS)
BTOP_PRIVATE_COLUMNS = [range(47, 62)]

ESCAPE_SEQUENCE = re.compile(r"\x1b(\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[()][0-9A-Za-z]|.)", re.DOTALL)


def scrub_cells(data, name, private_rows, private_columns):
    """Replaces alphanumeric characters that land in private rows and columns with x, X, and 0.

    The layout, the attributes, and the tree or graph characters stay as recorded. The cursor is
    tracked through the movements that htop and btop emit (CUP, HVP, VPA, CHA, CUU, CUD, CUF, CUB,
    and CR); any other control fails, so that a new version cannot leave a cell unscrubbed silently.
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
            if sequence.startswith("\x1b[") and sequence[2:3] not in "?><=":
                raw_params = sequence[2:-1].split(";")
                if all(p.isdigit() or p == "" for p in raw_params):
                    parameters = [int(p) if p else 1 for p in raw_params]
                    if final in "Hf":
                        row, column = (parameters + [1])[:2]
                    elif final == "d":
                        row = parameters[0]
                    elif final == "G":
                        column = parameters[0]
                    elif final == "A":
                        row = max(1, row - parameters[0])
                    elif final == "B":
                        row = min(ROWS, row + parameters[0])
                    elif final == "C":
                        column = min(COLUMNS, column + parameters[0])
                    elif final == "D":
                        column = max(1, column - parameters[0])
            continue
        char = text[position]
        position += 1
        if char == "\r":
            column = 1
        elif ord(char) < 0x20:
            raise ValueError(f"unexpected control {ord(char):#x} in the {name} recording")
        else:
            if column > COLUMNS:
                raise ValueError(f"unexpected autowrap in the {name} recording")
            if row in private_rows and any(column in columns for columns in private_columns):
                char = "x" if char.islower() else "X" if char.isupper() else "0" if char.isdigit() else char
            column += 1
        out.append(char)
    return "".join(out).encode("utf-8")


def scrub_htop(data):
    return scrub_cells(data, "htop", HTOP_PROCESS_ROWS, HTOP_PRIVATE_COLUMNS)


def scrub_btop(data):
    return scrub_cells(data, "btop", BTOP_PROCESS_ROWS, BTOP_PRIVATE_COLUMNS)


def scrub_mc(data):
    """Replaces the user, host, and temporary path in Midnight Commander's OSC 0 and OSC 7 strings."""
    text = data.decode("utf-8")
    text = re.sub(r"\x1b\]0;mc \[[^\x07\x1b]*\]:", "\x1b]0;mc [user@host]:", text)
    text = re.sub(r"\x1b\]7;file://[^\x07\x1b]*(?=\x07|\x1b\\)", "\x1b]7;file://host/home/user", text)
    return text.encode("utf-8")


SCRUBBERS = {
    "htop": scrub_htop,
    "btop": scrub_btop,
    "mc": scrub_mc,
}

PASSTHROUGH_ENV = (
    "LD_LIBRARY_PATH",
    "VIMRUNTIME",
    "EMACSDATA",
    "EMACSDOC",
    "EMACSLOADPATH",
    "EMACSPATH",
    "MC_DATADIR",
)


def record(command, steps, env, cwd):
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(cwd)
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
        setup_fixtures(work)
        base_env = {
            "PATH": os.environ["PATH"],
            "HOME": work,
            "XDG_CONFIG_HOME": os.path.join(work, ".config"),
            "TERM": "xterm-256color",
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "TZ": "UTC",
            "SHELL": "/bin/sh",
            "PS1": "$ ",
            "ENV": "",
        }
        for key in PASSTHROUGH_ENV:
            if key in os.environ:
                base_env[key] = os.environ[key]
        for name, (command, steps, cwd, extra_env) in scenarios(work).items():
            if names and name not in names:
                continue
            path = os.path.join(out_dir, f"{name}.out")
            if reference_only:
                with open(path, "rb") as f:
                    data = f.read()
            else:
                env = {**base_env, **extra_env}
                data = SCRUBBERS.get(name, lambda d: d)(record(command, steps, env, cwd))
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
                "app_version": version(shutil.which(command[0], path=base_env["PATH"]) or command[0]),
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
