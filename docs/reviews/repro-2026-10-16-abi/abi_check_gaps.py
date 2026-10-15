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

"""Runs the review reproductions of ../2026-10-16-abi.md, outside the regular checks.

The findings are about the ABI check (checkKotlinAbi), not about the behavior of the library, so
they are reproduced on a copy of the tree rather than with a Gradle init script: the target commit
is exported to a temporary directory, each case changes one public declaration there, and
checkKotlinAbi is expected to fail. A case fails when the check passes, that is, when the change is
not detected. The repository itself is not modified.

For R1, the script also compiles a small client against the unchanged library and runs that same
client binary against the changed library, to show what the undetected change does to an app that
was not recompiled.

Usage: python3 docs/reviews/repro-2026-10-16-abi/abi_check_gaps.py [COMMIT]

COMMIT defaults to HEAD. Needs what the build needs (JDK 21, the Android SDK in local.properties),
and `java` on the PATH.
"""

import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[3]

# (id, finding, module, file, [(old, new), ...]). Each replacement must match exactly once.
CASES = [
    (
        "default-value-removed",
        "R1",
        "vtfx-session",
        "vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt",
        [("    columns: Int = DEFAULT_COLUMNS,\n", "    columns: Int,\n")],
    ),
    (
        "const-value-changed",
        "R1",
        "vtfx-core",
        "vtfx-core/src/main/kotlin/com/google/vtfx/core/TerminalLimits.kt",
        [
            (
                "public const val MAX_DIMENSION: Int = 2048",
                "public const val MAX_DIMENSION: Int = 1024",
            )
        ],
    ),
    (
        "type-argument-changed",
        "R2",
        "vtfx-gate",
        "vtfx-gate/src/main/kotlin/com/google/vtfx/gate/Capabilities.kt",
        [
            (
                "public val urlSchemes: Set<String> = urlSchemes.mapTo(LinkedHashSet())",
                "public val urlSchemes: Set<CharSequence> = urlSchemes.mapTo(LinkedHashSet())",
            )
        ],
    ),
    (
        "return-made-nullable",
        "R2",
        "vtfx-session",
        "vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt",
        [("    public val title: String\n", "    public val title: String?\n")],
    ),
    (
        "parameter-renamed",
        "R2",
        "vtfx-session",
        "vtfx-session/src/main/kotlin/com/google/vtfx/session/TerminalSession.kt",
        [
            (
                "public fun canOpenUrl(url: String): Boolean = gate.canOpenUrl(url)",
                "public fun canOpenUrl(link: String): Boolean = gate.canOpenUrl(link)",
            )
        ],
    ),
    (
        "fun-interface-removed",
        "R2",
        "vtfx-gate",
        "vtfx-gate/src/main/kotlin/com/google/vtfx/gate/Capabilities.kt",
        [("public fun interface UrlOpener {", "public interface UrlOpener {")],
    ),
    (
        "sealed-removed",
        "R2",
        "vtfx-core",
        "vtfx-core/src/main/kotlin/com/google/vtfx/core/KeyStroke.kt",
        [("public sealed interface KeyStroke {", "public interface KeyStroke {")],
    ),
]

# A change that the check must detect, so that a passing check means a gap and not a broken setup.
CONTROL = (
    "control",
    "-",
    "vtfx-core",
    "vtfx-core/src/main/kotlin/com/google/vtfx/core/TerminalModes.kt",
    [
        (
            "    val mouseEncoding: MouseEncoding = MouseEncoding.Default,\n)",
            "    val mouseEncoding: MouseEncoding = MouseEncoding.Default,\n"
            "    val added: Boolean = false,\n)",
        )
    ],
)

CLIENT_BUILD = """\
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(21) }

dependencies { implementation(project(":vtfx-session")) }

tasks.register<Copy>("copyRuntime") {
    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("runtime"))
}
"""

CLIENT_SOURCE = """\
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.session.TerminalSession

private fun attempt(name: String, block: () -> Unit) {
    try {
        block()
        println("  $name: ok")
    } catch (t: Throwable) {
        println("  $name: $t")
    }
}

fun main() {
    // Leaves columns to its default.
    attempt("default-value-removed") { TerminalSession({ _ -> }) }
    // Uses the largest size that the library allowed when this client was compiled.
    attempt("const-value-changed") {
        TerminalSession({ _ -> }, columns = 80).resize(TerminalLimits.MAX_DIMENSION, 24)
    }
}
"""


def gradle(tree, *tasks):
    return subprocess.run(
        ["./gradlew", "--console=plain", "-q", *tasks], cwd=tree, capture_output=True, text=True
    )


def apply(tree, case):
    path = tree / case[3]
    text = path.read_text()
    original = text
    for old, new in case[4]:
        if text.count(old) != 1:
            raise SystemExit(f"{case[0]}: the text to change is not found once in {case[3]}")
        text = text.replace(old, new)
    path.write_text(text)
    return path, original


def run_case(tree, case):
    """Returns whether checkKotlinAbi detected the change of the case."""
    path, original = apply(tree, case)
    try:
        compiled = gradle(tree, f":{case[2]}:compileKotlin")
        if compiled.returncode != 0:
            raise SystemExit(f"{case[0]}: the changed tree does not compile\n{compiled.stderr}")
        return gradle(tree, f":{case[2]}:checkKotlinAbi").returncode != 0
    finally:
        path.write_text(original)


def run_client(tree, work):
    """Runs a client compiled against the unchanged library against the library with R1's changes."""
    (tree / "client/src/main/kotlin").mkdir(parents=True)
    (tree / "client/build.gradle.kts").write_text(CLIENT_BUILD)
    (tree / "client/src/main/kotlin/Client.kt").write_text(CLIENT_SOURCE)
    with open(tree / "settings.gradle.kts", "a") as settings:
        settings.write('\ninclude(":client")\n')

    def build(into, tasks):
        result = gradle(tree, *tasks)
        if result.returncode != 0:
            raise SystemExit(f"the client build failed\n{result.stderr}")
        shutil.copytree(tree / "client/build/runtime", into)

    build(work / "unchanged", [":client:jar", ":client:copyRuntime"])
    client_jar = work / "client.jar"
    shutil.copy(next((tree / "client/build/libs").glob("*.jar")), client_jar)

    changes = [apply(tree, case) for case in CASES if case[1] == "R1"]
    try:
        # Only the library is rebuilt: the client binary stays the one compiled above.
        build(work / "changed", [":client:copyRuntime"])
    finally:
        for path, original in reversed(changes):
            path.write_text(original)

    for label in ("unchanged", "changed"):
        print(f"The same client binary against the {label} library:")
        classpath = [str(client_jar)] + [str(p) for p in sorted((work / label).glob("*.jar"))]
        result = subprocess.run(
            ["java", "-cp", ":".join(classpath), "ClientKt"], capture_output=True, text=True
        )
        print(result.stdout + result.stderr, end="")


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    with tempfile.TemporaryDirectory(prefix="vtfx-abi-review-") as temp:
        work = pathlib.Path(temp)
        tree = work / "tree"
        tree.mkdir()
        archive = subprocess.run(
            ["git", "archive", commit], cwd=REPO, capture_output=True, check=True
        ).stdout
        subprocess.run(["tar", "-x", "-C", str(tree)], input=archive, check=True)
        if (REPO / "local.properties").exists():
            shutil.copy(REPO / "local.properties", tree / "local.properties")

        if not run_case(tree, CONTROL):
            raise SystemExit("the control change was not detected: the setup is broken")
        print("control: detected, as expected")

        undetected = 0
        for case in CASES:
            detected = run_case(tree, case)
            undetected += not detected
            print(f"{case[1]} {case[0]}: {'detected' if detected else 'NOT DETECTED'}")
        print(f"{len(CASES)} cases, {undetected} not detected")

        run_client(tree, work)
    return 1 if undetected else 0


if __name__ == "__main__":
    sys.exit(main())
