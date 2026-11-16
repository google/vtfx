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

On anything but an emulator with the Pixel 7 AVD's screen (a device reached over adb, even one
with the same screen size), the keys are found from the keyboard's touchable region, without the
navigation bar that the IME draws under the keys with gesture navigation (layouts_for() in
run_scenarios.py), and the keyboard's language is
told by what reaches the host after typing "a" and Enter, since Gboard does not report it in the
settings on every Android version.

Usage: e2e_ime.py [--serial SERIAL] [--report PATH] [--skip-build] [CASE_GLOB ...]

Start the emulator with scripts/emulator.sh start first, or pass the serial of a device.
"""

import argparse
import datetime
import fnmatch
import itertools
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

# The keyboards to add: (search term, names of the language in the list, layout to select,
# subtype hash). Older Gboard versions list the languages by their English names.
KEYBOARDS = [
    ("Japanese", ("日本語", "Japanese"), "QWERTY", "-1318396357"),
    ("Korean", ("한국어", "Korean"), "두벌식", "-1906255757"),
]
ENGLISH_SUBTYPE = "1594443099"
ENGLISH_NAMES = ("English (US)",)
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

    def tap(self, *texts, timeout=10, field=False):
        """Taps the first view whose text is one of `texts`, in any case (older Gboard versions
        write some labels in capitals, or with other words). Text fields count only if `field`,
        so that a list item is not mistaken for a search field that holds the same text."""
        wanted = {text.casefold() for text in texts}
        deadline = time.time() + timeout
        while time.time() < deadline:
            for label, node in self.nodes():
                is_field = node.get("class", "").endswith(("EditText", "AutoCompleteTextView", "SearchAutoComplete"))
                if label.casefold() in wanted and (field or not is_field):
                    self.tap_node(node)
                    time.sleep(1.5)
                    return
            time.sleep(1)
        raise RuntimeError(f"no view with text {texts!r}")


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

    def enabled(names, subtype):
        if by_settings:
            return subtype in enabled_subtypes(serial)
        text = gboard_languages(serial, ui)
        return any(name in text for name in names)

    for search, names, layout, subtype in KEYBOARDS:
        if enabled(names, subtype):
            continue
        print(f"Adding the {search} keyboard to Gboard")
        adb(serial, "shell", "am", "start", "-S", "-W", "-n", GBOARD_SETTINGS)
        time.sleep(2)
        ui.tap("Languages")
        ui.tap("Add keyboard")
        ui.tap("Search language", "Search", "Search for a language", field=True)
        adb(serial, "shell", "input", "text", search)
        time.sleep(2)
        ui.tap(*names)
        # Select exactly the wanted layout.
        for label, node in ui.nodes():
            is_layout = node.get("class") == "android.widget.LinearLayout" and node.get("clickable") == "true"
            if is_layout and label and (node.get("selected") == "true") != (label == layout):
                ui.tap_node(node)
                time.sleep(1)
        ui.tap("Done")
        adb(serial, "shell", "am", "force-stop", GBOARD_SETTINGS.split("/")[0])
        if not enabled(names, subtype):
            raise RuntimeError(f"Gboard did not enable the {search} keyboard")
    missing = [names[0] for names, subtype in [(ENGLISH_NAMES, ENGLISH_SUBTYPE), *((k[1], k[3]) for k in KEYBOARDS)]
               if not enabled(names, subtype)]
    if missing:
        raise RuntimeError(f"Gboard keyboards missing: {missing}")


def ime_navigation_bar(serial):
    """The navigation bar that the IME draws under its keys with gesture navigation, as (height,
    center of the IME switcher button or None), or None if the IME draws none.

    `dumpsys input_method` gives the bar's frame and its view tree, whose views give their position
    in their parent as "pos=L,T-R,B" on newer versions and as l, t, w, and h attributes on older
    ones. The bar's right button is the IME switcher; older versions hide it and show a language
    key in the keyboard instead."""
    dump = adb(serial, "shell", "dumpsys", "input_method")
    bar = re.search(r"mNavigationBarController=\{mImeDrawsImeNavBar=true mNavigationBarFrame=\S+ \S+ \S+ "
                    r"-?\d+,(-?\d+)-(-?\d+),(-?\d+)", dump)
    if not bar:
        return None
    height = int(bar.group(3)) - int(bar.group(1))
    lines = dump[dump.index("<NavigationBarFrame"):].splitlines()[1:]
    ancestors, buttons = [], []
    for line in itertools.takewhile(lambda line: "NavigationBarFrame" not in line, lines):
        new = re.search(r"<(\w+)[^>]* pos=(-?\d+),-?\d+-(-?\d+),-?\d+ visibility=\"(\d+)\"", line)
        old = re.search(r"<(\w+)[^>]* l=\"(-?\d+)\" t=\"-?\d+\" w=\"(\d+)\" h=\"\d+\" visibility=\"(\d+)\"", line)
        if new:
            name, x, width, visibility = new.group(1), int(new.group(2)), int(new.group(3)) - int(new.group(2)), new.group(4)
        elif old:
            name, x, width, visibility = old.group(1), int(old.group(2)), int(old.group(3)), old.group(4)
        else:
            continue
        # A view's ancestors are the views above it with less indentation.
        indent = len(line) - len(line.lstrip())
        while ancestors and ancestors[-1][0] >= indent:
            ancestors.pop()
        if name == "KeyButtonView":
            buttons.append((sum(a[1] for a in ancestors) + x + width // 2, visibility == "0" and width > 0))
        ancestors.append((indent, x))
    if len(buttons) != 2:
        raise RuntimeError(f"expected the back and IME switcher buttons in the IME's navigation bar, found {buttons}")
    x, shown = max(buttons)
    return height, x if shown else None


def keyboard_region(serial):
    """The touchable region of the software keyboard, (left, top, right, bottom), and the IME's
    navigation bar (ime_navigation_bar()), once the keyboard shows.

    The region holds only the bar while the keyboard is hidden, and grows while the keyboard slides
    in; it is taken when it is taller than the bar and has not changed since it was last read."""
    last = None
    for _ in range(20):
        windows = adb(serial, "shell", "dumpsys", "window", "windows")
        match = re.search(r"Window\{\S+ u0 InputMethod\}:.*?touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)\)",
                          windows, re.S)
        region = tuple(int(v) for v in match.groups()) if match else None
        bar = ime_navigation_bar(serial)
        if region and region == last and region[3] - region[1] > (bar[0] if bar else 0):
            return region, bar
        last = region
        time.sleep(0.5)
    raise RuntimeError(f"the software keyboard did not show; its last touchable region was {last}")


def adapt_to_device(serial, scenarios):
    """On anything but the AVD, finds Gboard's keys from its touchable region, and switches its
    language by typing, since the subtype in the settings may not follow it. Phones with the AVD's
    screen size are not the AVD: their keyboards differ from it in height."""
    global terminal_tap, switch_language
    width, height = (int(v) for v in re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size")).groups())
    emulator = adb(serial, "shell", "getprop", "ro.boot.qemu").strip() == "1"
    if emulator and f"{width}x{height}" == AVD_SCREEN:
        return
    terminal_tap = (width // 2, height // 3)
    adb(serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
    time.sleep(2)
    scenarios.tap(terminal_tap)
    (left, top, right, bottom), bar = keyboard_region(serial)
    bar_height, switcher_x = bar or (0, None)
    keys_bottom = bottom - bar_height
    switcher = (switcher_x, bottom - bar_height // 2) if switcher_x is not None else None
    scenarios.LAYOUTS = scenarios.layouts_for(left, top, right, keys_bottom, switcher)
    print(f"Keyboard region {(left, top, right, bottom)} on a {width}x{height} screen, "
          f"IME navigation bar {bar_height} high, IME switcher at {switcher}")

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

    names = {"en": ENGLISH_NAMES, **{subtype_lang: k[1] for subtype_lang, k in zip(("ja", "ko"), KEYBOARDS)}}
    ui = Ui(serial)

    def choose_from_list(lang):
        """Taps the language in the list of keyboards, whose items may add the layout to the name
        (e.g. "한국어(두벌식)"). Returns whether the list had it."""
        for label, node in ui.nodes():
            if label.startswith(names[lang]):
                ui.tap_node(node)
                return True
        return False

    def switch(lang):
        # A tap on the language key moves to the next language on some versions, and back to the
        # previous one on others (so that taps alone never reach a third language); on others the
        # tap opens the system's list of the keyboards, and holding the key opens such a list on
        # some versions. A language is chosen by name in a list that shows. Taps are tried first,
        # then holding the key.
        for attempt in range(6):
            current = language()
            if current == lang:
                break
            x, y = scenarios.LAYOUTS[current or "en"]["GLOBE"]
            if attempt < 3:
                scenarios.tap((x, y))
                time.sleep(1.5)
                choose_from_list(lang)
            else:
                adb(serial, "shell", "input", "swipe", str(x), str(y), str(x), str(y), "1200")
                time.sleep(1.5)
                if not choose_from_list(lang):
                    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
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
