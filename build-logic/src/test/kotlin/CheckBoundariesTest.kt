/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Runs `checkBoundaries` of `vtfx.boundaries` in a build of one JVM module. */
class CheckBoundariesTest {
    @TempDir lateinit var root: File

    @Test
    fun checkBoundaries_allChecksRun_succeeds() {
        writeBuild(moduleBuild = "")

        val result = runner().build()

        assertContains(result.output, "BUILD SUCCESSFUL")
    }

    @Test
    fun checkBoundaries_checkDisabled_fails() {
        writeBuild(moduleBuild = """tasks.named("checkClassReferences") { enabled = false }""")

        val result = runner().buildAndFail()

        assertContains(result.output, ":a:checkClassReferences did not run")
    }

    @Test
    fun checkBoundaries_checkSkippedByOnlyIf_fails() {
        writeBuild(moduleBuild = """tasks.named("checkDependencies") { onlyIf { false } }""")

        val result = runner().buildAndFail()

        assertContains(result.output, ":a:checkDependencies did not run")
    }

    @Test
    fun checkBoundaries_checkExcludedFromTheCommandLine_fails() {
        writeBuild(moduleBuild = "")

        val result = runner("-x", ":a:checkLibraryArtifact").buildAndFail()

        assertContains(result.output, ":a:checkLibraryArtifact did not run")
    }

    private fun writeBuild(moduleBuild: String) {
        root.resolve("settings.gradle.kts").writeText("include(\":a\")\n")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"vtfx.boundaries\") }\n")
        root.resolve("config").mkdirs()
        root
            .resolve("config/boundaries.toml")
            .writeText(
                """
                bootstrap_methods = []
                baseline_references = { classes = [], packages = [], types = [], members = [] }

                [modules.a]
                allowed_dependencies = []
                check_classes = false
                """
                    .trimIndent()
            )
        root.resolve("a").mkdirs()
        root.resolve("a/build.gradle.kts").writeText("plugins { java }\n$moduleBuild\n")
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("--offline", "--stacktrace", "checkBoundaries", *arguments)
}
