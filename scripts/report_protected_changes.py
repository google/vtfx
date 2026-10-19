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

"""Reports changes to the paths listed in config/protected-paths.toml.

Prints a Markdown report of the commits in BASE..HEAD that touch each category of protected paths.
The paths of each category are the union of those in config/protected-paths.toml at BASE and at
HEAD, so that a range that drops a pattern and changes the code under it is still reported.
Always exits with status 0 unless git fails: the report is for human review and later audit, not a
gate.

Usage: report_protected_changes.py BASE [HEAD]
"""

import fnmatch
import subprocess
import sys
import tomllib


def git(*args):
    # Paths are bytes to git: one that is not UTF-8 is kept as surrogates rather than failing.
    return subprocess.run(
        ["git", *args], capture_output=True, encoding="utf-8", errors="surrogateescape", check=True
    ).stdout


def commits_with_files(base, head):
    """Returns [(commit, subject, [path, ...]), ...] for BASE..HEAD, oldest first.

    A rename lists both its old and new paths, so that moving a file out of a protected path is
    reported. A merge commit lists the paths it changes from its first parent, which include any
    change made in the merge itself. The paths are read NUL-separated (-z): by default git quotes
    paths with characters that are not ASCII, a double quote, a backslash, or control characters,
    and quoted paths would match no pattern.
    """
    output = git(
        "log",
        "--reverse",
        "-z",
        "--name-only",
        "--no-renames",
        "--diff-merges=first-parent",
        "--format=%x00%h %s",
        f"{base}..{head}",
    )
    # Each commit is "\0<header>\0", followed, if it changes any path, by "\n<path>\0<path>\0...":
    # every field ends in NUL. A subject has neither NUL nor line breaks, and a path is never empty,
    # so the empty field that the format's leading NUL leaves marks the start of each commit.
    if output == "":
        return []
    if not output.endswith("\0"):
        raise ValueError(f"unexpected output of git log: {output!r}")
    fields = output[:-1].split("\0")
    commits = []
    index = 0
    while index < len(fields):
        if fields[index] != "":
            raise ValueError(f"unexpected output of git log: {output!r}")
        commit, _, subject = fields[index + 1].partition(" ")
        index += 2
        paths = []
        while index < len(fields) and fields[index] != "":
            field = fields[index]
            if not paths:
                if not field.startswith("\n"):
                    raise ValueError(f"unexpected output of git log: {output!r}")
                field = field[1:]
            paths.append(field)
            index += 1
        commits.append((commit, subject, paths))
    return commits


def printable(path):
    """Returns PATH with control characters escaped, so that each path stays on its line."""
    return "".join(c if c.isprintable() else repr(c)[1:-1] for c in path)


def read_config(base, head):
    """Returns {category: {"description": ..., "paths": [...]}} from BASE and HEAD, HEAD first."""
    merged = {}
    for rev in (head, base):
        config = tomllib.loads(git("show", f"{rev}:config/protected-paths.toml"))
        for category, settings in config.items():
            entry = merged.setdefault(category, {"description": settings["description"], "paths": []})
            entry["paths"] += [path for path in settings["paths"] if path not in entry["paths"]]
    return merged


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    base = sys.argv[1]
    head = sys.argv[2] if len(sys.argv) == 3 else "HEAD"
    config = read_config(base, head)
    commits = commits_with_files(base, head)

    print(f"# Changes to protected paths in {base}..{head}")
    for category, settings in config.items():
        print(f"\n## {category}\n\n{settings['description']}\n")
        found = False
        for commit, subject, paths in commits:
            matched = [p for p in paths if any(fnmatch.fnmatchcase(p, pattern) for pattern in settings["paths"])]
            if matched:
                found = True
                print(f"- {commit} {subject}")
                for path in matched:
                    print(f"  - `{printable(path)}`")
        if not found:
            print("No changes.")


if __name__ == "__main__":
    main()
