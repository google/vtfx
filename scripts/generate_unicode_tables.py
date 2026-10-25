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

"""Generates the character width tables of vtfx-core (UnicodeWidthTables.kt), the diacritics of
the Unicode placeholders of the kitty graphics protocol in vtfx-core (PlaceholderDiacritics.kt), and
the table of default-ignorable code points of vtfx-gate (UnicodeDefaultIgnorables.kt).

The tables come from the copy of the Unicode Character Database in third_party/unicode
(docs/decisions/0079), and the generated sources are checked in (docs/decisions/0088). Run this
script after updating the UCD files, and commit the result. With --check, it writes nothing and
exits with status 1 if a checked-in file is not what it would generate; scripts/check.sh runs
it that way.

The width rules follow glibc's localedata/unicode-gen/utf8_gen.py, so that the widths agree with
the wcwidth() of hosts with the same Unicode version:
- 0: general categories Mn, Me, and Cf except U+00AD; Hangul Jungseong and Jongseong
  (U+1160..U+11FF, U+D7B0..U+D7FF); U+200B.
- 2: East_Asian_Width W or F, including the defaults for unassigned code points in the CJK blocks.
- Ambiguous: East_Asian_Width A, which is 1 or 2 by the terminal's setting.
- 1: everything else.

The default-ignorable code points are those with the Default_Ignorable_Code_Point property in
DerivedCoreProperties.txt. They draw as nothing, so TerminalGate refuses URLs that contain them
(docs/decisions/0083).

The diacritics give the row, the column, and the high byte of the image id of a placeholder cell
(docs/decisions/0080), in the order of kitty's rowcolumn-diacritics.txt, which kitty derives from
UnicodeData.txt of Unicode 6.0.0: the characters of general category Mn and combining class 230
without a decomposition, except 19 that normalization may join with a base character. The same
rule over these files, limited to the characters of Unicode 6.0 or earlier (DerivedAge.txt), gives
the same 297 characters in the same order; the Bidi_Class NSM of kitty's rule holds for every Mn.

The Unicode version is read from the first line of the files, which must agree.

Usage: generate_unicode_tables.py [--check] [--ucd DIR] [--output FILE]
                                  [--ignorables-output FILE] [--diacritics-output FILE]
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_UCD = ROOT / "third_party" / "unicode" / "ucd"
DEFAULT_OUTPUT = ROOT / "vtfx-core" / "src" / "main" / "kotlin" / "com" / "google" / "vtfx" / "core" / "UnicodeWidthTables.kt"
DEFAULT_IGNORABLES_OUTPUT = (
    ROOT / "vtfx-gate" / "src" / "main" / "kotlin" / "com" / "google" / "vtfx" / "gate" / "UnicodeDefaultIgnorables.kt")
DEFAULT_DIACRITICS_OUTPUT = (
    ROOT / "vtfx-core" / "src" / "main" / "kotlin" / "com" / "google" / "vtfx" / "core" / "graphics"
    / "PlaceholderDiacritics.kt")

# The combining characters that kitty leaves out of the diacritics, since normalization may join them
# with a base character (rowcolumn-diacritics.txt).
DIACRITIC_EXCLUSIONS = frozenset(int(code, 16) for code in (
    "0300 0301 0302 0303 0304 0306 0307 0308 0309 030A 030B 030C 030F 0311 0313 0314 0342 0653 0654").split())

# The Unicode version of kitty's list of diacritics.
DIACRITICS_AGE = (6, 0)

MAX_CODE_POINT = 0x10FFFF
CODE_POINTS = MAX_CODE_POINT + 1

# The width class of each code point, as computed by classify().
OTHER = 0
ZERO = 1
WIDE = 2
AMBIGUOUS = 3

VERSION_LINE = re.compile(r"# (\w+)-(\d+\.\d+\.\d+)\.txt")
MISSING_LINE = re.compile(r"# @missing: ([0-9A-F]+)\.\.([0-9A-F]+); (\w+)")
LINE_BREAK = re.compile(r"\r\n|\n|\r")

HEADER = """\
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

"""


class UcdError(Exception):
    pass


def version(lines, name):
    """Returns the Unicode version in the first line of a UCD file, e.g. 16.0.0."""
    match = VERSION_LINE.fullmatch(lines[0] if lines else "")
    if match is None or match.group(1) != name:
        raise UcdError(f"The first line of {name}.txt does not name its version")
    return match.group(2)


def parse_ranges(lines, missing):
    """Yields the first and last code point and the value of each line of a UCD file, in order.

    With missing, also for the "# @missing:" lines, which give the defaults.
    """
    for line in lines:
        if line.startswith("# @missing:"):
            if missing:
                match = MISSING_LINE.search(line)
                if match:
                    yield int(match.group(1), 16), int(match.group(2), 16), match.group(3)
            continue
        data = line.split("#", 1)[0].strip()
        if not data:
            continue
        fields = data.split(";")
        codes = fields[0].strip()
        value = fields[1].strip()
        first, _, last = codes.partition("..")
        yield int(first, 16), int(last or first, 16), value


def classify(east_asian_width_lines, category_lines):
    """Returns the width class of every code point, as a bytearray indexed by code point."""
    # The East_Asian_Width of each code point that matters. The @missing lines give the defaults,
    # including those for unassigned code points in the CJK blocks, and the data lines that follow
    # override them.
    classes = bytearray(CODE_POINTS)
    for first, last, value in parse_ranges(east_asian_width_lines, missing=True):
        eaw = WIDE if value in ("W", "F") else AMBIGUOUS if value == "A" else OTHER
        classes[first : last + 1] = bytes([eaw]) * (last + 1 - first)
    soft_hyphen = classes[0x00AD]
    for first, last, value in parse_ranges(category_lines, missing=False):
        if value in ("Mn", "Me", "Cf"):
            classes[first : last + 1] = bytes([ZERO]) * (last + 1 - first)
    for first, last in ((0x1160, 0x11FF), (0xD7B0, 0xD7FF), (0x200B, 0x200B)):
        classes[first : last + 1] = bytes([ZERO]) * (last + 1 - first)
    classes[0x00AD] = soft_hyphen
    return classes


def ranges(classes, kind):
    """Returns the merged, inclusive ranges of code points of the class kind, in order."""
    result = []
    marker = bytes([kind])
    start = classes.find(marker)
    while start >= 0:
        end = start
        while end < CODE_POINTS and classes[end] == kind:
            end += 1
        result.append((start, end - 1))
        start = classes.find(marker, end)
    return result


def kotlin_array(name, doc, items):
    lines = [f"    /** {doc} */", f"    val {name}: IntArray = intArrayOf("]
    lines += [f"        0x{first:04X}, 0x{last:04X}," for first, last in items]
    lines.append("    )")
    return "\n".join(lines)


def read_lines(path):
    """Returns the lines of a UCD file, split at line breaks only (not at other separators)."""
    return LINE_BREAK.split(path.read_text(encoding="utf-8"))


def generate(ucd):
    """Returns the sources of UnicodeWidthTables.kt, UnicodeDefaultIgnorables.kt, and
    PlaceholderDiacritics.kt generated from the UCD files in ucd."""
    east_asian_width_lines = read_lines(ucd / "EastAsianWidth.txt")
    category_lines = read_lines(ucd / "extracted" / "DerivedGeneralCategory.txt")
    core_property_lines = read_lines(ucd / "DerivedCoreProperties.txt")
    combining_class_lines = read_lines(ucd / "extracted" / "DerivedCombiningClass.txt")
    decomposition_lines = read_lines(ucd / "extracted" / "DerivedDecompositionType.txt")
    age_lines = read_lines(ucd / "DerivedAge.txt")
    unicode_version = version(east_asian_width_lines, "EastAsianWidth")
    for lines, name in (
            (category_lines, "DerivedGeneralCategory"),
            (core_property_lines, "DerivedCoreProperties"),
            (combining_class_lines, "DerivedCombiningClass"),
            (decomposition_lines, "DerivedDecompositionType"),
            (age_lines, "DerivedAge")):
        other_version = version(lines, name)
        if unicode_version != other_version:
            raise UcdError(f"EastAsianWidth.txt is Unicode {unicode_version}, but {name}.txt is Unicode {other_version}")

    classes = classify(east_asian_width_lines, category_lines)
    body = "\n\n".join([
        kotlin_array("ZERO_WIDTH", "Code points that combine with the previous character.", ranges(classes, ZERO)),
        kotlin_array("WIDE", "Code points that occupy two cells.", ranges(classes, WIDE)),
        kotlin_array(
            "AMBIGUOUS",
            "East Asian Ambiguous code points: one or two cells by the setting.",
            ranges(classes, AMBIGUOUS)),
    ])
    width_tables = kotlin_object("com.google.vtfx.core", "UnicodeWidthTables", unicode_version, body)
    ignorables = kotlin_object(
        "com.google.vtfx.gate",
        "UnicodeDefaultIgnorables",
        unicode_version,
        kotlin_array(
            "RANGES",
            "Code points with the Default_Ignorable_Code_Point property, which draw as nothing.",
            default_ignorables(core_property_lines)))
    codes = diacritics(category_lines, combining_class_lines, decomposition_lines, age_lines)
    diacritics_source = (
        HEADER
        + f"// Generated by scripts/generate_unicode_tables.py from Unicode {unicode_version} in\n"
        + "// third_party/unicode. Do not edit.\n"
        + "\n"
        + "package com.google.vtfx.core.graphics\n"
        + "\n"
        + "/**\n"
        + " * The diacritics that give the row, the column, and the high byte of the image id of a Unicode\n"
        + " * placeholder of the kitty graphics protocol: the diacritic at index n stands for n.\n"
        + " */\n"
        + "internal object PlaceholderDiacritics {\n"
        + "    /** The diacritics, sorted, so that the index of one is its value. */\n"
        # As ktfmt formats it, so that the generated file is also formatted.
        + "    val CODE_POINTS: IntArray =\n"
        + "        intArrayOf(\n"
        + "".join(f"            0x{code:04X},\n" for code in codes)
        + "        )\n"
        + "}\n"
    )
    return width_tables, ignorables, diacritics_source


def diacritics(category_lines, combining_class_lines, decomposition_lines, age_lines):
    """Returns the diacritics of placeholders by kitty's rule, in order."""
    def values(lines):
        result = {}
        for first, last, value in parse_ranges(lines, missing=False):
            for code in range(first, last + 1):
                result[code] = value
        return result

    categories = values(category_lines)
    combining_classes = values(combining_class_lines)
    decompositions = values(decomposition_lines)
    ages = values(age_lines)
    codes = [
        code for code, category in categories.items()
        if category == "Mn"
        and combining_classes.get(code) == "230"
        and decompositions.get(code, "None") == "None"
        and code not in DIACRITIC_EXCLUSIONS
        and code in ages
        and tuple(int(part) for part in ages[code].split(".")) <= DIACRITICS_AGE
    ]
    if codes != sorted(codes):
        raise UcdError("The diacritics are not in order")
    return codes


def default_ignorables(core_property_lines):
    """Returns the merged, inclusive ranges of the Default_Ignorable_Code_Point property, in order."""
    flags = bytearray(CODE_POINTS)
    for first, last, value in parse_ranges(core_property_lines, missing=False):
        if value == "Default_Ignorable_Code_Point":
            flags[first : last + 1] = bytes([1]) * (last + 1 - first)
    return ranges(flags, 1)


def kotlin_object(package, name, unicode_version, body):
    return (
        HEADER
        + f"// Generated by scripts/generate_unicode_tables.py from Unicode {unicode_version} in\n"
        + "// third_party/unicode. Do not edit.\n"
        + "\n"
        + f"package {package}\n"
        + "\n"
        + "/** Sorted, inclusive ranges of code points, as pairs of first and last. */\n"
        + f"internal object {name} {{\n"
        + f'    const val UNICODE_VERSION: String = "{unicode_version}"\n'
        + "\n"
        + body
        + "\n}\n"
    )


def check(output, source, ucd):
    """Returns whether the file output holds source, printing what to do if not."""
    try:
        current = output.read_bytes()
    except FileNotFoundError:
        current = None
    if current != source.encode("utf-8"):
        print(
            f"{output} is not up to date with the UCD files in {ucd}.\n"
            "Run scripts/generate_unicode_tables.py and commit the result.")
        return False
    return True


def main(argv):
    parser = argparse.ArgumentParser(
        description="Generates the character width tables and the diacritics of placeholders of vtfx-core, "
                    "and the default-ignorable code points of vtfx-gate.")
    parser.add_argument("--check", action="store_true", help="check that the output files are up to date")
    parser.add_argument("--ucd", type=pathlib.Path, default=DEFAULT_UCD, help="the directory of the UCD files")
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT, help="the generated width tables")
    parser.add_argument(
        "--ignorables-output", type=pathlib.Path, default=DEFAULT_IGNORABLES_OUTPUT,
        help="the generated default-ignorable code points")
    parser.add_argument(
        "--diacritics-output", type=pathlib.Path, default=DEFAULT_DIACRITICS_OUTPUT,
        help="the generated diacritics of placeholders")
    args = parser.parse_args(argv)

    try:
        sources = generate(args.ucd)
    except (OSError, UcdError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    outputs = list(zip((args.output, args.ignorables_output, args.diacritics_output), sources))

    if args.check:
        up_to_date = [check(output, source, args.ucd) for output, source in outputs]
        return 0 if all(up_to_date) else 1

    for output, source in outputs:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(source.encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
