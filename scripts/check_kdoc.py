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

"""Checks that every public declaration of the library modules has KDoc (docs/REQUIREMENTS.md
section 6.5).

The library modules use Kotlin's explicit API mode, so every public declaration has the `public`
modifier, except for properties declared in the primary constructor of a data class. A declaration
is documented when a KDoc comment ends on the line before it (annotations in between are fine).
A property declared in a primary constructor is also documented by `@property name` in the KDoc
of its class. Dokka shows neither `@param name` nor a mention of `[name]` as the documentation of
the property. Companion objects need no KDoc.

Declarations marked with InternalVtfxApi are not public API (docs/decisions/0016), so they are kept
out of the API reference with `@suppress` in their KDoc. Dokka also hides what they contain, so
only the outermost ones need it: those not inside another marked declaration, as told by
indentation.

The KDoc that the API reference shows does not refer to files of the repository (docs/, scripts/)
or to sections of the requirements, which the readers of the reference cannot open
(docs/decisions/0022). Such references go in comments.

This is a line-based check that relies on the formatting of the sources (one declaration per line,
parameters of a multi-line primary constructor on lines of their own).

Usage: scripts/check_kdoc.py [ROOT]
"""

import pathlib
import re
import sys

# Modules that are not libraries, whose public declarations are not public API.
APP_MODULES = {"vtfx-sample"}

ANNOTATIONS = r"(?:@[\w.]+(?:\([^)]*\))?\s+)*"
PUBLIC_DECLARATION = re.compile(r"^\s*" + ANNOTATIONS + r"public\s+(?P<rest>.*)$")
CLASS_HEADER = re.compile(r"^\s*" + ANNOTATIONS + r"public\s+(?:\w+\s+)*class\s+\w+[^(]*\(")
CONSTRUCTOR_PROPERTY = re.compile(r"(?:^|[(,])\s*" + ANNOTATIONS + r"(?P<public>public\s+)?(?:val|var)\s+(?P<name>\w+)")
ANNOTATION_LINE = re.compile(r"^\s*@")
INTERNAL_API = re.compile(r"@InternalVtfxApi\b")
REPOSITORY_REFERENCE = re.compile(r"\b(?:docs|scripts)/[\w./-]*\w|\brequirements? \d+(?:\.\d+)*")


def annotation_start(lines: list[str], end: int) -> int | None:
    """Returns the first line of the annotation that ends at lines[end], or None if there is none."""
    if ANNOTATION_LINE.match(lines[end]):
        return end
    if paren_balance(lines[end]) >= 0:
        return None
    # The last line of a multi-line annotation: go back to the line that opens it.
    balance = 0
    for k in range(end, -1, -1):
        balance += paren_balance(lines[k])
        if balance == 0:
            return k if ANNOTATION_LINE.match(lines[k]) else None
    return None


def annotations_before(lines: list[str], index: int) -> str:
    """Returns the annotations on the lines right before lines[index]."""
    j = index - 1
    while j >= 0 and (start := annotation_start(lines, j)) is not None:
        j = start - 1
    return "\n".join(lines[j + 1 : index])


def kdoc_before(lines: list[str], index: int) -> str | None:
    """Returns the KDoc that ends right before lines[index], skipping annotations, or None."""
    j = index - 1
    while j >= 0 and (start := annotation_start(lines, j)) is not None:
        j = start - 1
    if j < 0 or not lines[j].rstrip().endswith("*/"):
        return None
    end = j
    while j >= 0 and "/**" not in lines[j]:
        j -= 1
    return "\n".join(lines[j : end + 1]) if j >= 0 else None


def names_property(kdoc: str | None, name: str) -> bool:
    if kdoc is None:
        return False
    return re.search(rf"@property\s+{name}\b", kdoc) is not None


def repository_reference(kdoc: str | None) -> str | None:
    """Returns the first reference to the repository in kdoc, or None."""
    match = REPOSITORY_REFERENCE.search(kdoc or "")
    return match.group(0) if match else None


def paren_balance(line: str) -> int:
    """The number of '(' minus ')' outside string literals and line comments."""
    line = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line.split("//")[0])
    return line.count("(") - line.count(")")


def check_file(path: pathlib.Path, display: str) -> list[str]:
    lines = path.read_text(encoding="utf-8").split("\n")
    problems = []
    constructor_kdoc = None  # The KDoc of the class whose primary constructor is open, if any.
    constructor_hidden = False  # Whether that class is internal API, kept out of the reference.
    constructor_open = False
    depth = 0
    # The indentation of the declarations that enclose the current line, and whether each is
    # internal API, itself or by being inside internal API.
    enclosing: list[tuple[int, bool]] = []
    for i, line in enumerate(lines):
        if constructor_open:
            property_kdoc = kdoc_before(lines, i)
            for match in CONSTRUCTOR_PROPERTY.finditer(line.split("//")[0]) if depth == 1 else []:
                if constructor_hidden:
                    continue
                if property_kdoc is None and not names_property(constructor_kdoc, match.group("name")):
                    problems.append(f"{display}:{i + 1}: property {match.group('name')} has no KDoc")
                if reference := repository_reference(property_kdoc):
                    problems.append(f"{display}:{i + 1}: KDoc refers to the repository: {reference}")
            depth += paren_balance(line)
            constructor_open = depth > 0
            continue

        public = PUBLIC_DECLARATION.match(line)
        kdoc = kdoc_before(lines, i)
        if public and "companion object" not in public.group("rest") and kdoc is None:
            problems.append(f"{display}:{i + 1}: no KDoc: {line.strip()}")

        if public:
            indent = len(line) - len(line.lstrip())
            while enclosing and enclosing[-1][0] >= indent:
                enclosing.pop()
            inside_internal = any(internal for _, internal in enclosing)
            marked = INTERNAL_API.search(annotations_before(lines, i) + "\n" + line.split("public")[0]) is not None
            if marked and not inside_internal and kdoc is not None and "@suppress" not in kdoc:
                problems.append(f"{display}:{i + 1}: internal API without @suppress: {line.strip()}")
            if not marked and not inside_internal and (reference := repository_reference(kdoc)):
                problems.append(f"{display}:{i + 1}: KDoc refers to the repository: {reference}")
            enclosing.append((indent, marked or inside_internal))

        if CLASS_HEADER.match(line):
            header = line[line.index("(") :]
            balance = paren_balance(header)
            hidden = enclosing[-1][1]  # The class is public, so it is the last of enclosing.
            # Properties declared on the header line itself, as in a one-line data class.
            for match in CONSTRUCTOR_PROPERTY.finditer(header.split("//")[0]):
                if not hidden and not names_property(kdoc, match.group("name")):
                    problems.append(f"{display}:{i + 1}: property {match.group('name')} has no KDoc")
            if balance > 0:
                constructor_open = True
                constructor_kdoc = kdoc
                constructor_hidden = hidden
                depth = balance
    return problems


def check(root: pathlib.Path) -> list[str]:
    problems = []
    for module in sorted(root.glob("vtfx-*")):
        if module.name in APP_MODULES:
            continue
        for path in sorted((module / "src" / "main").rglob("*.kt")):
            problems += check_file(path, str(path.relative_to(root)))
    return problems


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)
    problems = check(root)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} problems in the KDoc of the public API.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
