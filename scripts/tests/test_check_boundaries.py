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

"""Tests for scripts/check_boundaries.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_boundaries.py"

CONFIG = textwrap.dedent('''
    [modules.core]
    allowed_dependencies = []

    [modules.gate]
    allowed_dependencies = ["core"]

    [[restricted_apis]]
    pattern = '\\bClipboardManager\\b'
    reason = "Clipboard access must go through gate."
    allowed_paths = ["gate/**"]
''')


class CheckBoundariesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write("config/boundaries.toml", CONFIG)
        self.write("settings.gradle.kts", 'include(":core")\ninclude(":gate")\n')
        self.write("core/build.gradle.kts", "")
        self.write("gate/build.gradle.kts", 'dependencies { implementation(project(":core")) }\n')

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")

    def run_check(self):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(self.root)], capture_output=True, text=True)

    def test_passes_when_within_boundaries(self):
        self.write("gate/src/main/kotlin/Clip.kt", "import android.content.ClipboardManager\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_disallowed_dependency(self):
        self.write("core/build.gradle.kts", 'dependencies { implementation(project(":gate")) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:1: 'core' must not depend on 'gate'", result.stdout)

    def test_rejects_type_safe_project_accessor(self):
        self.write("core/build.gradle.kts", "dependencies { implementation(projects.gate) }\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("type-safe project accessors", result.stdout)

    def test_rejects_undeclared_module(self):
        self.write("settings.gradle.kts", 'include(":core")\ninclude(":gate")\ninclude(":extra")\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("module 'extra' is not declared", result.stdout)

    def test_rejects_restricted_api_outside_allowed_paths(self):
        self.write("core/src/main/kotlin/Leak.kt", "val x: ClipboardManager? = null\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/src/main/kotlin/Leak.kt:1: Clipboard access must go through gate.", result.stdout)

    def test_ignores_build_outputs(self):
        self.write("core/build/generated/Leak.kt", "val x: ClipboardManager? = null\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_named_path_argument(self):
        self.write("core/build.gradle.kts", 'dependencies { implementation(project(path = ":gate")) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:1: 'core' must not depend on 'gate'", result.stdout)

    def test_rejects_other_named_arguments(self):
        self.write(
            "core/build.gradle.kts",
            'dependencies { implementation(project(configuration = "default", path = ":gate")) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:1: 'core' must not depend on 'gate'", result.stdout)

    def test_rejects_dependency_across_lines(self):
        self.write("core/build.gradle.kts", 'dependencies {\n    implementation(project(\n        ":gate"\n    ))\n}\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:2: 'core' must not depend on 'gate'", result.stdout)

    def test_rejects_find_project(self):
        self.write("core/build.gradle.kts", 'dependencies { implementation(findProject(":gate")!!) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:1: 'core' must not depend on 'gate'", result.stdout)

    def test_rejects_non_literal_project_path(self):
        self.write("core/build.gradle.kts", 'val p = ":gate"\ndependencies { implementation(project(p)) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:2: write project(...) with a literal module path only", result.stdout)

    def test_accepts_literal_path_with_configuration(self):
        self.write(
            "gate/build.gradle.kts",
            'dependencies {\n    implementation(project(path = ":core", configuration = "default"))\n}\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_type_safe_project_accessor_across_lines(self):
        self.write("core/build.gradle.kts", "dependencies {\n    implementation(projects\n        .gate)\n}\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("core/build.gradle.kts:2: use project", result.stdout)

    def test_rejects_undeclared_module_in_multi_argument_include(self):
        self.write("settings.gradle.kts", 'include(\n    ":core",\n    ":gate",\n    ":extra",\n)\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("module 'extra' is not declared", result.stdout)

    def test_rejects_non_literal_include(self):
        self.write("settings.gradle.kts", 'include(":core")\ninclude(":gate")\nlistOf(":extra").forEach { include(it) }\n')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("settings.gradle.kts:3: write include(...) with literal module paths only", result.stdout)


REPO_CONFIG = SCRIPT.parents[1] / "config" / "boundaries.toml"


class RepositoryRestrictedApisTest(unittest.TestCase):
    """Checks the patterns in the repository's config/boundaries.toml against the APIs they must catch."""

    # Each line must be rejected in vtfx-core, and, where an allowed path is given, accepted there.
    CASES = [
        ("val c = LocalClipboardManager.current", "vtfx-gate-android"),
        ("val c = LocalClipboard.current", "vtfx-gate-android"),
        ("c.setClipEntry(ClipEntry(clipData))", "vtfx-gate-android"),
        ("import androidx.compose.ui.platform.ClipboardManager", "vtfx-gate-android"),
        ("LocalUriHandler.current.openUri(url)", "vtfx-gate-android"),
        ("fun open(handler: UriHandler) = Unit", "vtfx-gate-android"),
        ("withLink(LinkAnnotation.Url(url)) { append(text) }", "vtfx-gate-android"),
        ("ShareCompat.IntentBuilder(context).setText(text).startChooser()", "vtfx-gate-android"),
        ("CustomTabsIntent.Builder().build().launchUrl(context, uri)", "vtfx-gate-android"),
        ("import javax.net.ssl.SSLSocketFactory", None),
        ("import javax.net.SocketFactory", None),
        ("context.getSystemService(DownloadManager::class.java)", None),
        ("import java.lang.invoke.MethodHandles", None),
        ("import kotlin.reflect.jvm.isAccessible", None),
        ("import kotlin.reflect.full.declaredMemberProperties", None),
        ("public class AndroidClipboard(context: Context) : ClipboardAccess {", "vtfx-gate-android"),
        ("public class AndroidUrlOpener(private val context: Context) : UrlOpener {", "vtfx-gate-android"),
        ("package com.google.vtfx.font.gate", "vtfx-font-gate"),
        ("package  com.google.vtfx.font.gate.sub", "vtfx-font-gate"),
    ]

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write("config/boundaries.toml", REPO_CONFIG.read_text(encoding="utf-8"))
        self.write("settings.gradle.kts", 'include(":vtfx-core")\ninclude(":vtfx-gate-android")\n')

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")

    def run_check(self):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(self.root)], capture_output=True, text=True)

    def test_rejects_capabilities_under_other_names(self):
        for line, _ in self.CASES:
            with self.subTest(line=line):
                self.write("vtfx-core/src/main/kotlin/Leak.kt", line + "\n")
                result = self.run_check()
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn("vtfx-core/src/main/kotlin/Leak.kt:1:", result.stdout)

    def test_accepts_capabilities_in_allowed_paths(self):
        for line, allowed_module in self.CASES:
            if allowed_module is None:
                continue
            with self.subTest(line=line):
                self.write(f"{allowed_module}/src/main/kotlin/Capability.kt", line + "\n")
                result = self.run_check()
                self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_clipboard_in_session_outside_terminal_session(self):
        self.write("vtfx-session/src/main/kotlin/com/google/vtfx/session/Copy.kt",
                   "fun copy(clipboard: ClipboardAccess, text: String) = clipboard.write(text)\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("vtfx-session/src/main/kotlin/com/google/vtfx/session/Copy.kt:1:", result.stdout)

    def test_rejects_url_opener_in_compose(self):
        self.write("vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Links.kt",
                   "fun open(opener: UrlOpener, url: String) = opener.open(url)\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("vtfx-compose/src/main/kotlin/com/google/vtfx/compose/Links.kt:1:", result.stdout)

    def test_accepts_clipboard_handed_over_by_terminal_session(self):
        self.write("vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt",
                   "    clipboard: ClipboardAccess? = null,\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_accepts_fake_clipboard_in_tests(self):
        self.write("vtfx-compose/src/test/kotlin/com/google/vtfx/compose/TerminalStateTest.kt",
                   "        object : ClipboardAccess {\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_verifier_package_in_font(self):
        self.write("vtfx-font/src/main/kotlin/com/google/vtfx/font/gate/CanonicalVerifier.kt",
                   "package com.google.vtfx.font.gate\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("vtfx-font/src/main/kotlin/com/google/vtfx/font/gate/CanonicalVerifier.kt:1:", result.stdout)

    def test_rejects_verifier_package_in_font_tests(self):
        self.write("vtfx-font/src/test/kotlin/com/google/vtfx/font/gate/Shadow.kt", "package com.google.vtfx.font.gate\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("vtfx-font/src/test/kotlin/com/google/vtfx/font/gate/Shadow.kt:1:", result.stdout)

    def test_accepts_verifier_package_in_font_gate_tests(self):
        self.write("vtfx-font-gate/src/test/kotlin/com/google/vtfx/font/gate/CanonicalVerifierTest.kt",
                   "package com.google.vtfx.font.gate\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_accepts_typefaces_package_in_font_gate_android(self):
        self.write("vtfx-font-gate-android/src/main/kotlin/com/google/vtfx/font/gate/android/SanitizedTypefaces.kt",
                   "package com.google.vtfx.font.gate.android\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_typefaces_package_in_compose(self):
        self.write("vtfx-compose/src/main/kotlin/com/google/vtfx/font/gate/android/SanitizedTypefaces.kt",
                   "package com.google.vtfx.font.gate.android\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("vtfx-compose/src/main/kotlin/com/google/vtfx/font/gate/android/SanitizedTypefaces.kt:1:", result.stdout)

    def test_accepts_shared_font_package_in_font(self):
        self.write("vtfx-font/src/main/kotlin/com/google/vtfx/font/Reader.kt",
                   "package com.google.vtfx.font\n\nimport com.google.vtfx.font.gateway.Other\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
