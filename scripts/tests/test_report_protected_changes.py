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

"""Tests for scripts/report_protected_changes.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import os
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "report_protected_changes.py"

CONFIG = textwrap.dedent('''\
    [security_review]
    description = "Gate code."
    paths = ["gate/**"]
''')

# Keeps the user's and the system's git configuration (renames, signing, hooks) out of the tests.
GIT_ENV = {
    **os.environ,
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@example.com",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@example.com",
}


class ReportProtectedChangesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.git("init", "--quiet", "--initial-branch=main")
        self.write("config/protected-paths.toml", CONFIG)
        self.write("gate/Gate.kt", "class Gate\n")
        self.write("core/Core.kt", "class Core\n")
        self.commit("Initial commit")

    def tearDown(self):
        self._tmp.cleanup()

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, env=GIT_ENV, capture_output=True, text=True, check=True
        ).stdout.strip()

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.git("add", "--all")
        self.git("commit", "--quiet", "--message", message)
        return self.git("rev-parse", "HEAD")

    def report(self, base):
        result = subprocess.run(
            [sys.executable, "-I", str(SCRIPT), base, "HEAD"],
            cwd=self.root,
            env=GIT_ENV,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_reports_no_changes_when_only_unprotected_paths_change(self):
        base = self.git("rev-parse", "HEAD")
        self.write("core/Core.kt", "class Core(val x: Int)\n")
        self.commit("Change core")

        output = self.report(base)

        self.assertIn("## security_review\n\nGate code.\n\nNo changes.\n", output)

    def test_reports_file_moved_out_of_protected_path(self):
        base = self.git("rev-parse", "HEAD")
        self.git("mv", "gate/Gate.kt", "core/Gate.kt")
        self.commit("Move Gate to core")

        output = self.report(base)

        self.assertIn("  - `gate/Gate.kt`\n", output)

    def test_reports_protected_change_made_in_merge_commit(self):
        base = self.git("rev-parse", "HEAD")
        self.git("checkout", "--quiet", "-b", "side")
        self.write("core/Core.kt", "class Core(val x: Int)\n")
        self.commit("Change core on side")
        self.git("checkout", "--quiet", "main")
        self.write("core/Other.kt", "class Other\n")
        self.commit("Add Other on main")
        self.git("merge", "--quiet", "--no-ff", "--no-commit", "side")
        self.write("gate/Gate.kt", "class Gate(val open: Boolean)\n")
        self.commit("Merge side")

        output = self.report(base)

        self.assertIn("  - `gate/Gate.kt`\n", output)

    def test_reports_change_under_pattern_removed_in_same_range(self):
        base = self.git("rev-parse", "HEAD")
        self.write("config/protected-paths.toml", CONFIG.replace('"gate/**"', '"none/**"'))
        self.write("gate/Gate.kt", "class Gate(val open: Boolean)\n")
        self.commit("Drop gate from config and change it")

        output = self.report(base)

        self.assertIn("  - `gate/Gate.kt`\n", output)

    def test_reports_change_under_pattern_added_in_same_range(self):
        self.write("config/protected-paths.toml", CONFIG.replace('"gate/**"', '"none/**"'))
        base = self.commit("Drop gate from config")
        self.write("config/protected-paths.toml", CONFIG)
        self.write("gate/Gate.kt", "class Gate(val open: Boolean)\n")
        self.commit("Add gate to config and change it")

        output = self.report(base)

        self.assertIn("  - `gate/Gate.kt`\n", output)


if __name__ == "__main__":
    unittest.main()
