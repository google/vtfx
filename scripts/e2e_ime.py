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

"""Runs the IME end-to-end tests on an emulator or a device and writes a report.

Types the scenarios of tools/ime-probe/scripts/run_scenarios.py on Gboard into the sample app
(vtfx-sample), collects the bytes the app sent to its pseudo host, and compares them with the
human-owned expectations in spec/ime/gboard.toml. The result is for reporting, not a gate
(docs/design/testing.md section 8): a real IME is not deterministic.

Before the scenarios, Gboard is set up with the English (US), Japanese (QWERTY), and Korean (2-set)
keyboards through its settings screen, since the emulator boots from a snapshot without them.

On a screen other than the Pixel 7 AVD's (a device reached over adb), the keys are found from the
keyboard's touchable region (layouts_for() in run_scenarios.py), and the keyboard's language is
told by what reaches the host after typing "a" and Enter, since Gboard does not report it in the
settings on every Android version.

Usage: e2e_ime.py [--serial SERIAL] [--report PATH] [--skip-build] [CASE_GLOB ...]

Start the emulator with scripts/emulator.sh start first, or pass the serial of a device.
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
ENGLISH_NAME = "English (US)"
# The screen of the AVD that the coordinates of run_scenarios.py were measured on.
AVD_SCREEN = "1080x2400"
# Where run_case taps to focus the terminal, and how it switches the keyboard's language; on a
# device, adapt_to_device() replaces them.
terminal_tap = (540, 900)
switch_language = None


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


def gboard_languages(serial, ui):
    """The text of Gboard's settings screen, whose summary of "Languages" names the keyboards
    (e.g. "English (US) (QWERTY), 日本語（QWERTY）")."""
    adb(serial, "shell", "am", "start", "-S", "-W", "-n", GBOARD_SETTINGS)
    for _ in range(10):
        labels = [label for label, _ in ui.nodes()]
        if "Languages" in labels:
            break
        time.sleep(1)
    adb(serial, "shell", "am", "force-stop", GBOARD_SETTINGS.split("/")[0])
    return "\n".join(labels)


def set_up_gboard(serial):
    """Enables Gboard with the English, Japanese, and Korean keyboards, if needed."""
    adb(serial, "shell", "ime", "enable", GBOARD)
    adb(serial, "shell", "ime", "set", GBOARD)
    # Show the software keyboard even if a hardware keyboard is reported.
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    ui = Ui(serial)
    # Some versions of Android or Gboard list no subtypes in the settings; then Gboard's settings
    # screen says which keyboards it has.
    by_settings = bool(enabled_subtypes(serial))

    def enabled(name, subtype):
        return subtype in enabled_subtypes(serial) if by_settings else name in gboard_languages(serial, ui)

    for search, name, layout, subtype in KEYBOARDS:
        if enabled(name, subtype):
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
        if not enabled(name, subtype):
            raise RuntimeError(f"Gboard did not enable the {search} keyboard")
    missing = [name for name, subtype in [(ENGLISH_NAME, ENGLISH_SUBTYPE), *((k[1], k[3]) for k in KEYBOARDS)]
               if not enabled(name, subtype)]
    if missing:
        raise RuntimeError(f"Gboard keyboards missing: {missing}")


def keyboard_region(serial):
    """The touchable region of the software keyboard, (left, top, right, bottom), once it shows."""
    for _ in range(10):
        windows = adb(serial, "shell", "dumpsys", "window", "windows")
        match = re.search(r"Window\{\S+ u0 InputMethod\}:.*?touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)\)",
                          windows, re.S)
        if match and int(match.group(4)) > int(match.group(2)):
            return tuple(int(v) for v in match.groups())
        time.sleep(1)
    raise RuntimeError("the software keyboard did not show")


def adapt_to_device(serial, scenarios):
    """On a screen other than the AVD's, finds Gboard's keys from its touchable region, and
    switches its language by typing, since the subtype in the settings may not follow it."""
    global terminal_tap, switch_language
    width, height = (int(v) for v in re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size")).groups())
    if f"{width}x{height}" == AVD_SCREEN:
        return
    terminal_tap = (width // 2, height // 3)
    adb(serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
    time.sleep(2)
    scenarios.tap(terminal_tap)
    region = keyboard_region(serial)
    scenarios.LAYOUTS = scenarios.layouts_for(*region)
    print(f"Keyboard region {region} on a {width}x{height} screen")

    def language():
        """Types "a" and Enter, and tells the language by what reaches the host."""
        adb(serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
        time.sleep(2)
        scenarios.tap(terminal_tap)
        time.sleep(1.5)
        adb(serial, "logcat", "-c")
        # "a" is at the same place on the English and the Japanese keyboards, and Enter too.
        keys = scenarios.LAYOUTS["ja"]
        scenarios.tap((round(keys["a"][0] * 1.5), keys["a"][1]))
        time.sleep(0.6)
        scenarios.tap(keys["ENTER"])
        time.sleep(1.5)
        sent = collect_bytes(serial).decode("utf-8", errors="replace")
        return {"a": "en", "あ": "ja", "ㅁ": "ko"}.get(sent[:1])

    def switch(lang):
        for _ in range(4):
            current = language()
            if current == lang:
                break
            scenarios.tap(scenarios.LAYOUTS[current or "en"]["GLOBE"])
            time.sleep(1.5)
        else:
            return False
        # Start again, so that the probe leaves nothing in the input history.
        adb(serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
        time.sleep(2)
        scenarios.tap(terminal_tap)
        time.sleep(2)
        return True

    switch_language = switch


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
    scenarios.tap(terminal_tap)
    time.sleep(2)
    switched = (switch_language or scenarios.ensure_language)(lang)
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
    adapt_to_device(args.serial, scenarios)

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
    model = adb(args.serial, "shell", "getprop", "ro.product.model").strip()
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
        f"| Device | {model} |",
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
