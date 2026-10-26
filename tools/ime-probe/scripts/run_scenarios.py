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

"""Drives Gboard on an emulator through scripted scenarios and records IME probe traces.

The key coordinates assume a 1080x2400 screen (Pixel 7 AVD) and Gboard 17.2 with the
English (US), Japanese (QWERTY) and Korean (2-set) keyboards enabled in that order.
layouts_for() computes them for other screens (scripts/e2e_ime.py uses it on devices).

Usage: run_scenarios.py OUT_DIR [SCENARIO_GLOB ...]
"""

import fnmatch
import os
import subprocess
import sys
import time

ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
ACTIVITY = "vtfx.tools.imeprobe/.MainActivity"

# Values of `settings get secure selected_input_method_subtype` per Gboard language.
SUBTYPES = {"en": "1594443099", "ja": "-1318396357", "ko": "-1906255757"}

ROW_Y = [1716, 1868, 2024, 2180]
TOP_X = [58, 166, 272, 378, 486, 593, 700, 806, 913, 1020]
BOTTOM_LETTERS_X = [218, 325, 432, 539, 646, 752, 859]
GLOBE = (920, 2336)


def _layout(home_row_x, bottom_row):
    keys = {}
    for ch, x in zip("qwertyuiop", TOP_X):
        keys[ch] = (x, ROW_Y[0])
    for ch, x in zip("asdfghjkl", home_row_x):
        keys[ch] = (x, ROW_Y[1])
    for ch, x in zip("zxcvbnm", BOTTOM_LETTERS_X):
        keys[ch] = (x, ROW_Y[2])
    keys["SHIFT"] = (84, ROW_Y[2])
    keys["BKSP"] = (994, ROW_Y[2])
    for name, x in bottom_row.items():
        keys[name] = (x, ROW_Y[3])
    return keys


LAYOUTS = {
    "en": _layout([112, 218, 325, 432, 539, 646, 752, 859, 966],
                  {"SYM": 84, ",": 218, " ": 539, ".": 859, "ENTER": 994}),
    "ja": _layout(TOP_X[:9],
                  {"KANA": 84, "、": 218, "?": 325, " ": 486, "。": 646, "LEFT": 752, "RIGHT": 859, "ENTER": 994}),
}
# The Korean 2-set layout shares the English geometry. Type it with the QWERTY letters.
LAYOUTS["ko"] = LAYOUTS["en"]


def layouts_for(left, top, right, bottom):
    """The key coordinates of Gboard's keyboards on any screen, from the touchable region of the
    keyboard (`touchable region` of the InputMethod window in `dumpsys window windows`).

    The keys are at fixed fractions of the width, measured on the Pixel 7 AVD and on a Pixel XL
    with Gboard 18.4: four rows of keys under a toolbar about 0.73 of a row high. On a device with
    three-button navigation, Gboard shows a language switch key ("GLOBE") where the Japanese
    keyboard has "?" and left of the English space bar."""
    key = (right - left) / 10
    row = (bottom - top) / (4 + 0.733)
    rows = [round(bottom - (3.5 - k) * row) for k in range(4)]

    def at(fraction, k):
        return (round(left + fraction * 10 * key), rows[k])

    def layout(home_row_offset, bottom_row):
        keys = {ch: at((i + 0.5) / 10, 0) for i, ch in enumerate("qwertyuiop")}
        keys.update({ch: at((i + home_row_offset) / 10, 1) for i, ch in enumerate("asdfghjkl")})
        keys.update({ch: at((i + 2) / 10, 2) for i, ch in enumerate("zxcvbnm")})
        keys["SHIFT"] = at(0.078, 2)
        keys["BKSP"] = at(0.92, 2)
        keys.update({name: at(fraction, 3) for name, fraction in bottom_row.items()})
        return keys

    layouts = {
        "en": layout(1.0, {"SYM": 0.078, ",": 0.2, "GLOBE": 0.3, " ": 0.55, ".": 0.795, "ENTER": 0.92}),
        "ja": layout(0.5, {"KANA": 0.078, "、": 0.2, "?": 0.3, "GLOBE": 0.3, " ": 0.45, "。": 0.6, "LEFT": 0.7,
                           "RIGHT": 0.795, "ENTER": 0.92}),
    }
    layouts["ko"] = layouts["en"]
    return layouts


def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check).stdout.strip()


def tap(xy):
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))


def ensure_language(lang):
    """Switches Gboard to LANG. Returns False if it is not possible, e.g. for password fields."""
    for _ in range(len(SUBTYPES) + 1):
        if adb("shell", "settings", "get", "secure", "selected_input_method_subtype") == SUBTYPES[lang]:
            return True
        tap(GLOBE)
        time.sleep(1.5)
    return False


def run(name, lang, steps, out_dir, surrounding="none", input_type="text", no_learning=True, edit_text=False,
        reject_selection=False):
    """Runs one scenario.

    Each step is either a string of characters to tap one by one, a list of key names
    (e.g. ["ENTER"]), a float to sleep for that many seconds, or a tuple of Android key code
    names to inject as hardware key events (e.g. ("KEYCODE_A",)).
    """
    adb("shell", "am", "start", "-S", "-W", "-n", ACTIVITY,
        "--es", "label", name, "--es", "surrounding", surrounding, "--es", "inputType", input_type,
        "--ez", "noLearning", "true" if no_learning else "false",
        "--ez", "editText", "true" if edit_text else "false",
        "--ez", "rejectSelection", "true" if reject_selection else "false")
    time.sleep(1.5)
    tap((540, 900))
    time.sleep(2)
    if not ensure_language(lang):
        print(f"{name}: could not switch to {lang}; typing on the current layout")
    adb("logcat", "-c")
    keys = LAYOUTS[lang]
    for step in steps:
        if isinstance(step, float):
            time.sleep(step)
            continue
        if isinstance(step, tuple):
            for code in step:
                adb("shell", "input", "keyboard", "keyevent", code)
                time.sleep(0.4)
            continue
        for key in step:
            tap(keys[key])
            time.sleep(0.4)
    time.sleep(2)
    trace = adb("logcat", "-d", "-s", "VtfxImeProbe:I", "-v", "raw")
    lines = [line for line in trace.splitlines() if line.startswith("{")]
    with open(os.path.join(out_dir, name + ".jsonl"), "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(out_dir, name + ".png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
    print(f"{name}: {len(lines)} events")


SCENARIOS = {
    # Japanese: composition is held until explicitly confirmed.
    "ja-confirm-enter": ("ja", ["nihongo", ["ENTER"], ["ENTER"]], {}),
    "ja-convert": ("ja", ["kanji", [" "], 1.0, ["ENTER"], ["ENTER"]], {}),
    "ja-backspace": ("ja", ["aiu", ["BKSP"], ["ENTER"], ["BKSP"], ["BKSP"]], {}),
    "ja-ascii-symbol": ("ja", ["a", ["?"], ["ENTER"]], {}),
    # Hardware keyboard: key events go through the IME first.
    "ja-hw-convert": ("ja", [("KEYCODE_K", "KEYCODE_A", "KEYCODE_N", "KEYCODE_J", "KEYCODE_I",
                              "KEYCODE_SPACE", "KEYCODE_ENTER", "KEYCODE_ENTER")], {}),
    # English: per-key delivery and autocorrect.
    "en-vim-j": ("en", ["j", 2.0, "j"], {}),
    "en-command": ("en", ["ls", [" "], ["ENTER"]], {}),
    "en-autocorrect": ("en", ["teh", [" "], 1.0, ["ENTER"]], {}),
    "en-double-space": ("en", ["ok", [" "], [" "], ["ENTER"]], {}),
    # Korean: composition where a final consonant can move to the next syllable.
    "ko-hangul": ("ko", ["gksrnr", [" "], ["ENTER"]], {}),
    "ko-jamo-move": ("ko", ["rkr", 1.0, "k", ["ENTER"]], {}),
    "ko-enter-mid": ("ko", ["gks", ["ENTER"]], {}),
}

# Variants that change the editor configuration.
for base in ["en-vim-j", "en-autocorrect", "en-double-space"]:
    for input_type in ["text_nosuggest", "visible_password", "null"]:
        lang, steps, _ = SCENARIOS[base]
        SCENARIOS[f"{base}@{input_type}"] = (lang, steps, {"input_type": input_type})
    lang, steps, _ = SCENARIOS[base]
    SCENARIOS[f"{base}@history"] = (lang, steps, {"surrounding": "history"})
for base in ["en-vim-j", "en-autocorrect", "en-double-space", "ja-convert", "ko-hangul"]:
    lang, steps, _ = SCENARIOS[base]
    SCENARIOS[f"{base}@learning"] = (lang, steps, {"no_learning": False})
    SCENARIOS[f"{base}@learning+history"] = (lang, steps, {"no_learning": False, "surrounding": "history"})
for base in ["en-vim-j", "en-autocorrect", "en-double-space", "en-command", "ja-convert", "ko-hangul"]:
    for input_type in ["text_autocorrect", "text_multiline"]:
        lang, steps, _ = SCENARIOS[base]
        SCENARIOS[f"{base}@{input_type}"] = (lang, steps, {"input_type": input_type})
        SCENARIOS[f"{base}@{input_type}+history"] = (lang, steps, {"input_type": input_type, "surrounding": "history"})
for base in ["en-autocorrect", "en-double-space", "ja-convert", "ko-hangul"]:
    lang, steps, _ = SCENARIOS[base]
    SCENARIOS[f"{base}@edittext"] = (lang, steps, {"edit_text": True})
    SCENARIOS[f"{base}@edittext+learning"] = (lang, steps, {"edit_text": True, "no_learning": False})
    SCENARIOS[f"{base}@edittext+typical"] = (lang, steps, {"edit_text": True, "no_learning": False, "input_type": "text_typical"})
    SCENARIOS[f"{base}@typical"] = (lang, steps, {"no_learning": False, "input_type": "text_typical"})
    SCENARIOS[f"{base}@typical+history"] = (lang, steps, {"no_learning": False, "input_type": "text_typical", "surrounding": "history"})
for base in ["ja-convert", "ko-hangul"]:
    for input_type in ["text_nosuggest", "visible_password", "null"]:
        lang, steps, _ = SCENARIOS[base]
        SCENARIOS[f"{base}@{input_type}"] = (lang, steps, {"input_type": input_type})

# The editor configuration of docs/design/ime-contract.md. Traces recorded under it can be replayed
# as regression tests, since the IME saw the same editor as it would with vtfx.
CONTRACT = {"surrounding": "history", "input_type": "text", "no_learning": True, "reject_selection": True}
for base in [name for name in list(SCENARIOS) if "@" not in name]:
    lang, steps, _ = SCENARIOS[base]
    SCENARIOS[f"{base}@contract"] = (lang, steps, CONTRACT)


def main():
    out_dir = sys.argv[1]
    patterns = sys.argv[2:] or ["*"]
    os.makedirs(out_dir, exist_ok=True)
    for name, (lang, steps, options) in SCENARIOS.items():
        if any(fnmatch.fnmatch(name, p) for p in patterns):
            run(name, lang, steps, out_dir, **options)


if __name__ == "__main__":
    main()
