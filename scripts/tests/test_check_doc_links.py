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

"""Tests for scripts/check_doc_links.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_doc_links.py"


class CheckDocLinksTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        self.write("docs/design/testing.md", "# Test strategy\n")
        self.write("docs/decisions/0001-parser.md", "# Parser\n\n- Status: Approved\n")

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(textwrap.dedent(content), encoding="utf-8")

    def run_check(self):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(self.root)], capture_output=True, text=True)

    def assert_passes(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def assert_fails_with(self, *messages):
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        for message in messages:
            self.assertIn(message, result.stdout)

    def test_passes_when_paths_exist(self):
        self.write("Foo.kt", """\
            // See docs/design/testing.md section 5 and docs/decisions/0001.
            // docs/decisions/0001-parser.md, docs/design/, and docs/decisions/*.md.
            """)
        self.assert_passes()

    def test_fails_on_a_missing_path_in_code(self):
        self.write("Foo.kt", "// See docs/design/missing.md section 5.\n")
        self.assert_fails_with("Foo.kt:1: docs/design/missing.md does not exist")

    def test_fails_on_a_missing_decision_number(self):
        self.write("build.gradle.kts", "// The boundaries (docs/decisions/0044).\n")
        self.assert_fails_with("build.gradle.kts:1: docs/decisions/0044 does not exist")

    def test_fails_on_a_glob_that_matches_nothing(self):
        self.write("AGENTS.md", "Findings are in `docs/research/ime-*.md`.\n")
        self.assert_fails_with("docs/research/ime-*.md does not exist")

    def test_checks_only_the_directory_before_a_placeholder(self):
        self.write("a.md", "Reports go in `docs/design/repro-<date>/`, decisions in `docs/decisions/NNNN-<name>.md`.\n")
        self.assert_passes()
        self.write("b.md", "Reports go in `docs/reviews/repro-<date>/`.\n")
        self.assert_fails_with("b.md:1: docs/reviews/repro-<date>/ does not exist")

    def test_ignores_paths_inside_urls_and_longer_paths(self):
        self.write("a.md", "https://github.com/google/x/blob/master/docs/code-of-conduct.md and build/docs/x.md\n")
        self.assert_passes()

    def test_checks_relative_markdown_links(self):
        self.write("docs/design/architecture.md", """\
            See [testing](testing.md#5-screenshot-tests), [0001](../decisions/0001-parser.md),
            [the web](https://example.com), and [above](#top).
            """)
        self.assert_passes()
        self.write("docs/design/ime-contract.md", "See [gone](../decisions/0002-limits.md).\n")
        self.assert_fails_with("docs/design/ime-contract.md:1: the link target ../decisions/0002-limits.md does not exist")

    def test_ignores_links_in_code(self):
        self.write("docs/a.md", """\
            Write `[x](missing.md)` like this:

            ```markdown
            [y](missing.md)
            ```
            """)
        self.assert_passes()

    def test_superseded_decision_links_to_its_successor(self):
        self.write("docs/decisions/0002-limits.md", "# Limits\n\n- Status: Superseded by [0001](0001-parser.md)\n")
        self.assert_passes()
        self.write("docs/decisions/0003-fuzzing.md", "# Fuzzing\n\n- Status: Superseded by 0001\n")
        self.assert_fails_with("docs/decisions/0003-fuzzing.md: the status says it is superseded")

    def test_skips_oracles_and_test_fixtures(self):
        self.write("spec/core/a.vtspec.md", "docs/missing.md\n")
        self.write("scripts/tests/test_x.py", "# docs/missing.md\n")
        self.assert_passes()


if __name__ == "__main__":
    unittest.main()
