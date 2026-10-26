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

"""Runs checks of docs/device-checks.md on a real device and writes a report.

Keys come from a virtual USB keyboard that the platform's `hid` shell command makes through uhid,
so they take the path of a physical keyboard, unlike `adb shell input` (docs/decisions/0092). The
sample app (vtfx-sample) logs the bytes it sends to its pseudo host, which are compared with the
expectations of device-checks.md. The result is for reporting, not a gate (docs/design/testing.md
section 8).

The script talks to the device only through adb and opens no path from the device to this machine
(topic 3 of docs/decisions/0092). It changes the settings that the checks need: the IME, which it
puts back at the end, Gboard's input mode for physical keyboards, and the layout of the virtual
keyboard.

Usage: device_checks.py --serial SERIAL [--report PATH] [--skip-build] [CHECK_GLOB ...]
"""

import argparse
import datetime
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import e2e_ime
from e2e_ime import ACTIVITY, GBOARD, Ui, adb, collect_bytes, show

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# A boot keyboard: a byte of modifiers, a reserved byte, and six key codes (HID Usage Tables,
# Keyboard/Keypad page). The name, vendor, and product identify the device to the platform, which
# keeps the keyboard layout chosen for it across registrations: the US keyboard keeps the default
# layout, and the German one keeps the German layout once set_layout() has chosen it.
KEYBOARD_VENDOR = 0x18D1
US_KEYBOARD = ("vtfx virtual keyboard", 0x4F00)
GERMAN_KEYBOARD = ("vtfx virtual keyboard (German)", 0x4F01)
KEYBOARD_DESCRIPTOR = [
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,  # Usage Page (Generic Desktop), Usage (Keyboard), Collection (Application)
    0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,  # Modifiers
    0x95, 0x01, 0x75, 0x08, 0x81, 0x01,  # Reserved byte
    0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,  # LEDs
    0x95, 0x01, 0x75, 0x03, 0x91, 0x01,  # LED padding
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,  # Keys
    0xC0,  # End Collection
]
MODIFIERS = {"ctrl": 0x01, "shift": 0x02, "alt": 0x04, "meta": 0x08, "rctrl": 0x10, "rshift": 0x20, "ralt": 0x40}
KEYS = {chr(ord("a") + i): 0x04 + i for i in range(26)}
KEYS.update({str(i): 0x1E + i - 1 for i in range(1, 10)})
KEYS.update({"0": 0x27, "enter": 0x28, "esc": 0x29, "backspace": 0x2A, "tab": 0x2B, "space": 0x2C,
             "right": 0x4F, "left": 0x50, "down": 0x51, "up": 0x52})


def keyboard_report(chord):
    """The input report of KEYBOARD_DESCRIPTOR that holds a chord such as "ctrl+shift+k"."""
    *mods, key = chord.split("+")
    modifiers = 0
    for mod in mods:
        modifiers |= MODIFIERS[mod]
    return [modifiers, 0, KEYS[key], 0, 0, 0, 0, 0]


class VirtualKeyboard:
    """A USB keyboard that the `hid` shell command adds to the device until close()."""

    def __init__(self, serial, identity):
        self.serial = serial
        self.name, self.product = identity
        self.open()

    def open(self):
        """Adds the keyboard to the device, after close() too."""
        self.process = subprocess.Popen([e2e_ime.ADB_PATH, "-s", self.serial, "shell", "hid", "-"],
                                        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                        text=True)
        self._send({"id": 1, "command": "register", "name": self.name, "vid": KEYBOARD_VENDOR, "pid": self.product,
                    "bus": "usb", "descriptor": KEYBOARD_DESCRIPTOR})
        deadline = time.time() + 10
        while self.name not in adb(self.serial, "shell", "dumpsys", "input"):
            if time.time() > deadline:
                self.close()
                raise RuntimeError("the virtual keyboard did not appear in dumpsys input")
            time.sleep(0.5)
        time.sleep(1)

    def _send(self, command):
        self.process.stdin.write(json.dumps(command) + "\n")
        self.process.stdin.flush()

    def press(self, chord):
        """Presses and releases a chord such as "ctrl+shift+k", "ralt+q", or "enter"."""
        self._send({"id": 1, "command": "report", "report": keyboard_report(chord)})
        time.sleep(0.05)
        self._send({"id": 1, "command": "report", "report": [0] * 8})
        time.sleep(0.15)

    def type(self, *chords, gap=0.0):
        for chord in chords:
            self.press(chord)
            time.sleep(gap)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            self.process.wait(timeout=10)


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.ui = Ui(serial)

    def shell(self, *args):
        return adb(self.serial, "shell", *args)

    def setting(self, name):
        return self.shell("settings", "get", "secure", name).strip()

    def install(self, apk):
        """Installs the APK unless the same one is installed. Declines Play Protect's offer to send
        the app for a security check, which otherwise holds the installation."""
        installed = self.shell("pm", "path", "com.google.vtfx.sample").strip().removeprefix("package:")
        if installed:
            remote = self.shell("sha256sum", installed).split()[0]
            local = subprocess.run(["sha256sum", apk], capture_output=True, text=True, check=True).stdout.split()[0]
            if remote == local:
                return
        process = subprocess.Popen([e2e_ime.ADB_PATH, "-s", self.serial, "install", "-r", apk],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        while process.poll() is None:
            time.sleep(2)
            for label, node in self.ui.nodes():
                if label == "Don't send":
                    self.ui.tap_node(node)
        output = process.stdout.read()
        if process.returncode != 0 or "Success" not in output:
            raise RuntimeError(f"installing {apk} failed: {output}")

    def switch_gboard_language(self, lang):
        """Switches the language of Gboard's software keyboard as scripts/e2e_ime.py does."""
        os.environ["ANDROID_SERIAL"] = self.serial  # run_scenarios.py calls adb without a serial.
        scenarios = e2e_ime.load_scenarios()
        e2e_ime.adapt_to_device(self.serial, scenarios)
        if not (e2e_ime.switch_language or scenarios.ensure_language)(lang):
            raise RuntimeError(f"could not switch Gboard to {lang}")

    def start_sample(self):
        """Starts the sample app with the terminal focused, and clears the log of host input."""
        self.shell("am", "start", "-S", "-W", "-n", ACTIVITY, "--ez", "logHostInput", "true")
        time.sleep(2)
        width, height = (int(v) for v in re.search(r"(\d+)x(\d+)", self.shell("wm", "size")).groups())
        self.shell("input", "tap", str(width // 2), str(height // 3))
        time.sleep(1.5)
        adb(self.serial, "logcat", "-c")

    def host_bytes(self, settle=1.5):
        time.sleep(settle)
        return collect_bytes(self.serial)

    def toast_shown(self, timeout=2.0):
        """Whether a toast of the sample app is in the toast queue within `timeout` seconds."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            out = self.shell("dumpsys", "notification")
            queue = out[out.find("Toast Queue"):][:2000] if "Toast Queue" in out else ""
            # "ToastRecord{... pkg=com.google.vtfx.sample ..." up to Android 10, and
            # "TextToastRecord{... 1234:com.google.vtfx.sample/u0a1 ..." later.
            if "com.google.vtfx.sample" in queue:
                return True
            time.sleep(0.2)
        return False

    def switch_rows(self):
        """The rows of the screen that hold a switch or a check box: (title, checked, row node)."""
        out = adb(self.serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        root = ET.fromstring(out[out.find("<?xml"):out.rfind(">") + 1])
        rows = []
        for node in root.iter("node"):
            if node.get("clickable") != "true":
                continue
            toggles = [n for n in node.iter("node") if n.get("checkable") == "true"]
            texts = [n.get("text") for n in node.iter("node") if n.get("text") and n.get("checkable") != "true"]
            if len(toggles) == 1 and texts:
                rows.append((texts[0], toggles[0].get("checked") == "true", node))
        return rows

    def set_layout(self, keyboard_name, layout):
        """Turns on `layout` (e.g. "German") as the only layout of a physical keyboard in Settings."""
        self.shell("am", "start", "-S", "-W", "-a", "android.settings.HARD_KEYBOARD_SETTINGS")
        time.sleep(2)
        self.ui.tap(keyboard_name)
        for label, node in self.ui.nodes():
            if label == "Set up keyboard layouts":
                self.ui.tap_node(node)
                time.sleep(2)
                break
        found, seen = False, None
        for _ in range(40):
            rows = self.switch_rows()
            titles = [title for title, _, _ in rows]
            if titles == seen:
                break
            seen = titles
            for title, checked, node in rows:
                if checked != (title == layout):
                    self.ui.tap_node(node)
                    time.sleep(1)
                found |= title == layout
            self.shell("input", "swipe", "500", "1800", "500", "900", "400")
            time.sleep(1)
        if not found:
            raise RuntimeError(f"no keyboard layout {layout!r} in Settings")
        self.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)


def gboard_mode(device, keyboard):
    """Types "a" and Enter, and tells Gboard's mode for physical keys by what reaches the host."""
    device.start_sample()
    keyboard.type("a", "enter")
    data = device.host_bytes(settle=1)
    return {b"a\r": "latin", "あ".encode(): "japanese"}.get(data)


def ensure_gboard_mode(device, keyboard, mode):
    """Puts Gboard in `mode` ("latin" or "japanese") for physical keys. Physical keys follow the
    language of Gboard's software keyboard, and its Japanese keyboard switches between kana and
    Latin input on Shift+Space; neither Ctrl+Space nor the subtype in the settings changes them. So
    the keyboard is first switched by Shift+Space, and if that does not do, the software keyboard is
    switched to Japanese or English, without the virtual keyboard, which would hide it. Raises if it
    cannot."""
    for attempt in range(2):
        for _ in range(3):
            if gboard_mode(device, keyboard) == mode:
                return
            device.start_sample()
            keyboard.press("shift+space")
            time.sleep(1)
        if attempt == 0:
            keyboard.close()
            device.switch_gboard_language("ja" if mode == "japanese" else "en")
            keyboard.open()
    raise RuntimeError(f"could not put Gboard in the {mode} mode for physical keys")


def check_interception(device, keyboards):
    """3.1: the app takes Ctrl+Shift+K before Gboard, in English and Japanese; Ctrl+C still reaches the host."""
    keyboard = keyboards(US_KEYBOARD)
    results = []
    ensure_gboard_mode(device, keyboard, "latin")
    device.start_sample()
    keyboard.press("ctrl+shift+k")
    toast = device.toast_shown()
    data = device.host_bytes()
    status = "pass" if data == b"" and toast else "fail"
    results.append(("3.1.1", "Ctrl+Shift+K with Gboard (English)", "the toast, and nothing sent",
                    f"toast {'shown' if toast else 'not shown'}, sent `{show(data)}`", status))
    adb(device.serial, "logcat", "-c")
    keyboard.press("ctrl+c")
    data = device.host_bytes()
    results.append(("3.1.2", "Ctrl+C", "`03`", f"`{data.hex()}`", "pass" if data == b"\x03" else "fail"))
    ensure_gboard_mode(device, keyboard, "japanese")
    device.start_sample()
    keyboard.press("ctrl+shift+k")
    toast = device.toast_shown()
    data = device.host_bytes()
    status = "pass" if data == b"" and toast else "fail"
    results.append(("3.1.3", "Ctrl+Shift+K with Gboard (Japanese)", "the toast, and nothing sent",
                    f"toast {'shown' if toast else 'not shown'}, sent `{show(data)}`", status))
    return results


def check_altgr(device, keyboards):
    """3.2: with the German layout, AltGr types the character of the layout; left Alt stays ESC."""
    keyboard = keyboards(GERMAN_KEYBOARD)
    ensure_gboard_mode(device, keyboard, "latin")
    device.start_sample()
    keyboard.type("y", "enter")
    if device.host_bytes(settle=1) != b"z\r":  # The German layout swaps Y and Z.
        device.set_layout(keyboard.name, "German")
    results = []
    for row, chord, expected in (("3.2.1", "ralt+q", b"@"), ("3.2.2", "ralt+7", b"{"), ("3.2.3", "alt+q", b"\x1bq")):
        device.start_sample()
        keyboard.press(chord)
        data = device.host_bytes()
        results.append((row, f"{chord} with the German layout", f"`{expected.hex()}`", f"`{data.hex()}`",
                        "pass" if data == expected else "fail"))
    return results


def check_keys_after_enter(device, keyboards):
    """15.2: keys typed right after Enter on a hardware keyboard all reach the host."""
    keyboard = keyboards(US_KEYBOARD)
    ensure_gboard_mode(device, keyboard, "latin")
    device.start_sample()
    keyboard.type(*"echo", "space", *"hi", "enter", *"ls", "enter")
    data = device.host_bytes(settle=3)
    expected = b"echo hi\rls\r"
    return [("15.2", "`echo hi`, Enter, and right away `ls`, Enter", f"`{show(expected)}`", f"`{show(data)}`",
             "pass" if data == expected else "fail")]


CHECKS = {
    "3.1": check_interception,
    "3.2": check_altgr,
    "15.2": check_keys_after_enter,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), required="ANDROID_SERIAL" not in os.environ)
    parser.add_argument("--report", default=os.path.join(ROOT, "build", "reports", "device-checks.md"))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("patterns", nargs="*", default=["*"])
    args = parser.parse_args()

    if not args.skip_build:
        subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-sample:assembleDebug"],
                       cwd=ROOT, check=True)
    apk = os.path.join(ROOT, "vtfx-sample", "build", "outputs", "apk", "debug", "vtfx-sample-debug.apk")
    device = Device(args.serial)
    device.install(apk)
    device.shell("input", "keyevent", "KEYCODE_WAKEUP")
    device.shell("wm", "dismiss-keyguard")
    saved_ime = device.setting("default_input_method")
    device.shell("ime", "enable", GBOARD)
    device.shell("ime", "set", GBOARD)

    results = []
    keyboards = {}

    def keyboard(identity):
        """The virtual keyboard with `identity`, the only one registered while a check uses it."""
        if identity not in keyboards:
            for other in keyboards.values():
                other.close()
            keyboards.clear()
            keyboards[identity] = VirtualKeyboard(args.serial, identity)
        return keyboards[identity]

    try:
        for name, check in CHECKS.items():
            if any(fnmatch.fnmatch(name, p) for p in args.patterns):
                print(f"Running {name}")
                try:
                    results += check(device, keyboard)
                except RuntimeError as e:
                    # A check that the device cannot run (e.g. Settings without the screens that it
                    # uses) is reported, and the others still run.
                    results.append((name, check.__doc__.splitlines()[0], "", f"could not run: {e}", "error"))
    finally:
        for other in keyboards.values():
            other.close()
        if saved_ime and saved_ime != "null":
            device.shell("ime", "set", saved_ime)

    props = {name: device.shell("getprop", prop).strip() for name, prop in
             (("model", "ro.product.model"), ("release", "ro.build.version.release"),
              ("sdk", "ro.build.version.sdk"), ("build", "ro.build.type"))}
    gboard = re.search(r"versionName=(\S+)", device.shell("dumpsys", "package", "com.google.android.inputmethod.latin"))
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    failed = sum(1 for r in results if r[4] == "fail")
    errors = sum(1 for r in results if r[4] == "error")
    lines = [
        "# Device check results",
        "",
        "Generated by `scripts/device_checks.py`. Do not edit by hand. The rows are those of `docs/device-checks.md`, with keys "
        "from a virtual USB keyboard (uhid). For reports, not a gate (section 8 of testing.md).",
        "",
        "| Item | Value |",
        "|---|---|",
        f"| Date (UTC) | {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M')} |",
        f"| Commit | {commit} |",
        f"| Device | {props['model']}, Android {props['release']} (API {props['sdk']}), {props['build']} build |",
        f"| Gboard | {gboard.group(1) if gboard else 'unknown'} |",
        f"| Result | {len(results) - failed - errors} passed, {failed} failed, {errors} could not run |",
        "",
        "| Row | Action | Expected | Actual | Result |",
        "|---|---|---|---|---|",
    ]
    lines += [f"| {row} | {action} | {expected} | {actual} | {status} |" for row, action, expected, actual, status in results]
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(results) - failed - errors} passed, {failed} failed, {errors} could not run -> {args.report}")
    return 1 if failed or errors else 0


if __name__ == "__main__":
    sys.exit(main())
