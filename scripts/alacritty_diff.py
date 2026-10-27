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

"""Compares vtfx-core against alacritty_terminal and writes a Markdown report.

Feeds the same inputs and resize operations to vtfx-core (DifferentialHost in vtfx-core's tests)
and to alacritty_terminal 0.26.0 (tools/alacritty-diff), and compares the resulting screen text,
cursor position, and optionally cell styles. For reports, not a gate (docs/design/testing.md).

Inputs:
- spec/core/*.vtspec (and spec-proposals/core/*.vtspec if present)
- Fuzzers' corpora (vtfx-core's TerminalFuzzer and vtfx-gate's GateFuzzer)
- App recordings (recordings/*.out and recordings/graphics/*.out)
- Deterministically generated sequences covering docs/design/emulation.md

Usage: alacritty_diff.py [--report PATH] [--include REGEX] [--styles] [--extra-corpus DIR]
"""

import argparse
import dataclasses
import datetime
import json
import os
import random
import re
import select
import subprocess
import sys
import time
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ALACRITTY_CRATE_VERSION = "0.26.0"
DEFAULT_SCROLLBACK = 10_000


@dataclasses.dataclass(frozen=True)
class WriteOp:
    data: bytes


@dataclasses.dataclass(frozen=True)
class ResizeOp:
    columns: int
    rows: int


Op = WriteOp | ResizeOp


@dataclasses.dataclass(frozen=True)
class DiffCase:
    case_id: str
    category: str
    columns: int
    rows: int
    scrollback: int
    ops: tuple[Op, ...]


@dataclasses.dataclass(frozen=True)
class EngineResult:
    status: str  # "ok" or "err"
    cursor: tuple[int, int] = (0, 0)
    lines: tuple[str, ...] = ()
    styles: tuple[str, ...] = ()
    error: str = ""


def escape_bytes(data: bytes, limit: int = 240) -> str:
    """Formats bytes into readable ASCII + \\e / \\r / \\n / \\t / \\b / \\xNN escapes."""
    parts = []
    shown = data[:limit]
    for b in shown:
        if b == 0x1B:
            parts.append("\\e")
        elif b == 0x0D:
            parts.append("\\r")
        elif b == 0x0A:
            parts.append("\\n")
        elif b == 0x09:
            parts.append("\\t")
        elif b == 0x08:
            parts.append("\\b")
        elif b == 0x5C:
            parts.append("\\\\")
        elif 0x20 <= b <= 0x7E:
            parts.append(chr(b))
        else:
            parts.append(f"\\x{b:02x}")
    if len(data) > limit:
        parts.append(f"...(+{len(data) - limit}B)")
    return "".join(parts)


def describe_ops(case: DiffCase, limit: int = 320) -> str:
    """Summarizes initial geometry and operations of a case for reason matching and reporting."""
    pieces = [f"size={case.columns}x{case.rows} sb={case.scrollback}"]
    for op in case.ops:
        if isinstance(op, WriteOp):
            pieces.append(f'W("{escape_bytes(op.data, limit=160)}")')
        else:
            pieces.append(f"R({op.columns}x{op.rows})")
        if sum(len(p) for p in pieces) > limit:
            pieces.append("...")
            break
    return " ".join(pieces)


def decode_vtspec_string(quoted: str) -> bytes:
    if len(quoted) < 2 or not (quoted.startswith('"') and quoted.endswith('"')):
        raise ValueError(f"bad vtspec string: {quoted}")
    body = quoted[1:-1]
    out = bytearray()
    i = 0
    while i < len(body):
        ch = body[i]
        if ch != "\\":
            out.extend(ch.encode("utf-8"))
            i += 1
            continue
        esc = body[i + 1]
        if esc == "e":
            out.append(0x1B)
        elif esc == "b":
            out.append(0x08)
        elif esc == "r":
            out.append(0x0D)
        elif esc == "n":
            out.append(0x0A)
        elif esc == "t":
            out.append(0x09)
        elif esc == "\\":
            out.append(ord("\\"))
        elif esc == '"':
            out.append(ord('"'))
        elif esc == "x":
            out.append(int(body[i + 2 : i + 4], 16))
            i += 2
        elif esc == "u":
            code = int(body[i + 2 : i + 6], 16)
            out.extend(chr(code).encode("utf-8"))
            i += 4
        else:
            raise ValueError(f"unknown escape \\{esc}")
        i += 2
    return bytes(out)


def load_vtspec_cases(directory: str, prefix: str) -> list[DiffCase]:
    if not os.path.isdir(directory):
        return []
    cases = []
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".vtspec"):
            continue
        path = os.path.join(directory, name)
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
        cur_name = None
        cols = rows = 0
        ops: list[Op] = []
        i = 0
        while i < len(lines):
            trimmed = lines[i].strip()
            i += 1
            if trimmed.startswith("=== "):
                if cur_name is not None and cols > 0 and rows > 0:
                    cases.append(DiffCase(f"{prefix}/{name}:{cur_name}", prefix, cols, rows, DEFAULT_SCROLLBACK, tuple(ops)))
                cur_name = trimmed[4:].strip()
                cols = rows = 0
                ops = []
                continue
            if not trimmed or trimmed.startswith("#") or cur_name is None:
                continue
            keyword, _, arg = trimmed.partition(" ")
            arg = arg.strip()
            if keyword == "size":
                c_str, r_str = arg.split("x", 1)
                cols, rows = int(c_str), int(r_str)
            elif keyword == "input":
                ops.append(WriteOp(decode_vtspec_string(arg)))
            elif keyword == "screen":
                while i < len(lines) and lines[i].strip().startswith("|"):
                    i += 1
        if cur_name is not None and cols > 0 and rows > 0:
            cases.append(DiffCase(f"{prefix}/{name}:{cur_name}", prefix, cols, rows, DEFAULT_SCROLLBACK, tuple(ops)))
    return cases


def decode_terminal_fuzz_input(case_id: str, category: str, raw: bytes) -> DiffCase | None:
    """Decodes TerminalFuzzInput (vtfx-core/src/fuzz/.../TerminalFuzzer.kt)."""
    if len(raw) < 3:
        return None
    columns = 1 + (raw[0] % 12)
    rows = 1 + (raw[1] % 6)
    scrollback = raw[2] % 5
    ops: list[Op] = []
    start = 3
    i = 3
    while i < len(raw):
        b = raw[i]
        if b == 0xFE:
            if i > start:
                ops.append(WriteOp(raw[start:i]))
            start = i + 1
        elif b == 0xFF:
            if i > start:
                ops.append(WriteOp(raw[start:i]))
            if i + 1 < len(raw):
                nb = raw[i + 1]
                ops.append(ResizeOp(1 + (nb % 12), 1 + ((nb // 12) % 6)))
            i += 1
            start = i + 1
        i += 1
    if len(raw) > start:
        ops.append(WriteOp(raw[start:]))
    return DiffCase(case_id, category, columns, rows, scrollback, tuple(ops))


def decode_gate_fuzz_input(case_id: str, category: str, raw: bytes) -> DiffCase | None:
    """Decodes GateFuzzer's host-output stream (vtfx-gate/src/fuzz/.../GateFuzzer.kt)."""
    if len(raw) < 7:
        return None
    columns = 1 + (raw[0] % 80)
    rows = 1 + (raw[1] % 24)
    output = raw[7:]
    ops: list[Op] = []
    for part in output.split(b"\xfe"):
        if len(part) >= 3 and part[0] in (0xFD, 0xFC):
            if part[0] == 0xFD:
                ops.append(ResizeOp(1 + part[1], 1 + part[2]))
            part = part[3:]
        if part:
            ops.append(WriteOp(part))
    return DiffCase(case_id, category, columns, rows, DEFAULT_SCROLLBACK, tuple(ops))


def load_fuzz_cases(extra_corpus_dirs: list[str] | None = None) -> list[DiffCase]:
    cases: list[DiffCase] = []
    core_dir = os.path.join(ROOT, "vtfx-core", "src", "fuzz", "corpus", "TerminalFuzzer")
    if os.path.isdir(core_dir):
        for name in sorted(os.listdir(core_dir)):
            path = os.path.join(core_dir, name)
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    decoded = decode_terminal_fuzz_input(f"fuzz-core/{name}", "fuzz-core", f.read())
                if decoded is not None:
                    cases.append(decoded)

    gate_dir = os.path.join(ROOT, "vtfx-gate", "src", "fuzz", "corpus", "GateFuzzer")
    if os.path.isdir(gate_dir):
        for name in sorted(os.listdir(gate_dir)):
            path = os.path.join(gate_dir, name)
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    decoded = decode_gate_fuzz_input(f"fuzz-gate/{name}", "fuzz-gate", f.read())
                if decoded is not None:
                    cases.append(decoded)

    for extra_dir in extra_corpus_dirs or []:
        if not os.path.isdir(extra_dir):
            continue
        for name in sorted(os.listdir(extra_dir)):
            path = os.path.join(extra_dir, name)
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    decoded = decode_terminal_fuzz_input(f"fuzz-extra/{name}", "fuzz-extra", f.read())
                if decoded is not None:
                    cases.append(decoded)
    return cases


def load_recording_cases() -> list[DiffCase]:
    rec_root = os.path.join(ROOT, "recordings")
    cases: list[DiffCase] = []
    for sub, prefix in [("", "recordings"), ("graphics", "recordings-graphics")]:
        directory = os.path.join(rec_root, sub) if sub else rec_root
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".out"):
                continue
            stem = name[:-4]
            meta_path = os.path.join(directory, f"{stem}.json")
            cols, rows = 80, 24
            if os.path.isfile(meta_path):
                with open(meta_path, encoding="utf-8") as mf:
                    meta = json.load(mf)
                cols = int(meta.get("columns", 80))
                rows = int(meta.get("rows", 24))
            with open(os.path.join(directory, name), "rb") as rf:
                data = rf.read()
            cases.append(DiffCase(f"{prefix}/{stem}", prefix, cols, rows, DEFAULT_SCROLLBACK, (WriteOp(data),)))
    return cases


def generated_cases() -> list[DiffCase]:
    """Deterministically generates sequences covering docs/design/emulation.md."""
    cases: list[DiffCase] = []

    def add(name: str, data: bytes | str | list[Op], cols: int = 10, rows: int = 4, scrollback: int = 20) -> None:
        if isinstance(data, str):
            ops: tuple[Op, ...] = (WriteOp(data.encode("utf-8")),)
        elif isinstance(data, bytes):
            ops = (WriteOp(data),)
        else:
            ops = tuple(data)
        cases.append(DiffCase(f"generated/{name}", "generated", cols, rows, scrollback, ops))

    # 1. C0 controls & pending wrap interactions
    add("c0/bs-edges-and-pending-wrap", "abcdefghij\bZ\r\n\bX", cols=10, rows=3)
    add("c0/ht-default-stops", "a\tb\tc\r\n12345678\tX", cols=20, rows=3)
    add("c0/ht-over-existing-text", "0123456789\r\tX", cols=12, rows=2)
    add("c0/ht-at-pending-wrap", "abcdefghij\tZ", cols=10, rows=3)
    add("c0/ht-cr-at-pending-wrap", "abcdef\t\rZ", cols=6, rows=2)
    add("c0/lf-ind-ri-at-pending-wrap", "abcdef\nX\r\nabcdef\x1bMY", cols=6, rows=3)
    add("c0/lf-vt-ff-without-cr", "ab\ncd\x0bef\x0cgh", cols=10, rows=5)
    add("c0/lnm-mode-20", "\x1b[20hab\ncd\x1b[20l\nef", cols=10, rows=5)
    add("c0/cr-clears-pending-wrap", "abcdefghij\rZ", cols=10, rows=2)
    add("c0/nul-and-del-ignored", "a\x00b\x7fc\x1b[\x002;\x7f3Hd", cols=10, rows=3)
    add("c0/can-sub-abort-csi", "A\x1b[31\x18B\x1b[2J\x1b[1;1H\x1b[99\x1aC", cols=10, rows=3)
    add("c0/can-sub-abort-osc", "A\x1b]2;title\x18B\x1b]8;;https://example.com\x1aC", cols=10, rows=2)
    add("c0/c0-inside-csi-executes", "abcde\x1b[2\bD!", cols=10, rows=2)

    # 2. ESC sequences & character sets
    add("esc/ind-nel-ri", "A\x1bDB\x1bEC\x1bMD", cols=10, rows=4)
    add("esc/ri-at-top-scrolls-down", "1\r\n2\r\n3\x1b[1;2H\x1bMX", cols=6, rows=3)
    add("esc/hts-and-tbc", "\x1b[3g   \x1bH   \x1bH\r\tA\tB\r\n\x1b[4G\x1b[0g\r\tC", cols=12, rows=3)
    add("esc/tbc-invalid-param-ignored", "a\tb\x1b[2g\r\tc", cols=12, rows=2)
    add("esc/decaln-resets-margins-and-fills-e", "\x1b[2;3r\x1b[?6h\x1b[2;2H\x1b#8Z", cols=6, rows=4)
    add("esc/decsc-decrc-basic", "\x1b[2;4H\x1b7\x1b[4;1Habc\x1b8X", cols=8, rows=4)
    add("esc/decsc-decrc-pending-wrap", "abcdefgh\x1b7\r12\x1b8Z", cols=8, rows=3)
    add("esc/decsc-decrc-origin-mode", "\x1b[2;4r\x1b[?6h\x1b[2;3H\x1b7\x1b[?6l\x1b[1;1H\x1b8X", cols=8, rows=4)
    add("esc/scosc-scorc-csi-s-u", "\x1b[2;4H\x1b[s\x1b[3;1Habc\x1b[uX", cols=8, rows=4)
    add("charset/dec-special-graphics-g0-g1", "\x1b(0lqk\r\nx x\r\nmqj\x1b(B\x1b)0\x0e`\x0f`", cols=8, rows=4)
    add("charset/uk-and-ascii", "\x1b(A#!\x1b)B\x0e#\x0f#", cols=8, rows=2)
    add("charset/g2-g3-designate-and-ss2-ss3", "\x1b*0\x1b+A\x1bNl\x1bO#q", cols=8, rows=2)

    # 3. Cursor movements & pending wrap / margins / DECOM
    for cmd, ch in [
        ("cuu", "A"),
        ("cud", "B"),
        ("cuf", "C"),
        ("cub", "D"),
        ("cnl", "E"),
        ("cpl", "F"),
        ("cha", "G"),
        ("hpa", "`"),
        ("hpr", "a"),
        ("vpa", "d"),
        ("vpr", "e"),
        ("cht", "I"),
        ("cbt", "Z"),
    ]:
        for param in ["", "0", "1", "2", "99"]:
            p_label = param or "default"
            add(f"cursor/{cmd}-param-{p_label}", f"\x1b[2;5H*\x1b[{param}{ch}X", cols=10, rows=4)
        # Interaction with pending wrap
        add(f"cursor/{cmd}-from-pending-wrap", f"abcdefghij\x1b[1{ch}X", cols=10, rows=3)

    add("cursor/cup-hvp-defaults-and-clamping", "\x1b[HA\x1b[;HB\x1b[0;0HC\x1b[99;99HD\x1b[2;3fE", cols=8, rows=4)
    add("cursor/cuu-cud-inside-and-outside-margins", "\x1b[2;3r\x1b[2;2H\x1b[5AA\x1b[3;4H\x1b[5BB\x1b[1;6H\x1b[5BC\x1b[4;8H\x1b[5AD", cols=10, rows=4)
    add("cursor/cnl-cpl-with-margins", "\x1b[2;3r\x1b[2;4H\x1b[5EA\x1b[3;6H\x1b[5FB", cols=10, rows=4)
    add("cursor/decom-cup-vpa-clamping", "\x1b[2;3r\x1b[?6h\x1b[1;2HA\x1b[99;99HB\x1b[2dC\x1b[?6l\x1b[1;1HD", cols=8, rows=4)

    # 4. Erasing & editing
    for ps in [0, 1, 2, 3, 9]:
        for row in [1, 2, 4]:
            for col in [1, 4, 8]:
                add(
                    f"erase/ed-{ps}-r{row}-c{col}",
                    f"11111111\r\n22222222\r\n33333333\r\n44444444\x1b[{row};{col}H\x1b[{ps}J",
                    cols=8,
                    rows=4,
                )
        add(f"erase/ed-{ps}-pending-wrap", f"11111111\r\n22222222\x1b[1;1H33333333\x1b[{ps}J", cols=8, rows=3)

    for ps in [0, 1, 2, 9]:
        for col in [1, 4, 8]:
            add(f"erase/el-{ps}-c{col}", f"abcdefgh\x1b[1;{col}H\x1b[{ps}K", cols=8, rows=2)
        add(f"erase/el-{ps}-pending-wrap", f"abcdefgh\x1b[{ps}K", cols=8, rows=2)

    add("erase/decsed-decsel", "111111\r\n222222\x1b[1;3H\x1b[?0K\x1b[2;4H\x1b[?1J", cols=6, rows=2)
    add("erase/ech-counts-and-pending-wrap", "abcdefgh\x1b[1;3H\x1b[3X\r\nabcdefgh\x1b[2X", cols=8, rows=3)
    add("erase/wide-split-by-el-ed-ech", "あいおう\x1b[1;2H\x1b[1X\r\nかきくけ\x1b[2;4H\x1b[0K\r\nさしすせ\x1b[3;3H\x1b[1K", cols=8, rows=4)

    add("edit/ich-narrow-and-wide", "abcdefgh\x1b[1;3H\x1b[2@XY\r\nあいおう\x1b[2;3H\x1b[1@Z\r\nかきくけ\x1b[3;2H\x1b[1@W", cols=8, rows=4)
    add("edit/ich-pushes-wide-half-off-edge", "aあいお\x1b[1;1H\x1b[2@", cols=8, rows=2)
    add("edit/ich-pending-wrap", "abcdefgh\x1b[2@Z", cols=8, rows=2)
    add("edit/dch-narrow-and-wide", "abcdefgh\x1b[1;3H\x1b[2P\r\nあいおう\x1b[2;2H\x1b[1P\r\nかきくけ\x1b[3;3H\x1b[1P", cols=8, rows=4)
    add("edit/dch-pending-wrap", "abcdefgh\x1b[1PZ", cols=8, rows=2)
    add("edit/il-dl-inside-and-outside-margins", "1111\r\n2222\r\n3333\r\n4444\x1b[2;3r\x1b[1;2H\x1b[L\x1b[4;2H\x1b[M\x1b[2;3H\x1b[LX", cols=6, rows=4)
    add("edit/il-dl-cursor-column", "111111\r\n222222\r\n333333\x1b[2;4H\x1b[1L!\x1b[3;5H\x1b[1M?", cols=6, rows=4)
    add("edit/irm-narrow-and-wide", "abcdef\x1b[1;2H\x1b[4hXY\x1b[4lZ\r\nあいお\x1b[2;1H\x1b[4hW\x1b[4l", cols=6, rows=3)
    add("edit/rep-ascii-wide-charset-irm", "A\x1b[4b!\r\nあ\x1b[2b!\r\n\x1b(0q\x1b[3b\x1b(B\r\n123456\x1b[4;2H\x1b[4hZ\x1b[2b\x1b[4l", cols=8, rows=4)
    add("edit/rep-before-any-print-and-after-csi", "\x1b[3bA\x1b[1;3H\x1b[2b", cols=8, rows=2)

    # 5. Scrolling & margins
    add("scroll/decstbm-basic-and-scrollback", "\x1b[2;3r1\r\n2\r\n3\r\n4\r\n5", cols=6, rows=4)
    add("scroll/decstbm-invalid-regions", "\x1b[3;2rA\x1b[2;2rB\x1b[99;100rC", cols=6, rows=4)
    add("scroll/su-sd-within-margins", "1111\r\n2222\r\n3333\r\n4444\x1b[2;3r\x1b[1S\x1b[1;1H!\x1b[2T", cols=6, rows=4)
    add("scroll/lf-outside-bottom-margin-stops-at-bottom", "1\r\n2\r\n3\r\n4\x1b[1;2r\x1b[3;1HA\nB\nC\nD", cols=6, rows=4)
    add("scroll/ri-outside-top-margin-stops-at-top", "1\r\n2\r\n3\r\n4\x1b[3;4r\x1b[2;1HA\x1bMB\x1bMC", cols=6, rows=4)

    # 6. Modes, alternate screen, and resets
    add("modes/decawm-off-and-toggle-pending-wrap", "\x1b[?7labcdefghij\r\n\x1b[?7h123456\x1b[?7lZ", cols=6, rows=3)
    add("modes/alt-screen-1049", "main1\r\nmain2\x1b[?1049halt\x1b[?1049l!", cols=8, rows=3)
    add("modes/alt-screen-1047-and-47", "main\x1b[?1047halt1\x1b[?1047l!\x1b[?47halt2\x1b[?47l?", cols=10, rows=3)
    add("modes/save-cursor-1048", "\x1b[2;3H\x1b[?1048h\x1b[1;1Habc\x1b[?1048lZ", cols=8, rows=3)
    add("modes/decrst-6-homes-cursor", "\x1b[2;3r\x1b[?6h\x1b[2;2H\x1b[?6lZ\x1b[2;4H\x1b[?6lW", cols=8, rows=4)
    add("modes/multi-param-decset", "\x1b[2;3r\x1b[?6;7l\x1b[1;1Habcdefgh", cols=6, rows=4)
    add("reset/decstr-soft-reset", "\x1b[2;3r\x1b[?6h\x1b[?7l\x1b(0\x1b[31m\x1b[!pq", cols=8, rows=4)
    add("reset/ris-hard-reset", "abc\x1b[2;3r\x1b[?1049halt\x1bcZ", cols=8, rows=4)

    # 7. SGR attributes & BCE
    add("sgr/basic-flags-and-resets", "\x1b[1;2;3;4;7;8;9mA\x1b[22;23;24;27;28;29mB\x1b[0mC", cols=8, rows=2)
    add("sgr/colors-16-256-rgb-semicolon-and-colon", "\x1b[31;42mA\x1b[91;102mB\x1b[38;5;200;48:5:40mC\x1b[38;2;1;2;3;48:2::4:5:6mD\x1b[39;49mE", cols=10, rows=2)
    add("sgr/underlines-and-underline-color", "\x1b[4:1mA\x1b[4:2mB\x1b[4:3;58:5:196mC\x1b[4:4;58;2;10;20;30mD\x1b[4:5;59mE\x1b[4:0mF\x1b[21mG", cols=10, rows=2)
    add("sgr/overline-53-55", "\x1b[53mA\x1b[55mB", cols=6, rows=2)
    add("sgr/bce-on-erase-and-scroll", "\x1b[44m111111\r\n222222\r\n333333\r\n\x1b[41m\x1b[2;2H\x1b[K\x1b[3;1H\n\x1b[0m", cols=6, rows=3)
    add("sgr/wide-wrap-padding-style", "\x1b[31;44;1;4m12345漢\x1b[0m", cols=6, rows=2)

    # 8. Unicode, wide chars, combining marks, split UTF-8
    add("unicode/wide-wrap-at-odd-width", "aあいう", cols=5, rows=3)
    add("unicode/wide-overwrite-left-and-right-half", "あいう\x1b[1;1HX\x1b[1;4HY", cols=6, rows=2)
    add("unicode/wide-overwrite-with-wide-offset-by-one", "あいう\x1b[1;2Hかき", cols=6, rows=2)
    add("unicode/wide-in-1-column-grid", "aあb", cols=1, rows=4)
    add("unicode/combining-marks-narrow-and-wide", "e\u0301a\u0300\u0302あ\u3099!", cols=8, rows=2)
    add("unicode/combining-at-col-0-and-pending-wrap", "\u0301abcdef\u0301Z", cols=6, rows=2)
    add("unicode/emoji-zwj-and-variation-selector", "😀\u2764\ufe0f👨\u200d👩x", cols=10, rows=2)
    add("unicode/regional-indicator-flags", "a🇯🇵🇺🇸b", cols=10, rows=2)
    add(
        "unicode/utf8-split-across-writes",
        [
            WriteOp(b"\xe3\x81"),
            WriteOp(b"\x82\xf0\x9f"),
            WriteOp(b"\x98\x80!"),
        ],
        cols=8,
        rows=2,
    )
    add(
        "unicode/utf8-malformed-maximal-subpart",
        b"A\xe3\x81B\xf0\x9f\x98C\xc0\xafD\xed\xa0\x80E",
        cols=14,
        rows=2,
    )
    add(
        "unicode/csi-split-across-writes",
        [
            WriteOp(b"abc\x1b"),
            WriteOp(b"[1;"),
            WriteOp(b"2HX"),
        ],
        cols=8,
        rows=2,
    )

    # 9. Resize & reflow
    add(
        "resize/wrap-narrower-and-wider",
        [
            WriteOp(b"abcdefghij12345"),
            ResizeOp(5, 4),
            ResizeOp(10, 4),
        ],
        cols=10,
        rows=4,
    )
    add(
        "resize/wide-chars-reflow",
        [
            WriteOp("あいうえお".encode("utf-8")),
            ResizeOp(5, 4),
            ResizeOp(4, 4),
            ResizeOp(8, 4),
        ],
        cols=6,
        rows=4,
    )
    add(
        "resize/height-shrink-and-grow-with-scrollback",
        [
            WriteOp(b"line1\r\nline2\r\nline3\r\nline4"),
            ResizeOp(8, 2),
            ResizeOp(8, 4),
        ],
        cols=8,
        rows=4,
    )
    add(
        "resize/alt-screen-then-leave",
        [
            WriteOp(b"primary-long-line"),
            WriteOp(b"\x1b[?1049halt-line"),
            ResizeOp(6, 3),
            WriteOp(b"\x1b[?1049l"),
        ],
        cols=10,
        rows=4,
    )
    add(
        "resize/with-margins-active",
        [
            WriteOp(b"\x1b[2;3r1111\r\n2222\r\n3333"),
            ResizeOp(6, 5),
            WriteOp(b"\r\n4444\r\n5555"),
        ],
        cols=6,
        rows=4,
    )

    # 10. Deterministic PRNG stress combinations (fixed seed)
    rng = random.Random(0x56544658)
    tokens = [
        b"a",
        b"bcdef",
        "あ".encode("utf-8"),
        "漢".encode("utf-8"),
        "e\u0301".encode("utf-8"),
        b"\r",
        b"\n",
        b"\r\n",
        b"\b",
        b"\t",
        b"\x1bD",
        b"\x1bE",
        b"\x1bM",
        b"\x1b7",
        b"\x1b8",
        b"\x1b(0qxlkmj\x1b(B",
        b"\x1b[A",
        b"\x1b[B",
        b"\x1b[2C",
        b"\x1b[2D",
        b"\x1b[E",
        b"\x1b[F",
        b"\x1b[3G",
        b"\x1b[2d",
        b"\x1b[1;1H",
        b"\x1b[2;4H",
        b"\x1b[99;99H",
        b"\x1b[I",
        b"\x1b[Z",
        b"\x1b[0J",
        b"\x1b[2J",
        b"\x1b[0K",
        b"\x1b[1K",
        b"\x1b[2K",
        b"\x1b[2X",
        b"\x1b[1@",
        b"\x1b[2@",
        b"\x1b[1P",
        b"\x1b[2P",
        b"\x1b[1L",
        b"\x1b[1M",
        b"\x1b[1S",
        b"\x1b[1T",
        b"\x1b[2b",
        b"\x1b[2;4r",
        b"\x1b[r",
        b"\x1b[?6h",
        b"\x1b[?6l",
        b"\x1b[?7l",
        b"\x1b[?7h",
        b"\x1b[4h",
        b"\x1b[4l",
        b"\x1b[?1049h",
        b"\x1b[?1049l",
        b"\x1b[31;44;1;4m",
        b"\x1b[0m",
    ]
    for idx in range(200):
        cols = rng.choice([4, 5, 6, 8, 10, 12])
        rows = rng.choice([3, 4, 5, 6])
        sb = rng.choice([0, 2, 5, 20])
        seq_ops: list[Op] = []
        buf = bytearray()
        steps = rng.randint(8, 24)
        for _ in range(steps):
            if rng.random() < 0.12:
                if buf:
                    seq_ops.append(WriteOp(bytes(buf)))
                    buf.clear()
                seq_ops.append(ResizeOp(rng.choice([3, 4, 5, 6, 8, 10]), rng.choice([2, 3, 4, 5])))
            else:
                buf.extend(rng.choice(tokens))
                if rng.random() < 0.25:
                    seq_ops.append(WriteOp(bytes(buf)))
                    buf.clear()
        if buf:
            seq_ops.append(WriteOp(bytes(buf)))
        add(f"random/seed-{idx:03d}", seq_ops, cols=cols, rows=rows, scrollback=sb)

    return cases


def format_case_line(case: DiffCase, include_styles: bool) -> str:
    id_hex = case.case_id.encode("utf-8").hex()
    style_flag = "1" if include_styles else "0"
    encoded_ops = []
    for op in case.ops:
        if isinstance(op, WriteOp):
            encoded_ops.append(f"W:{op.data.hex()}")
        else:
            encoded_ops.append(f"R:{op.columns}x{op.rows}")
    return f"CASE\t{id_hex}\t{case.columns}\t{case.rows}\t{case.scrollback}\t{style_flag}\t{' '.join(encoded_ops)}\n"


def parse_result_line(line: str) -> tuple[str, EngineResult]:
    parts = line.rstrip("\n").split("\t")
    kind = parts[0]
    case_id = bytes.fromhex(parts[1]).decode("utf-8")
    if kind == "OK":
        c_str, r_str = parts[2].split(",", 1)
        lines = tuple(bytes.fromhex(h).decode("utf-8", errors="replace") for h in parts[3].split(",") if h != "" or parts[3] == "")
        if parts[3] == "":
            lines = ("",)
        styles = tuple(bytes.fromhex(h).decode("utf-8") for h in parts[4].split(",") if h) if len(parts) > 4 and parts[4] else ()
        return case_id, EngineResult("ok", (int(c_str), int(r_str)), lines, styles)
    if kind == "ERR":
        err = bytes.fromhex(parts[2]).decode("utf-8", errors="replace")
        return case_id, EngineResult("err", error=err)
    raise ValueError(f"unexpected engine output: {line[:120]}")


def compare_results(vtfx: EngineResult, alacritty: EngineResult, include_styles: bool = False) -> str | None:
    """Returns a concise description of the first difference, or None if identical."""
    if vtfx.status != "ok" or alacritty.status != "ok":
        return f"status vtfx={vtfx.status}({vtfx.error}) alacritty={alacritty.status}({alacritty.error})"
    diffs: list[str] = []
    if vtfx.lines != alacritty.lines:
        for idx, (vline, aline) in enumerate(zip(vtfx.lines, alacritty.lines)):
            if vline != aline:
                diffs.append(f"row {idx}: vtfx={vline!r} vs alacritty={aline!r}")
                break
        if len(vtfx.lines) != len(alacritty.lines):
            diffs.append(f"row count: vtfx={len(vtfx.lines)} vs alacritty={len(alacritty.lines)}")
    if vtfx.cursor != alacritty.cursor:
        diffs.append(f"cursor: vtfx={vtfx.cursor} vs alacritty={alacritty.cursor}")
    if include_styles and vtfx.styles != alacritty.styles:
        for idx, (vstyle, astyle) in enumerate(zip(vtfx.styles, alacritty.styles)):
            if vstyle != astyle:
                diffs.append(f"style row {idx}: vtfx={vstyle[:80]} vs alacritty={astyle[:80]}")
                break
    return "; ".join(diffs) if diffs else None


def load_reasons(path: str | None = None) -> list[dict]:
    reasons_path = path or os.path.join(ROOT, "tools", "alacritty-diff", "reasons.toml")
    with open(reasons_path, "rb") as f:
        data = tomllib.load(f)
    return data.get("reason", [])


def reason_for(case_id: str, input_desc: str, difference: str, reasons: list[dict]) -> dict | None:
    """Returns the first reason whose configured patterns all match."""
    for reason in reasons:
        matched_any = False
        if "cases" in reason:
            matched_any = True
            if not re.search(reason["cases"], case_id):
                continue
        if "input" in reason:
            matched_any = True
            if not re.search(reason["input"], input_desc):
                continue
        if "difference" in reason:
            matched_any = True
            if not re.search(reason["difference"], difference):
                continue
        if matched_any:
            return reason
    return None


def build_vtfx_classpath() -> str:
    subprocess.run(
        [os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-core:testClasses", ":vtfx-core:differentialClasspath"],
        cwd=ROOT,
        check=True,
    )
    cp_file = os.path.join(ROOT, "vtfx-core", "build", "differential-classpath.txt")
    with open(cp_file, encoding="utf-8") as f:
        return f.read().strip()


def build_alacritty_runner() -> str:
    manifest = os.path.join(ROOT, "tools", "alacritty-diff", "Cargo.toml")
    target_dir = os.path.join(ROOT, "build", "alacritty-diff")
    subprocess.run(
        ["cargo", "build", "--release", "--locked", "--quiet", "--manifest-path", manifest, "--target-dir", target_dir],
        cwd=ROOT,
        check=True,
    )
    return os.path.join(target_dir, "release", "alacritty-diff")


def run_engine(cmd: list[str], cases: list[DiffCase], include_styles: bool, case_timeout: float = 1.0) -> dict[str, EngineResult]:
    """Streams cases through a persistent engine process, restarting it if a case hangs."""
    results: dict[str, EngineResult] = {}
    proc: subprocess.Popen[bytes] | None = None

    def ensure_proc() -> subprocess.Popen[bytes]:
        nonlocal proc
        if proc is None:
            proc = subprocess.Popen(cmd, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        elif proc.poll() is not None:
            close_proc()
            proc = subprocess.Popen(cmd, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        return proc

    def close_proc(kill: bool = False) -> None:
        nonlocal proc
        if proc is not None:
            if kill:
                proc.kill()
            if proc.stdin is not None:
                proc.stdin.close()
            if proc.stdout is not None:
                proc.stdout.close()
            proc.wait()
            proc = None

    buf = bytearray()
    try:
        for case in cases:
            p = ensure_proc()
            assert p.stdin is not None and p.stdout is not None
            try:
                p.stdin.write(format_case_line(case, include_styles).encode("utf-8"))
                p.stdin.flush()
            except BrokenPipeError:
                close_proc(kill=True)
                results[case.case_id] = EngineResult("err", error="engine exited unexpectedly")
                continue

            deadline = time.monotonic() + case_timeout
            line_bytes: bytes | None = None
            while True:
                nl = buf.find(b"\n")
                if nl != -1:
                    line_bytes = bytes(buf[:nl])
                    del buf[: nl + 1]
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                ready, _, _ = select.select([p.stdout], [], [], remaining)
                if not ready:
                    break
                chunk = os.read(p.stdout.fileno(), 65536)
                if not chunk:
                    break
                buf.extend(chunk)

            if line_bytes is None:
                close_proc(kill=True)
                buf.clear()
                results[case.case_id] = EngineResult("err", error=f"timeout ({case_timeout:.1f}s)")
            else:
                cid, res = parse_result_line(line_bytes.decode("utf-8"))
                results[cid] = res
    finally:
        close_proc()
    return results


def collect_all_cases(include_regex: str, extra_corpus_dirs: list[str] | None = None) -> list[DiffCase]:
    pattern = re.compile(include_regex)
    all_cases = (
        load_vtspec_cases(os.path.join(ROOT, "spec", "core"), "spec")
        + load_vtspec_cases(os.path.join(ROOT, "spec-proposals", "core"), "spec-proposals")
        + load_fuzz_cases(extra_corpus_dirs)
        + load_recording_cases()
        + generated_cases()
    )
    return [c for c in all_cases if pattern.search(c.case_id)]


def render_report(
    cases: list[DiffCase],
    differences: list[tuple[DiffCase, str, dict | None]],
    duration_sec: float,
    commit: str,
    include_styles: bool,
) -> str:
    by_category: dict[str, list[int]] = {}
    diff_ids = {c.case_id for c, _, _ in differences}
    for c in cases:
        counts = by_category.setdefault(c.category, [0, 0])
        counts[0] += 1
        if c.case_id in diff_ids:
            counts[1] += 1

    unexplained = [(c, diff) for c, diff, reason in differences if reason is None]
    matched = len(cases) - len(differences)

    lines = [
        "# Differential testing against alacritty_terminal",
        "",
        "Generated by `scripts/alacritty_diff.py`. Do not edit by hand. "
        "Known reasons for differences are in `tools/alacritty-diff/reasons.toml`. "
        "For reports, not a gate (section 15 of `docs/design/testing.md`).",
        "",
        "| Item | Value |",
        "|---|---|",
        f"| Date (UTC) | {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M')} |",
        f"| Commit | {commit} |",
        f"| Reference engine | `alacritty_terminal` {ALACRITTY_CRATE_VERSION} |",
        f"| Compared | Screen text and cursor{' and cell styles' if include_styles else ''} |",
        f"| Duration | {duration_sec:.1f} seconds |",
        f"| Result | {len(cases)} cases: {matched} identical, {len(differences)} differing ({len(unexplained)} without a reason) |",
        "",
        "## Breakdown by input source",
        "",
        "| Source | Cases | Identical | Differing |",
        "|---|---:|---:|---:|",
    ]
    for category, (total, diff_count) in sorted(by_category.items()):
        lines.append(f"| `{category}` | {total} | {total - diff_count} | {diff_count} |")
    lines.append("")

    if unexplained:
        lines += [
            "## Differences without a reason",
            "",
            "| Case | Input | Difference |",
            "|---|---|---|",
        ]
        for case, diff in unexplained:
            inp = describe_ops(case, limit=140).replace("|", "\\|")
            d_esc = diff.replace("|", "\\|")[:220]
            lines.append(f"| `{case.case_id}` | `{inp}` | `{d_esc}` |")
        lines.append("")

    by_kind: dict[str, dict[str, tuple[dict, list[tuple[DiffCase, str]]]]] = {}
    for case, diff, reason in differences:
        if reason is not None:
            kind_groups = by_kind.setdefault(reason["kind"], {})
            kind_groups.setdefault(reason["summary"], (reason, []))[1].append((case, diff))

    kind_order = [
        "Bug in alacritty_terminal",
        "Open question",
        "Intentional difference",
        "Unsupported by alacritty_terminal",
    ]
    ordered_kinds = [k for k in kind_order if k in by_kind] + sorted(k for k in by_kind if k not in kind_order)
    for kind in ordered_kinds:
        lines += [f"## {kind}", ""]
        for summary, (reason, entries) in sorted(by_kind[kind].items()):
            lines += [f"### {summary}", "", reason.get("detail", ""), ""]
            for case, diff in entries:
                d_short = diff[:180] + ("..." if len(diff) > 180 else "")
                lines.append(f"- `{case.case_id}` — `{d_short}`")
            lines.append("")

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", default=os.path.join(ROOT, "build", "reports", "alacritty-diff.md"))
    parser.add_argument("--include", default=".*")
    parser.add_argument("--styles", action="store_true", help="Also compare cell styles.")
    parser.add_argument("--extra-corpus", action="append", default=[], help="Additional TerminalFuzzer corpus directory.")
    args = parser.parse_args()

    started = time.time()
    cases = collect_all_cases(args.include, args.extra_corpus)

    cp = build_vtfx_classpath()
    alacritty_bin = build_alacritty_runner()

    vtfx_results = run_engine(["java", "-cp", cp, "com.google.vtfx.core.diff.DifferentialHost"], cases, args.styles)
    alacritty_results = run_engine([alacritty_bin], cases, args.styles)

    reasons = load_reasons()
    differences: list[tuple[DiffCase, str, dict | None]] = []
    for case in cases:
        vres = vtfx_results[case.case_id]
        ares = alacritty_results[case.case_id]
        diff = compare_results(vres, ares, include_styles=args.styles)
        if diff is not None:
            reason = reason_for(case.case_id, describe_ops(case, limit=100_000), diff, reasons)
            differences.append((case, diff, reason))

    duration = time.time() - started
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    report_md = render_report(cases, differences, duration, commit, args.styles)

    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write(report_md)

    unexplained = sum(1 for _, _, r in differences if r is None)
    print(f"{len(cases)} cases, {len(differences)} differing ({unexplained} unexplained) in {duration:.1f}s -> {args.report}")


if __name__ == "__main__":
    main()
