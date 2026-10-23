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

"""Tests for scripts/check_api_reference.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_api_reference.py"


class CheckApiReferenceTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(textwrap.dedent(content), encoding="utf-8")

    def run_check(self, root=None):
        return subprocess.run(
            [sys.executable, "-I", str(SCRIPT), str(root or self.root)], capture_output=True, text=True
        )

    def test_passes_when_links_exist(self):
        self.write("index.html", """\
            <a href="core/-a/index.html">A</a>
            <a href="core/-a/b.html#anchor">b</a>
            <a href="https://example.com/missing.html">external</a>
            <a href="#top">top</a>
            """)
        self.write("core/-a/index.html", '<a href="../../index.html">up</a>')
        self.write("core/-a/b.html", "")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_fails_on_link_to_missing_page(self):
        self.write("core/-a/index.html", """\
            <p>The largest <a href="../../core/-a/--root--.html"><span>size</span></a>.</p>
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/-a/index.html: broken link [size] to ../../core/-a/--root--.html", result.stdout)

    def test_fails_without_directory(self):
        result = self.run_check(self.root / "missing")
        self.assertEqual(result.returncode, 1)
        self.assertIn("dokkaGenerate", result.stderr)


if __name__ == "__main__":
    unittest.main()
