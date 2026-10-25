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

"""Runs the performance benchmarks and writes build/reports/performance.md.

- Throughput of vtfx-core (./gradlew :vtfx-core:benchmark) and, for comparison, of xterm.js
  (@xterm/headless on Node) with the same workloads, on this machine.
- Memory of the scrollback, by estimate, from the same benchmark.
- Frame times of the sample app on the emulator or a device (RenderBenchmark), if one is connected: updates every
  16 ms, and a flood of output without a break (docs/decisions/0089).

The targets are in docs/decisions/0089; the report marks each result against them. Not part of the
check: timing depends on the machine.

Usage: benchmark.py [--serial SERIAL] [--node PATH_TO_NODE_BIN] [--skip-emulator]
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import tempfile
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
XTERM_VERSION = "6.0.0"

# Targets of docs/decisions/0089.
THROUGHPUT_TARGETS = {"ascii": 120.0, "sgr": 40.0, "cjk": 70.0, "recordings": 30.0}  # MB/s
THROUGHPUT_RATIO_TARGET = 2.0  # times xterm.js on the same machine
SCROLLBACK_TYPICAL_TARGET = 5.0  # MiB, for the ascii, sgr, and cjk workloads
SCROLLBACK_WORST_TARGET = 16.0  # MiB
DRAW_TARGETS = {"full": 25.0, "scroll": 10.0}  # ms, p50 of the UI thread's drawing


def parse_table(text):
    rows = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0] != "workload":
            rows[parts[0]] = parts[1:]
    return rows


def vtfx_throughput():
    out = subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-core:benchmark"], cwd=ROOT,
                         check=True, capture_output=True, text=True).stdout
    return parse_table(out)


def xterm_throughput(node):
    work = tempfile.mkdtemp(prefix="vtfx-xterm-")
    npm = os.path.join(os.path.dirname(node), "npm")
    env = dict(os.environ, PATH=os.path.dirname(node) + os.pathsep + os.environ["PATH"])
    subprocess.run([npm, "install", "--silent", "--no-audit", "--no-fund", f"@xterm/headless@{XTERM_VERSION}"], cwd=work,
                   check=True, env=env)
    out = subprocess.run([node, os.path.join(ROOT, "tools", "benchmark", "xterm-headless.mjs"),
                          os.path.join(ROOT, "recordings"), "3"], cwd=work, check=True, capture_output=True, text=True,
                         env=env).stdout
    return parse_table(out)


def render(serial):
    def adb(*args):
        return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True).stdout

    subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-sample:assembleBenchmark"], cwd=ROOT,
                   check=True)
    adb("install", "-r", os.path.join(ROOT, "vtfx-sample", "build", "outputs", "apk", "benchmark", "vtfx-sample-benchmark.apk"))
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-S", "-W", "-n", "com.google.vtfx.sample/.MainActivity", "--ez", "benchmark", "true")
    deadline = time.time() + 300
    results = {}
    while time.time() < deadline and len(results) < 3:
        time.sleep(5)
        for line in adb("logcat", "-d", "-s", "VtfxRenderBenchmark:I", "-v", "raw").splitlines():
            if line.startswith("{"):
                data = json.loads(line)
                results[data["scenario"]] = data
    release = adb("shell", "getprop", "ro.build.version.release").strip()
    return results, release


def mark(ok):
    return "Met" if ok else "Not met"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5580"))
    parser.add_argument("--node", default=None, help="node binary; the xterm.js comparison is skipped without it")
    parser.add_argument("--skip-emulator", action="store_true")
    args = parser.parse_args()

    vtfx = vtfx_throughput()
    xterm = xterm_throughput(args.node) if args.node else {}
    renders, release = ({}, None) if args.skip_emulator else render(args.serial)

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    cpu = re.search(r"model name\s*:\s*(.*)", open("/proc/cpuinfo").read())
    lines = [
        "# Performance measurements",
        "",
        "Generated by `scripts/benchmark.py`. The targets are in `docs/decisions/0089-performance.md`.",
        "",
        f"- Date (UTC): {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M')}",
        f"- Commit: {commit}",
        f"- CPU: {cpu.group(1) if cpu else 'unknown'}",
        "",
        "## Throughput (JVM, 120 × 40, writes of 4 KiB)",
        "",
        "| Load | vtfx (MB/s) | xterm.js headless (MB/s) | Ratio | Target | Result |",
        "|---|---|---|---|---|---|",
    ]
    for name, target in THROUGHPUT_TARGETS.items():
        rate = float(vtfx[name][0])
        other = float(xterm[name][0]) if name in xterm else None
        ratio = rate / other if other else None
        ok = rate >= target and (ratio is None or ratio >= THROUGHPUT_RATIO_TARGET)
        lines.append(f"| {name} | {rate:.1f} | {other if other is not None else '-'} | "
                     f"{f'{ratio:.1f}' if ratio else '-'} | ≥ {target:.0f} MB/s, at least {THROUGHPUT_RATIO_TARGET:.0f} times xterm.js | {mark(ok)} |")
    lines += ["", "## Scrollback memory (the default 10,000 lines, 120 columns, estimated)", "", "| Load | MiB | Target | Result |", "|---|---|---|---|"]
    for name in ["ascii", "sgr", "cjk"]:
        mib = float(vtfx[name][1])
        lines.append(f"| {name} | {mib:.1f} | ≤ {SCROLLBACK_TYPICAL_TARGET:.0f} | {mark(mib <= SCROLLBACK_TYPICAL_TARGET)} |")
    worst = float(vtfx["worst"][1])
    lines.append(f"| worst (every cell a different color) | {worst:.1f} | ≤ {SCROLLBACK_WORST_TARGET:.0f} | {mark(worst <= SCROLLBACK_WORST_TARGET)} |")
    lines += ["", f"## Rendering (emulator, Android {release or '-'}, updates equivalent to 120 × 50 every 16 ms)", ""]
    if renders:
        lines += ["| Scene | UI thread drawing p50 (ms) | Whole frame p50 / p90 (ms) | Target (drawing p50) | Result |", "|---|---|---|---|---|"]
        for scenario, target in DRAW_TARGETS.items():
            data = renders.get(scenario)
            if not data:
                continue
            draw = float(data["drawP50"])
            lines.append(f"| {scenario} | {draw:.1f} | {data['p50']} / {data['p90']} | ≤ {target:.0f} | {mark(draw <= target)} |")
        lines += ["", "The time of the whole frame includes rasterization on the emulator's software GPU (swiftshader), so it is not a guide for real devices."]
    else:
        lines.append("Not measured on the emulator.")
    lines += ["", "## Output without a break (flood, docs/decisions/0089)", ""]
    flood = renders.get("flood")
    if flood:
        lines += [
            "Colored log lines fed from another thread in writes of 16 KiB for 5 s. Every frame shows new rows, so a frame is",
            "due at every vsync. Janky: the share of frames longer than the refresh interval. Without a display: the same",
            "output into a session that no display shows, on the same device.",
            "No target yet (docs/review-queue.md).",
            "",
            "| Refresh (Hz) | Frames per second | Janky | Whole frame p50 / p90 / p99 (ms) | UI thread drawing p50 / p90 (ms) | "
            "Throughput (MB/s) | Without a display (MB/s) |",
            "|---|---|---|---|---|---|---|",
            f"| {flood['refreshRate']} | {flood['fps']} | {flood['janky']} % | {flood['p50']} / {flood['p90']} / "
            f"{flood['p99']} | {flood['drawP50']} / {flood['drawP90']} | {flood['mbps']} | {flood['headlessMbps']} |",
        ]
    else:
        lines.append("Not measured.")
    path = os.path.join(ROOT, "build", "reports", "performance.md")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"-> {path}")


if __name__ == "__main__":
    main()
