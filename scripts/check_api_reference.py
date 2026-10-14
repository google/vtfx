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

"""Checks that the links in the HTML API reference lead to pages that exist.

Dokka renders a KDoc link that it resolves to a page it does not generate as a broken link, not as
a warning: a link to a declaration hidden with `@suppress`, or an unqualified link to a property
declared in a primary constructor, which Dokka 2.2 resolves to the constructor parameter
(docs/decisions/0016). Run this after `./gradlew :dokkaGenerate`.

Usage: scripts/check_api_reference.py [DIR]

DIR defaults to build/dokka/html.
"""

import html
import pathlib
import re
import sys

HREF = re.compile(r'<a\s[^>]*?href="(?P<href>[^"]*)"[^>]*>(?P<text>.*?)</a>', re.S)
TAG = re.compile(r"<[^>]+>")


def check(root: pathlib.Path) -> list[str]:
    problems = []
    for page in sorted(root.rglob("*.html")):
        source = page.read_text(encoding="utf-8")
        for match in HREF.finditer(source):
            target = html.unescape(match.group("href")).split("#")[0].split("?")[0]
            if not target or re.match(r"^[a-z][a-z0-9+.-]*:", target):
                continue
            if not (page.parent / target).resolve().exists():
                text = html.unescape(TAG.sub("", match.group("text"))).strip()
                problems.append(f"{page.relative_to(root)}: broken link [{text}] to {target}")
    return problems


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build/dokka/html")
    if not root.is_dir():
        print(f"{root} not found; run ./gradlew :dokkaGenerate first.", file=sys.stderr)
        return 1
    problems = check(root)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} broken links in the API reference.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
