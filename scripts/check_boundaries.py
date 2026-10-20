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

"""Checks the module boundaries declared in config/boundaries.toml.

Exits with status 1 and prints each violation if a restricted API appears outside its allowed paths.
This is a regex-based guardrail against obvious mistakes; see docs/design/architecture.md section 5.
The dependencies between modules are checked by the build logic (docs/decisions/0044).

Usage: check_boundaries.py [REPO_ROOT]
"""

import fnmatch
import pathlib
import re
import sys
import tomllib

SOURCE_SUFFIXES = (".kt", ".kts", ".java")


def source_files(root):
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        parts = relative.parts
        # Skip build outputs, tool caches, and standalone research tools outside the library.
        if any(part in ("build", ".gradle", ".kotlin", ".git", ".cache") for part in parts):
            continue
        if parts[0] == "tools":
            continue
        if path.is_file() and path.suffix in SOURCE_SUFFIXES:
            yield relative


def check_restricted_apis(root, restricted_apis):
    rules = [(re.compile(rule["pattern"]), rule["reason"], rule["allowed_paths"]) for rule in restricted_apis]
    violations = []
    for relative in source_files(root):
        posix = relative.as_posix()
        # The build configuration is checked by review, not by these source rules.
        if posix.endswith(".gradle.kts") or posix.startswith("build-logic/"):
            continue
        lines = (root / relative).read_text(encoding="utf-8").splitlines()
        for pattern, reason, allowed_paths in rules:
            if any(fnmatch.fnmatchcase(posix, allowed) for allowed in allowed_paths):
                continue
            for line_number, line in enumerate(lines, 1):
                if pattern.search(line):
                    violations.append(f"{posix}:{line_number}: {reason}")
    return violations


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    config = tomllib.loads((root / "config" / "boundaries.toml").read_text(encoding="utf-8"))
    violations = check_restricted_apis(root, config["restricted_apis"])
    for violation in violations:
        print(violation)
    if violations:
        print(f"{len(violations)} boundary violation(s). See config/boundaries.toml.", file=sys.stderr)
        sys.exit(1)
    print("Module boundaries OK.")


if __name__ == "__main__":
    main()
