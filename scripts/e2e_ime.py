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

"""Runs the IME end-to-end tests on an emulator and writes a report.

Types the scenarios of tools/ime-probe/scripts/run_scenarios.py on Gboard into the sample app
(vtfx-sample), collects the bytes the app sent to its pseudo host, and compares them with the
human-owned expectations in spec/ime/gboard.toml. The result is for reporting, not a gate
(docs/design/testing.md section 8): a real IME is not deterministic.

Before the scenarios, Gboard is set up with the English (US), Japanese (QWERTY), and Korean (2-set)
keyboards through its settings screen, since the emulator boots from a snapshot without them.

Usage: e2e_ime.py [--serial SERIAL] [--report PATH] [--skip-build] [CASE_GLOB ...]

Start the emulator with scripts/emulator.sh start first.
"""

import argparse
import datetime
import fnmatch
import os
import re
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ADB_PATH = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
APP_ID = "com.google.vtfx.sample"
ACTIVITY = APP_ID + "/.MainActivity"
HOST_INPUT_TAG = "VtfxSampleHost"
GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
GBOARD_SETTINGS = "com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.latin.preference.SettingsActivity"
DEFAULT_SERIAL = "emulator-5580"

# The keyboards to add: (search term, language name in the list, layout to select, subtype hash).
KEYBOARDS = [
    ("Japanese", "日本語", "QWERTY", "-1318396357"),
    ("Korean", "한국어", "두벌식", "-1906255757"),
]
ENGLISH_SUBTYPE = "1594443099"


def adb(serial, *args, check=True):
    return subprocess.run([ADB_PATH, "-s", serial, *args], capture_output=True, text=True, check=check).stdout


class Ui:
    """Finds views by text with uiautomator and taps them."""

    def __init__(self, serial):
        self.serial = serial

    def nodes(self):
        for _ in range(5):
            out = adb(self.serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False)
            start, end = out.find("<?xml"), out.rfind(">")
            if start >= 0:
                try:
                    root = ET.fromstring(out[start:end + 1])
                    return [(n.get("text") or n.get("content-desc") or "", n) for n in root.iter("node")]
                except ET.ParseError:
                    pass
            time.sleep(1)
        raise RuntimeError("uiautomator dump failed")

    def tap_node(self, node):
        x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds")))
        adb(self.serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(self, text, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for label, node in self.nodes():
                if label == text:
                    self.tap_node(node)
                    time.sleep(1.5)
                    return
            time.sleep(1)
        raise RuntimeError(f"no view with text {text!r}")


def enabled_subtypes(serial):
    value = adb(serial, "shell", "settings", "get", "secure", "enabled_input_methods").strip()
    for entry in value.split(":"):
        parts = entry.split(";")
        if parts[0] == GBOARD:
            return set(parts[1:])
    return set()


def set_up_gboard(serial):
    """Enables Gboard with the English, Japanese, and Korean keyboards, if needed."""
    adb(serial, "shell", "ime", "enable", GBOARD)
    adb(serial, "shell", "ime", "set", GBOARD)
    # Show the software keyboard even if a hardware keyboard is reported.
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    ui = Ui(serial)
    for search, name, layout, subtype in KEYBOARDS:
        if subtype in enabled_subtypes(serial):
            continue
        print(f"Adding the {search} keyboard to Gboard")
        adb(serial, "shell", "am", "start", "-W", "-n", GBOARD_SETTINGS)
        time.sleep(2)
        ui.tap("Languages")
        ui.tap("Add keyboard")
        ui.tap("Search language")
        adb(serial, "shell", "input", "text", search)
        time.sleep(2)
        ui.tap(name)
        # Select exactly the wanted layout.
        for label, node in ui.nodes():
            is_layout = node.get("class") == "android.widget.LinearLayout" and node.get("clickable") == "true"
            if is_layout and label and (node.get("selected") == "true") != (label == layout):
                ui.tap_node(node)
                time.sleep(1)
        ui.tap("Done")
        adb(serial, "shell", "am", "force-stop", GBOARD_SETTINGS.split("/")[0])
        if subtype not in enabled_subtypes(serial):
            raise RuntimeError(f"Gboard did not enable the {search} keyboard")
    missing = {ENGLISH_SUBTYPE, *(k[3] for k in KEYBOARDS)} - enabled_subtypes(serial)
    if missing:
        raise RuntimeError(f"Gboard keyboards missing: {missing}")


def load_scenarios():
    sys.path.insert(0, os.path.join(ROOT, "tools", "ime-probe", "scripts"))
    import run_scenarios  # noqa: E402  The scenario definitions are shared with the recorder.
    return run_scenarios


def collect_bytes(serial):
    log = adb(serial, "logcat", "-d", "-s", f"{HOST_INPUT_TAG}:I", "-v", "raw")
    data = bytearray()
    for line in log.splitlines():
        match = re.match(r'\{"hex":"([0-9a-f]*)"\}', line.strip())
        if match:
            data += bytes.fromhex(match.group(1))
    return bytes(data)


def run_case(serial, scenarios, lang, steps):
    adb(serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
    time.sleep(2)
    # The pseudo host prints a prompt; typing starts after it.
    scenarios.tap((540, 900))
    time.sleep(2)
    switched = scenarios.ensure_language(lang)
    adb(serial, "logcat", "-c")
    keys = scenarios.LAYOUTS[lang]
    for step in steps:
        if isinstance(step, float):
            time.sleep(step)
        elif isinstance(step, tuple):
            for code in step:
                adb(serial, "shell", "input", "keyboard", "keyevent", code)
                time.sleep(0.4)
        else:
            for key in step:
                scenarios.tap(keys[key])
                time.sleep(0.4)
    time.sleep(2)
    return collect_bytes(serial), switched


def show(data):
    text = data.decode("utf-8", errors="backslashreplace")
    return (text.replace("\\", "\\\\").replace("\x1b", "\\e").replace("\r", "\\r").replace("\n", "\\n")
            .replace("\x7f", "\\x7f"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", DEFAULT_SERIAL))
    parser.add_argument("--report", default=os.path.join(ROOT, "build", "reports", "e2e-ime.md"))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("patterns", nargs="*", default=["*"])
    args = parser.parse_args()
    # run_scenarios.py calls adb without a serial; adb reads ANDROID_SERIAL.
    os.environ["ANDROID_SERIAL"] = args.serial

    if not args.skip_build:
        subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-sample:assembleDebug"],
                       cwd=ROOT, check=True)
    apk = os.path.join(ROOT, "vtfx-sample", "build", "outputs", "apk", "debug", "vtfx-sample-debug.apk")
    adb(args.serial, "install", "-r", apk)
    set_up_gboard(args.serial)
    scenarios = load_scenarios()

    with open(os.path.join(ROOT, "spec", "ime", "gboard.toml"), "rb") as f:
        cases = tomllib.load(f)["case"]
    results = []
    for case in cases:
        name = case["name"]
        if not any(fnmatch.fnmatch(name, p) for p in args.patterns):
            continue
        if name not in scenarios.SCENARIOS:
            results.append((name, "skipped", "recorded on a real device; not reproducible on the emulator", "", ""))
            continue
        lang, steps, _ = scenarios.SCENARIOS[name]
        expected = case["expected"].encode("utf-8")
        actual, switched = run_case(args.serial, scenarios, lang, steps)
        status, note = "pass", ""
        if actual != expected:
            # Taps right after switching the keyboard are sometimes lost. Retry once, and report a
            # case that passes only on the retry as flaky, with the first result.
            first = actual
            actual, switched = run_case(args.serial, scenarios, lang, steps)
            if actual == expected:
                status, note = "flaky", f"first result: `{show(first)}`"
            else:
                status = "fail"
                note = "" if switched else f"could not switch Gboard to {lang}"
        results.append((name, status, note, show(expected), show(actual)))
        print(f"{name}: {status}")

    gboard = re.search(r"versionName=(\S+)", adb(args.serial, "shell", "dumpsys", "package",
                                                 "com.google.android.inputmethod.latin"))
    release = adb(args.serial, "shell", "getprop", "ro.build.version.release").strip()
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    counts = {s: sum(1 for r in results if r[1] == s) for s in ("pass", "flaky", "fail", "skipped")}
    lines = [
        "# IME e2e results",
        "",
        "Generated by `scripts/e2e_ime.py`. Do not edit by hand. The expected values are `spec/ime/gboard.toml`, and the steps are "
        "the scenarios in `tools/ime-probe/scripts/run_scenarios.py`. For reports, not a gate (section 8 of testing.md).",
        "",
        "| Item | Value |",
        "|---|---|",
        f"| Date (UTC) | {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M')} |",
        f"| Commit | {commit} |",
        f"| Android | {release} |",
        f"| Gboard | {gboard.group(1) if gboard else 'unknown'} |",
        f"| Result | {counts['pass']} passed, {counts['flaky']} passed on retry, {counts['fail']} failed, {counts['skipped']} skipped |",
        "",
        "| Case | Result | Expected | Actual | Notes |",
        "|---|---|---|---|---|",
    ]
    for name, status, note, expected, actual in results:
        cell = lambda s: f"`{s}`" if s else ""  # noqa: E731
        lines.append(f"| {name} | {status} | {cell(expected)} | {cell(actual)} | {note} |")
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{counts} -> {args.report}")
    return 1 if counts["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
