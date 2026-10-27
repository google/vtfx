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
- Throughput of the same workloads as on the JVM in the sample app on that device (ThroughputBenchmark), after
  installing, which leaves the code to ART's JIT, and after compiling the app ahead of time with the speed filter.

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
import sys
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
# Targets of the flood scene on the reference device, a Pixel XL (docs/decisions/0089).
FLOOD_FPS_TARGET = 0.8  # frames per second, as a share of the refresh rate
FLOOD_JANKY_TARGET = 35.0  # %
FLOOD_THROUGHPUT_TARGET = 0.75  # the throughput with the display, as a share of that without one
RENDER_SCENARIOS = ["full", "scroll", "flood"]
PACKAGE = "com.google.vtfx.sample"
# Where ./gradlew :vtfx-core:benchmarkWorkloads writes the workloads, and where the sample app reads them.
WORKLOADS = os.path.join(ROOT, "vtfx-core", "build", "benchmark-workloads")
DEVICE_WORKLOADS = f"/sdcard/Android/data/{PACKAGE}/files/workloads"
# How the code runs in each measurement of the throughput on the device: after `adb install`, ART interprets the
# app and compiles hot code with its JIT; `cmd package compile -m speed` compiles the whole app ahead of time.
COMPILATIONS = {"jit": "After install (JIT)", "speed": "Compiled with speed (AOT)"}
# Targets of the throughput on the reference device, after install (docs/decisions/0089).
DEVICE_THROUGHPUT_REFERENCE = "Pixel 9"
DEVICE_THROUGHPUT_TARGETS = {"ascii": 90.0, "sgr": 65.0, "cjk": 70.0, "recordings": 85.0}  # MB/s


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


def gradle(*tasks):
    subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", *tasks], cwd=ROOT, check=True)


def device(serial):
    """Installs the benchmark build on the device and runs the rendering scenes and the throughput benchmark."""
    def adb(*args):
        return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True).stdout

    gradle(":vtfx-sample:assembleBenchmark", ":vtfx-core:benchmarkWorkloads")
    adb("install", "-r", os.path.join(ROOT, "vtfx-sample", "build", "outputs", "apk", "benchmark", "vtfx-sample-benchmark.apk"))
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    # Behind the lock screen the activity draws no frames. This dismisses a lock screen without a
    # credential; a device with one has to be unlocked by hand.
    adb("shell", "wm", "dismiss-keyguard")
    renders = render(adb)
    throughputs = device_throughput(adb)
    release = adb("shell", "getprop", "ro.build.version.release").strip()
    emulator = adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
    name = "emulator" if emulator else adb("shell", "getprop", "ro.product.model").strip()
    return renders, throughputs, f"{name}, Android {release}", None if emulator else name, emulator


def render(adb):
    adb("logcat", "-c")
    adb("shell", "am", "start", "-S", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "benchmark", "true")
    deadline = time.time() + 300
    results = {}
    while time.time() < deadline and len(results) < len(RENDER_SCENARIOS):
        time.sleep(5)
        for line in adb("logcat", "-d", "-s", "VtfxRenderBenchmark:I", "-v", "raw").splitlines():
            if line.startswith("{"):
                data = json.loads(line)
                results[data["scenario"]] = data
    check_renders(results)
    return results


def device_throughput(adb):
    """Measures the throughput on the device after install and after compiling the app with the speed filter, and
    leaves the app compiled as after install."""
    # The app makes the directory of the workloads first, as a run that measures nothing: files that adb pushes into
    # a directory that adb made, which belongs to the shell, are not visible to the app.
    run_throughput(adb, "none")
    adb("push", os.path.join(WORKLOADS, "."), DEVICE_WORKLOADS + "/")
    results = {"jit": run_throughput(adb)}
    adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE)
    results["speed"] = run_throughput(adb)
    adb("shell", "cmd", "package", "compile", "--reset", PACKAGE)
    for compilation, rates in results.items():
        check_throughput(compilation, rates)
    return results


def run_throughput(adb, workload=None):
    """Runs ThroughputBenchmark, of only the given workload if any, and returns MB/s by workload."""
    adb("logcat", "-c")
    adb("shell", "am", "start", "-S", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "throughput", "true",
        *(["--es", "workload", workload] if workload else []))
    deadline = time.time() + 120
    while time.time() < deadline:
        time.sleep(2)
        rates = parse_throughput(adb("logcat", "-d", "-s", "VtfxThroughput:I", "-v", "raw"))
        if rates is not None:
            return rates
    sys.exit("ThroughputBenchmark did not finish")


def parse_throughput(log):
    """Returns MB/s by workload from the log of ThroughputBenchmark, or None if it has not finished."""
    rates = {}
    for line in log.splitlines():
        if not line.startswith("{"):
            continue
        data = json.loads(line)
        if data.get("done"):
            return rates
        rates[data["workload"]] = float(data["mbps"])
    return None


def check_throughput(compilation, rates):
    """Fails unless ThroughputBenchmark measured every workload: without the workloads on the device, it measures
    none."""
    missing = [name for name in THROUGHPUT_TARGETS if name not in rates]
    if missing:
        sys.exit(f"ThroughputBenchmark ({compilation}) reported no result for {', '.join(missing)}")


def check_renders(results):
    """Fails unless RenderBenchmark reported every scene with frames, so that no result is marked
    against a target without a measurement."""
    missing = [scenario for scenario in RENDER_SCENARIOS if scenario not in results]
    if missing:
        sys.exit(f"RenderBenchmark reported no result for {', '.join(missing)}")
    empty = [scenario for scenario in RENDER_SCENARIOS if results[scenario]["frames"] == 0]
    if empty:
        sys.exit(f"RenderBenchmark drew no frames in {', '.join(empty)}: is the screen on and unlocked?")


def device_throughput_result(model, workload, rate):
    """Returns the target and the result of the throughput of a workload after install on a device of the model,
    which only the reference device is marked against."""
    target = DEVICE_THROUGHPUT_TARGETS[workload]
    result = mark(rate >= target) if model == DEVICE_THROUGHPUT_REFERENCE else "For reference"
    return f"≥ {target:.0f} MB/s on a {DEVICE_THROUGHPUT_REFERENCE}", result


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
    renders, throughputs, device_name, model, emulator = (
        ({}, {}, None, None, False) if args.skip_emulator else device(args.serial))

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
    lines += ["", f"## Rendering ({device_name or '-'}, updates equivalent to 120 × 50 every 16 ms)", ""]
    if renders:
        lines += ["| Scene | UI thread drawing p50 (ms) | Whole frame p50 / p90 (ms) | Target (drawing p50) | Result |", "|---|---|---|---|---|"]
        for scenario, target in DRAW_TARGETS.items():
            data = renders.get(scenario)
            if not data:
                continue
            draw = float(data["drawP50"])
            lines.append(f"| {scenario} | {draw:.1f} | {data['p50']} / {data['p90']} | ≤ {target:.0f} | {mark(draw <= target)} |")
        if emulator:
            lines += ["", "The time of the whole frame includes rasterization on the emulator's software GPU (swiftshader), so it is not a guide for real devices."]
    else:
        lines.append("Not measured.")
    lines += ["", "## Output without a break (flood, docs/decisions/0089)", ""]
    flood = renders.get("flood")
    if flood:
        lines += [
            "Colored log lines fed from another thread in writes of 16 KiB for 5 s. Every frame shows new rows, so a frame is",
            "due at every vsync. Janky: the share of frames longer than the refresh interval. Without a display: the same",
            "output into a session that no display shows, on the same device.",
            "The targets are for the reference device, a Pixel XL; on other devices, the results are for reference.",
            "",
            "| Refresh (Hz) | Frames per second | Janky | Whole frame p50 / p90 / p99 (ms) | UI thread drawing p50 / p90 (ms) | "
            "Throughput (MB/s) | Without a display (MB/s) |",
            "|---|---|---|---|---|---|---|",
            f"| {flood['refreshRate']} | {flood['fps']} | {flood['janky']} % | {flood['p50']} / {flood['p90']} / "
            f"{flood['p99']} | {flood['drawP50']} / {flood['drawP90']} | {flood['mbps']} | {flood['headlessMbps']} |",
            "",
            "| Value | Measured | Target | Result |",
            "|---|---|---|---|",
        ]
        fps = float(flood["fps"]) / float(flood["refreshRate"])
        janky = float(flood["janky"])
        throughput = float(flood["mbps"]) / float(flood["headlessMbps"])
        lines += [
            f"| Frames per second / refresh rate | {fps:.0%} | ≥ {FLOOD_FPS_TARGET:.0%} | {mark(fps >= FLOOD_FPS_TARGET)} |",
            f"| Janky | {janky:.1f} % | ≤ {FLOOD_JANKY_TARGET:.0f} % | {mark(janky <= FLOOD_JANKY_TARGET)} |",
            f"| Throughput / without a display | {throughput:.0%} | ≥ {FLOOD_THROUGHPUT_TARGET:.0%} | "
            f"{mark(throughput >= FLOOD_THROUGHPUT_TARGET)} |",
        ]
    else:
        lines.append("Not measured.")
    lines += ["", f"## Throughput on the device ({device_name or '-'}, 120 × 40, writes of 4 KiB)", ""]
    if throughputs:
        lines += [
            "The workloads of the JVM's throughput, fed to a session in the sample app's benchmark build. The targets",
            f"are for the throughput after install on the reference device, a {DEVICE_THROUGHPUT_REFERENCE}; on other "
            "devices, the results are for reference.",
            "",
            "| Load | " + " | ".join(f"{label} (MB/s)" for label in COMPILATIONS.values()) +
            " | JVM on this machine (MB/s) | Target | Result |",
            "|---|" + "---|" * len(COMPILATIONS) + "---|---|---|",
        ]
        for name in THROUGHPUT_TARGETS:
            rates = " | ".join(f"{throughputs[compilation][name]:.1f}" for compilation in COMPILATIONS)
            target, result = device_throughput_result(model, name, throughputs["jit"][name])
            lines.append(f"| {name} | {rates} | {float(vtfx[name][0]):.1f} | {target} | {result} |")
        if emulator:
            lines += ["", "The emulator runs on this machine's CPU, so it does not show the throughput of devices."]
    else:
        lines.append("Not measured.")
    path = os.path.join(ROOT, "build", "reports", "performance.md")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"-> {path}")


if __name__ == "__main__":
    main()
