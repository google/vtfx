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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

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

    @Test
    fun checkBoundaries_classAtTheSamePathInTwoDirectories_fails() {
        // The check of classes would find the first, and the artifact check took the last.
        writeBuild(
            moduleBuild =
                """
                tasks.named<CheckClassReferencesTask>("checkClassReferences") {
                    classDirectories.setFrom("extra", sourceSets.main.map { it.output.classesDirs })
                }
                tasks.named<CheckLibraryArtifactTask>("checkLibraryArtifact") {
                    classDirectories.setFrom("extra", sourceSets.main.map { it.output.classesDirs })
                }
                """
        )
        root.resolve("a/src/main/java/a").mkdirs()
        root.resolve("a/src/main/java/a/A.java").writeText("package a;\n\npublic class A {}\n")
        root.resolve("a/extra/a").mkdirs()
        root.resolve("a/extra/a/A.class").writeBytes(classFile("a/A"))

        val result = runner().buildAndFail()

        assertContains(result.output, "a/A.class is in more than one of")
    }

    @Test
    fun checkBoundaries_classUnderMetaInfOfVariantJar_fails() {
        // The check of classes skips the class, so the artifact must not ship it.
        val path = "META-INF/versions/21/a/B.class"
        writeBuild(
            moduleBuild =
                """
                val variantJar = layout.projectDirectory.file("variant.jar")
                tasks.named<CheckClassReferencesTask>("checkClassReferences") {
                    variantJars.add(variantJar)
                }
                tasks.named<CheckLibraryArtifactTask>("checkLibraryArtifact") {
                    variantJars.add(variantJar)
                }
                """
        )
        root.resolve("a/src/main/resources/META-INF/versions/21/a").mkdirs()
        root.resolve("a/src/main/resources/$path").writeBytes(classFile("a/B"))
        ZipOutputStream(root.resolve("a/variant.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(classFile("a/B"))
            zip.closeEntry()
        }

        val result = runner().buildAndFail()

        assertContains(result.output, "$path is not a class that the check of classes read")
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

    /** A class file of [name], extending Object, that javac would not write. */
    private fun classFile(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}
