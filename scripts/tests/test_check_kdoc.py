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

"""Tests for scripts/check_kdoc.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_kdoc.py"


class CheckKdocTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(textwrap.dedent(content), encoding="utf-8")

    def run_check(self):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(self.root)], capture_output=True, text=True)

    def test_passes_when_documented(self):
        self.write("vtfx-core/src/main/kotlin/A.kt", """\
            /** A thing. */
            @Immutable
            public class A {
                /** Does it. */
                @Suppress("x")
                public fun run() {}

                public companion object {
                    /** The limit. */
                    public const val LIMIT: Int = 1
                }

                private fun hidden() {}
                internal val other = 1
            }

            /** An annotation. */
            @RequiresOptIn(
                message = "(x)",
                level = RequiresOptIn.Level.ERROR,
            )
            @Target(AnnotationTarget.CLASS)
            public annotation class B
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_reports_public_declaration_without_kdoc(self):
        self.write("vtfx-core/src/main/kotlin/A.kt", """\
            /** A thing. */
            public class A {
                // Not KDoc.
                public fun run() {}
            }
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("vtfx-core/src/main/kotlin/A.kt:4: no KDoc: public fun run() {}", result.stdout)

    def test_constructor_properties_are_documented_by_the_class_or_themselves(self):
        self.write("vtfx-core/src/main/kotlin/A.kt", """\
            /**
             * A thing at [x].
             *
             * @property y The other.
             * @param w The parameter.
             */
            public data class A(
                val x: Int,
                val y: Int,
                /** The third. */
                public val z: Int = f(1, 2),
                val w: Int,
                private val hidden: Int,
            )

            /**
             * A link to [uri].
             *
             * @property id The id.
             */
            public data class B(val uri: String, val id: String?)

            internal class C(val undocumented: Int)

            /**
             * Hidden, with its properties.
             *
             * @suppress
             */
            @InternalVtfxApi
            public data class D(val undocumented: Int)
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        # Dokka shows only @property as the documentation of the property, not @param or a link.
        self.assertEqual(
            result.stdout.splitlines(),
            [
                "vtfx-core/src/main/kotlin/A.kt:8: property x has no KDoc",
                "vtfx-core/src/main/kotlin/A.kt:12: property w has no KDoc",
                "vtfx-core/src/main/kotlin/A.kt:21: property uri has no KDoc",
            ],
        )

    def test_reference_kdoc_does_not_refer_to_the_repository(self):
        self.write("vtfx-core/src/main/kotlin/A.kt", """\
            // See docs/decisions/0001.
            /** A thing (docs/decisions/0001-x.md). */
            public class A(
                /** The size (requirement 6.1). */
                public val size: Int,
                /** Internal (docs/design/x.md). */
                internal val other: Int,
            ) {
                /** Made by scripts/gen.py. */
                public fun run() {}

                /** Not in the reference (requirement 5.4). */
                internal fun hidden() {}
            }

            /**
             * Hidden (docs/x.md).
             *
             * @suppress
             */
            @InternalVtfxApi
            public class B {
                /** Inside hidden API (docs/y.md). */
                public fun run() {}
            }
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(
            result.stdout.splitlines(),
            [
                "vtfx-core/src/main/kotlin/A.kt:3: KDoc refers to the repository: docs/decisions/0001-x.md",
                "vtfx-core/src/main/kotlin/A.kt:5: KDoc refers to the repository: requirement 6.1",
                "vtfx-core/src/main/kotlin/A.kt:10: KDoc refers to the repository: scripts/gen.py",
            ],
        )

    def test_internal_api_is_suppressed_from_the_reference(self):
        self.write("vtfx-core/src/main/kotlin/A.kt", """\
            /**
             * Hidden.
             *
             * @suppress
             */
            @InternalVtfxApi
            public sealed interface Event {
                /** Hidden with its parent. */
                @InternalVtfxApi
                public data object Bell : Event
            }

            /** Shown. */
            public class Writer {
                /**
                 * Hidden. @suppress
                 */
                @InternalVtfxApi
                public fun send() {}

                /** Not hidden. */
                @InternalVtfxApi
                public fun leak() {}
            }

            /** Not hidden. */
            @Suppress("x")
            @InternalVtfxApi public class Other
            """)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(
            result.stdout.splitlines(),
            [
                "vtfx-core/src/main/kotlin/A.kt:23: internal API without @suppress: public fun leak() {}",
                "vtfx-core/src/main/kotlin/A.kt:28: internal API without @suppress: @InternalVtfxApi public class Other",
            ],
        )

    def test_ignores_app_modules_and_tests(self):
        self.write("vtfx-sample/src/main/kotlin/A.kt", "public class A\n")
        self.write("vtfx-core/src/test/kotlin/ATest.kt", "public class ATest\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
