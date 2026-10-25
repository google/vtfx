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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.abi.AbiFilters
import org.jetbrains.kotlin.buildtools.api.abi.AbiValidationToolchain
import org.jetbrains.kotlin.buildtools.api.abi.dumpJvmAbiToStringOperation
import org.jetbrains.kotlin.buildtools.api.abi.operations.filters
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Dumps the public ABI of compiled classes, in the format of KGP's ABI validation, with the Kotlin
 * Build Tools API that KGP uses (docs/decisions/0087).
 *
 * The Android libraries need this: KGP's ABI validation finds no Kotlin compilation in them with
 * AGP's built-in Kotlin. The JVM libraries use KGP's ABI validation.
 */
@CacheableTask
abstract class DumpAbiTask : DefaultTask() {
    /** The directories of the classes to dump. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** The implementation of the Build Tools API, of the same version as the API. */
    @get:Classpath abstract val toolsClasspath: ConfigurableFileCollection

    /** Declarations with these annotations are not dumped. */
    @get:Input abstract val excludedAnnotations: SetProperty<String>

    @get:OutputFile abstract val dump: RegularFileProperty

    @OptIn(ExperimentalBuildToolsApi::class)
    @TaskAction
    fun run() {
        val classFiles =
            classes.asFileTree.matching { include("**/*.class") }.files.sorted().map { it.toPath() }
        val toolchains =
            KotlinToolchains.loadImplementation(toolsClasspath.files.map { it.toPath() })
        val text = StringBuilder()
        val operation =
            toolchains.getToolchain(AbiValidationToolchain::class.java).dumpJvmAbiToStringOperation(
                text,
                classFiles,
            ) {
                filters { set(AbiFilters.EXCLUDE_ANNOTATED_WITH, excludedAnnotations.get()) }
            }
        toolchains.createBuildSession().use { it.executeOperation(operation) }
        dump.get().asFile.writeText(text.toString())
    }
}

/**
 * Dumps the Kotlin signatures of the public API of compiled classes ([KotlinSignatures]), which the
 * ABI dump leaves out. Fails if the public API has a `const val` (docs/decisions/0087).
 */
@CacheableTask
abstract class DumpKotlinSignaturesTask : DefaultTask() {
    /** The directories of the classes to dump. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** Declarations with these annotations are not dumped. */
    @get:Input abstract val excludedAnnotations: SetProperty<String>

    @get:OutputFile abstract val dump: RegularFileProperty

    @TaskAction
    fun run() {
        val files = classes.asFileTree.matching { include("**/*.class") }.files.sorted()
        val result = KotlinSignatures.dump(files.map { it.readBytes() }, excludedAnnotations.get())
        if (result.publicConstants.isNotEmpty()) {
            throw GradleException(
                "Public const vals are copied into apps at compile time, so later versions cannot " +
                    "change them; make them plain vals (docs/decisions/0087): " +
                    result.publicConstants.joinToString()
            )
        }
        dump.get().asFile.writeText(result.text)
    }
}

/** Fails if the dumped ABI differs from the reference dump, which is checked in. */
abstract class CheckAbiTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val actual: RegularFileProperty

    /** The reference dump: one file, which may not exist yet. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reference: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val file = reference.singleFile
        if (!file.exists())
            throw GradleException("No ABI dump at $file. Run the updateKotlinAbi task.")
        if (actual.get().asFile.readText() != file.readText()) {
            throw GradleException(
                "The public ABI differs from $file. If the change is intended, run the updateKotlinAbi task " +
                    "and review the diff (docs/decisions/0087)."
            )
        }
    }
}

/**
 * Registers the dump of the Kotlin signatures of the public API compiled by [compileTask] into
 * `api/<module>.signatures` next to the ABI dump: `updateKotlinAbi` also writes it, and
 * `checkKotlinAbi` and `check` compare it (docs/decisions/0087).
 */
fun Project.registerKotlinSignatures(compileTask: String) {
    val dump =
        tasks.register("dumpKotlinSignatures", DumpKotlinSignaturesTask::class.java) {
            classes.from(
                tasks.named(compileTask, KotlinCompile::class.java).flatMap {
                    it.destinationDirectory
                }
            )
            excludedAnnotations.add("com.google.vtfx.core.InternalVtfxApi")
            dump.set(layout.buildDirectory.file("kotlin/signatures/${project.name}.signatures"))
        }
    val update =
        tasks.register("updateKotlinSignatures", Copy::class.java) {
            description = "Writes the Kotlin signatures of the public API to api/."
            from(dump.flatMap { it.dump })
            into(layout.projectDirectory.dir("api"))
        }
    tasks.named("updateKotlinAbi") { finalizedBy(update) }
    val check =
        tasks.register("checkKotlinSignatures", CheckAbiTask::class.java) {
            description = "Checks the Kotlin signatures of the public API against api/."
            actual.set(dump.flatMap { it.dump })
            reference.from(layout.projectDirectory.file("api/${project.name}.signatures"))
        }
    // Part of the ABI check, so that checkKotlinAbi alone reports these changes too.
    tasks.named("checkKotlinAbi") { dependsOn(check) }
    tasks.named("check") { dependsOn(check) }
}
