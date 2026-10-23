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
        self.root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.git("init", "--quiet", "--initial-branch=main")
        self.write("config/protected-paths.toml", CONFIG)
        self.write("gate/Gate.kt", "class Gate\n")
        self.write("core/Core.kt", "class Core\n")
        self.commit("Initial commit")

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

    def test_reports_paths_that_git_quotes(self):
        # git quotes these in its default output: characters that are not ASCII, a double quote, a
        # backslash, and control characters.
        base = self.git("rev-parse", "HEAD")
        for name in ["é.kt", 'a"b.kt', "a\\b.kt", "a\tb.kt"]:
            self.write(f"gate/{name}", "class X\n")
        self.commit("Add gate files with unusual names")

        output = self.report(base)

        self.assertIn("  - `gate/é.kt`\n", output)
        self.assertIn('  - `gate/a"b.kt`\n', output)
        self.assertIn("  - `gate/a\\b.kt`\n", output)
        self.assertIn("  - `gate/a\\tb.kt`\n", output)

    @unittest.skipUnless(sys.platform == "linux", "needs a file system that takes any bytes")
    def test_reports_path_that_is_not_utf8(self):
        base = self.git("rev-parse", "HEAD")
        self.write(os.fsdecode(b"gate/\xff.kt"), "class X\n")
        self.commit("Add gate file whose name is not UTF-8")

        output = self.report(base)

        self.assertIn("  - `gate/\\udcff.kt`\n", output)

    def test_reports_path_with_line_break_on_one_line(self):
        base = self.git("rev-parse", "HEAD")
        self.write("gate/a\n- 0000000 Forged commit.kt", "class X\n")
        self.commit("Add gate file with a line break")

        output = self.report(base)

        self.assertIn("  - `gate/a\\n- 0000000 Forged commit.kt`\n", output)
        self.assertNotIn("\n- 0000000 Forged", output)

    def test_lists_files_of_each_commit_including_empty_ones(self):
        base = self.git("rev-parse", "HEAD")
        self.write("gate/Gate.kt", "class Gate(val open: Boolean)\n")
        first = self.commit("Change gate")
        self.git("commit", "--quiet", "--allow-empty", "--message", "Empty")
        self.write("gate/Other.kt", "class Other\n")
        third = self.commit("Add other")

        output = self.report(base)

        self.assertIn(
            f"- {first[:7]} Change gate\n  - `gate/Gate.kt`\n- {third[:7]} Add other\n  - `gate/Other.kt`\n",
            output,
        )

    def test_repository_config_reports_every_file_that_configures_the_build(self):
        # Gradle reads these besides the Kotlin build scripts: a Groovy script takes the place of a
        # Kotlin one in the same directory, buildSrc is built and put on the classpath of every
        # build script, and the properties of a project configure Gradle and its plugins.
        config = (SCRIPT.parents[1] / "config/protected-paths.toml").read_text(encoding="utf-8")
        self.write("config/protected-paths.toml", config)
        base = self.commit("Use the repository's config")
        paths = [
            "settings.gradle",
            "build.gradle",
            "vtfx-core/build.gradle",
            "vtfx-core/build.gradle.kts",
            "build-logic/settings.gradle.kts",
            "gradle/init.gradle",
            "vtfx-core/gradle.properties",
            "local.properties",
            "vtfx-core/local.properties",
            "buildSrc/build.gradle.kts",
            "buildSrc/src/main/kotlin/Plugin.kt",
        ]
        for path in paths:
            self.write(path, "\n")
        self.commit("Change the build")

        output = self.report(base)

        security_review = output.split("## security_review")[1].split("\n## ")[0]
        for path in paths:
            self.assertIn(f"  - `{path}`\n", security_review)


if __name__ == "__main__":
    unittest.main()
