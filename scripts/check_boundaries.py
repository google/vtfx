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

Exits with status 1 and prints each violation if any module depends on a module it is not allowed
to, or if a restricted API appears outside its allowed paths. This is a regex-based guardrail
against obvious mistakes; see docs/design/architecture.md section 5.

Usage: check_boundaries.py [REPO_ROOT]
"""

import fnmatch
import pathlib
import re
import sys
import tomllib

SOURCE_SUFFIXES = (".kt", ".kts", ".java")
# Calls are found in the whole file text, so that arguments spread across lines are still seen.
INCLUDE_CALL_RE = re.compile(r'\binclude\s*\(')
PROJECT_CALL_RE = re.compile(r'\b(?:project|findProject)\s*\(')
# The arguments of include(...) that the check understands: one or more literal paths.
INCLUDE_ARGS_RE = re.compile(r'\s*"[^"$]+"\s*(?:,\s*"[^"$]+"\s*)*,?\s*')
# The arguments of project(...) that the check understands: a literal path, positional or named
# `path =`, optionally followed by a literal configuration. Anything else (a variable, a string
# template, a nested call) cannot be checked and is rejected.
PROJECT_ARGS_RE = re.compile(
    r'\s*(?:path\s*=\s*)?"(:[^"$]+)"\s*(?:,\s*(?:configuration\s*=\s*)?"[^"$]*"\s*)?,?\s*')
# A string literal in the arguments of a project(...) call that names a project path.
PROJECT_PATH_RE = re.compile(r'"(:[^"]*)"')
# Type-safe project accessors would bypass the project(...) check, so they are not allowed.
PROJECT_ACCESSOR_RE = re.compile(r'\bprojects\s*\.')


def call_arguments(text, open_paren):
    """Returns the text between the parenthesis at open_paren and its matching closing one."""
    depth = 0
    in_string = False
    index = open_paren
    while index < len(text):
        char = text[index]
        if in_string:
            if char == "\\":
                index += 1
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:index]
        index += 1
    return text[open_paren + 1:]


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


def included_modules(root, violations):
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    modules = set()
    for match in INCLUDE_CALL_RE.finditer(settings):
        arguments = call_arguments(settings, match.end() - 1)
        if not INCLUDE_ARGS_RE.fullmatch(arguments):
            violations.append(
                f"settings.gradle.kts:{line_of(settings, match.start())}: "
                "write include(...) with literal module paths only")
        modules.update(path.lstrip(":") for path in re.findall(r'"([^"]+)"', arguments))
    return sorted(modules)


def check_dependencies(root, modules_config):
    violations = []
    for module in included_modules(root, violations):
        if module not in modules_config:
            violations.append(f"settings.gradle.kts: module '{module}' is not declared in config/boundaries.toml")
            continue
        build_file = root / module / "build.gradle.kts"
        if not build_file.exists():
            continue
        allowed = set(modules_config[module]["allowed_dependencies"])
        text = build_file.read_text(encoding="utf-8")
        for match in PROJECT_ACCESSOR_RE.finditer(text):
            violations.append(
                f"{module}/build.gradle.kts:{line_of(text, match.start())}: "
                "use project(\":...\") instead of type-safe project accessors")
        for match in PROJECT_CALL_RE.finditer(text):
            line_number = line_of(text, match.start())
            arguments = call_arguments(text, match.end() - 1)
            if not PROJECT_ARGS_RE.fullmatch(arguments):
                violations.append(
                    f"{module}/build.gradle.kts:{line_number}: write project(...) with a literal module path only")
            for dependency in PROJECT_PATH_RE.findall(arguments):
                name = dependency.lstrip(":")
                if name not in allowed:
                    violations.append(
                        f"{module}/build.gradle.kts:{line_number}: '{module}' must not depend on '{name}'")
    return violations


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
    violations = check_dependencies(root, config["modules"])
    violations += check_restricted_apis(root, config.get("restricted_apis", []))
    for violation in violations:
        print(violation)
    if violations:
        print(f"{len(violations)} boundary violation(s). See config/boundaries.toml.", file=sys.stderr)
        sys.exit(1)
    print("Module boundaries OK.")


if __name__ == "__main__":
    main()
