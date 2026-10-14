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
Always exits with status 0 unless git fails: the report is for human review and later audit, not a
gate.

Usage: report_protected_changes.py BASE [HEAD]
"""

import fnmatch
import pathlib
import subprocess
import sys
import tomllib


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def commits_with_files(base, head):
    """Returns [(commit, subject, [path, ...]), ...] for BASE..HEAD, oldest first."""
    output = git("log", "--reverse", "--name-only", "--format=%x00%h %s", f"{base}..{head}")
    commits = []
    for chunk in output.split("\0")[1:]:
        lines = [line for line in chunk.splitlines() if line]
        commit, _, subject = lines[0].partition(" ")
        commits.append((commit, subject, lines[1:]))
    return commits


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    base = sys.argv[1]
    head = sys.argv[2] if len(sys.argv) == 3 else "HEAD"
    root = pathlib.Path(git("rev-parse", "--show-toplevel").strip())
    config = tomllib.loads((root / "config" / "protected-paths.toml").read_text(encoding="utf-8"))
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
                    print(f"  - `{path}`")
        if not found:
            print("No changes.")


if __name__ == "__main__":
    main()
