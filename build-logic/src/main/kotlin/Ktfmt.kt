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

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

/**
 * Runs ktfmt in the style of the Kotlin coding conventions on Kotlin sources (docs/decisions/0023).
 */
abstract class KtfmtTask : DefaultTask() {
    /** The Kotlin sources to format or check. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** ktfmt and its dependencies. */
    @get:Classpath abstract val ktfmtClasspath: ConfigurableFileCollection

    @get:Inject protected abstract val execOperations: ExecOperations

    /** Runs ktfmt with [options] on the sources, and returns its result whatever the exit value. */
    protected fun runKtfmt(vararg options: String): ExecResult = execOperations.javaexec {
        classpath = ktfmtClasspath
        mainClass.set("com.facebook.ktfmt.cli.Main")
        args("--kotlinlang-style", *options)
        args(sources.files.sorted())
        isIgnoreExitValue = true
    }
}

/** Formats the sources in place. */
abstract class FormatKotlinTask : KtfmtTask() {
    @TaskAction
    fun run() {
        runKtfmt("--quiet").assertNormalExitValue()
    }
}

/** Fails if formatting would change any of the sources, which ktfmt lists. */
@CacheableTask
abstract class CheckKotlinFormatTask : KtfmtTask() {
    /** Written when the check passes, so that the check is skipped until the sources change. */
    @get:OutputFile abstract val marker: RegularFileProperty

    @TaskAction
    fun run() {
        if (runKtfmt("--dry-run", "--set-exit-if-changed").exitValue != 0) {
            throw GradleException(
                "ktfmt failed, or would change the Kotlin sources listed above. Run the formatKotlin task " +
                    "and review the diff (docs/decisions/0023)."
            )
        }
        marker.get().asFile.writeText("")
    }
}
