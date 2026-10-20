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

"""Checks that the references to the documents point at files that exist.

Checked:
- every `docs/...` path written in the files of the repository (code comments, scripts,
  configuration, documents), where `docs/decisions/NNNN` without the rest of the file name stands
  for the decision with that number;
- every relative link in the Markdown files;
- that a decision whose status says it is superseded links to the decision that supersedes it.

References to sections ("section 5 of testing.md") are not checked, which is why the documents keep
their section numbers (AGENTS.md).

The human-owned oracles, third-party files, and the fixtures of the script tests are not scanned.

Usage: scripts/check_doc_links.py [ROOT]
"""

import pathlib
import re
import subprocess
import sys

# Not scanned: oracles and third-party files that agents do not edit, and test fixtures that
# contain made-up paths on purpose.
EXCLUDED_PREFIXES = ("spec/", "tools/ime-probe/traces/", "third_party/", "scripts/tests/")
SCANNED_SUFFIXES = {".md", ".kt", ".kts", ".py", ".sh", ".toml", ".yml", ".yaml", ".properties", ".xml"}

# A path under docs/ that is not part of a longer path or a URL. It may hold a glob (`ime-*.md`) or a
# placeholder (`repro-<date>/`, `NNNN-<name>.md`).
DOCS_PATH = re.compile(r"(?<![\w/.-])docs/[\w./*<>-]*[\w/*>]")
PLACEHOLDER = re.compile(r"<|\b[A-Z]{2,}\b")
DECISION_NUMBER = re.compile(r"^docs/decisions/(\d{4})$")
# An inline Markdown link or image: [text](target) or [text](target "title").
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^\s*(```|~~~)")
STATUS_LINE = re.compile(r"^- Status:(.*)$", re.MULTILINE)
DECISION_LINK = re.compile(r"\]\((?:\.\./decisions/|\./)?\d{4}-[\w.-]+\.md\)")


def tracked_files(root: pathlib.Path) -> list[str]:
    """Returns the files of the repository, tracked or new, relative to root."""
    output = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    return sorted({path for path in output.split("\0") if path and (root / path).is_file()})


def decision_exists(root: pathlib.Path, number: str) -> bool:
    return any((root / "docs" / "decisions").glob(f"{number}-*.md"))


def path_exists(directory: pathlib.Path, path: str) -> bool:
    """Whether path exists under directory. For a path with a placeholder, whether the directory
    before the placeholder exists; for a glob, whether it matches a file."""
    if placeholder := PLACEHOLDER.search(path):
        before = path[: placeholder.start()]
        return (directory / before[: before.rfind("/") + 1]).is_dir()
    if "*" in path:
        return any(directory.glob(path))
    return (directory / path).exists()


def docs_path_exists(root: pathlib.Path, path: str) -> bool:
    path = path.rstrip("/.")
    if match := DECISION_NUMBER.match(path):
        return decision_exists(root, match.group(1))
    return path_exists(root, path)


def markdown_lines(text: str):
    """Yields (line number, line) for the lines of Markdown text outside fenced code blocks."""
    in_fence = False
    for number, line in enumerate(text.splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence:
            yield number, line


def without_code_spans(line: str) -> str:
    return re.sub(r"`[^`]*`", "", line)


def check_docs_paths(root: pathlib.Path, path: str, text: str) -> list[str]:
    problems = []
    for number, line in enumerate(text.splitlines(), start=1):
        for match in DOCS_PATH.finditer(line):
            if not docs_path_exists(root, match.group(0)):
                problems.append(f"{path}:{number}: {match.group(0)} does not exist")
    return problems


def check_markdown_links(root: pathlib.Path, path: str, text: str) -> list[str]:
    problems = []
    directory = (root / path).parent
    for number, line in markdown_lines(text):
        for match in MARKDOWN_LINK.finditer(without_code_spans(line)):
            target = match.group(1)
            if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                continue
            file = target.split("#", 1)[0]
            if not path_exists(directory, file):
                problems.append(f"{path}:{number}: the link target {target} does not exist")
    return problems


def check_superseded(path: str, text: str) -> list[str]:
    problems = []
    for match in STATUS_LINE.finditer(text):
        status = match.group(1)
        if "uperseded" in status and not DECISION_LINK.search(status):
            problems.append(f"{path}: the status says it is superseded but links to no decision")
    return problems


def check(root: pathlib.Path) -> list[str]:
    problems = []
    for path in tracked_files(root):
        if path.startswith(EXCLUDED_PREFIXES) or pathlib.PurePosixPath(path).suffix not in SCANNED_SUFFIXES:
            continue
        text = (root / path).read_text(encoding="utf-8")
        problems += check_docs_paths(root, path, text)
        if path.endswith(".md"):
            problems += check_markdown_links(root, path, text)
        if re.match(r"^docs/decisions/\d{4}-", path):
            problems += check_superseded(path, text)
    return problems


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)
    problems = check(root)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} broken references to documents.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
