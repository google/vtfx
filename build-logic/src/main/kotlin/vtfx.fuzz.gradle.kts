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

// Conventions for the JVM modules with a fuzz target (docs/design/testing.md section 7,
// docs/decisions/0048).
//
// The fuzz target is a libFuzzer entry point in the fuzz source set: an object with a @JvmStatic
// fuzzerTestOneInput(ByteArray), named by `fuzzTarget` in the module's build file. It depends on
// the module, and on neither JUnit nor Jazzer. Its corpus is src/fuzz/corpus/<target>/, one input
// per file. The test task replays the corpus through the target (the module's corpus test, which
// reads the directory from the system property vtfx.fuzzCorpusDir); the fuzz task fuzzes it with
// Jazzer's driver. check runs the fuzz task briefly, and scripts/fuzz.sh runs it for longer by
// setting -Pvtfx.fuzzDuration.

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("vtfx.jvm-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val fuzzTarget = extensions.create<FuzzTargetExtension>("fuzzTarget")

val sourceSets = extensions.getByType<SourceSetContainer>()
val fuzzSourceSet = sourceSets.create("fuzz")

configurations.named("fuzzImplementation") { extendsFrom(configurations["implementation"]) }

configurations.named("fuzzRuntimeOnly") { extendsFrom(configurations["runtimeOnly"]) }

// The fuzz target sees the module's internal declarations, as the tests do, and the tests see the
// fuzz target and its helpers.
extensions.configure<KotlinJvmProjectExtension> {
    val compilations = target.compilations
    compilations.named("fuzz") { associateWith(compilations.getByName("main")) }
    compilations.named("test") { associateWith(compilations.getByName("fuzz")) }
}

val corpusDirectory = fuzzTarget.className.map { "src/fuzz/corpus/${it.substringAfterLast('.')}" }

tasks.named<Test>("test") {
    // The corpus is declared as an input, so that the replay runs again when it changes. The path
    // is relative to the working directory of the tests, the module directory, to keep the build
    // cache relocatable.
    inputs
        .dir(corpusDirectory)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("fuzzCorpus")
    val corpus = corpusDirectory
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Dvtfx.fuzzCorpusDir=${corpus.get()}") }
    )
}

val jazzer =
    configurations.create("jazzer") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    jazzer(libs.findLibrary("jazzer").get())
}

// The corpus that fuzzing grows, kept between runs. libFuzzer writes new inputs into the first
// corpus directory it is given, so this one comes first and the committed corpus second.
val workingCorpus = layout.buildDirectory.dir("fuzz/corpus")
val reproducers = layout.buildDirectory.dir("fuzz/reproducers")
val fuzzSeconds =
    providers.gradleProperty("vtfx.fuzzDuration").orElse("30s").map { duration ->
        val match =
            Regex("([0-9]+)([smh])").matchEntire(duration)
                ?: throw GradleException(
                    "vtfx.fuzzDuration must be a number of seconds, minutes, or hours, such as " +
                        "30s, 10m, or 1h, not '$duration'"
                )
        val (count, unit) = match.destructured
        count.toLong() *
            when (unit) {
                "s" -> 1
                "m" -> 60
                else -> 3600
            }
    }

// The fuzz tasks of the modules run in parallel with each other and with the other tasks, since
// each one takes the same time whatever else runs (docs/decisions/0028).
val fuzz =
    tasks.register<JavaExec>("fuzz") {
        description = "Fuzzes the fuzz target of this module."
        group = "verification"
        classpath = fuzzSourceSet.runtimeClasspath + jazzer
        mainClass.set("com.code_intelligence.jazzer.Jazzer")
        // Bounds the memory, so that unbounded growth is reported as OutOfMemoryError.
        maxHeapSize = "1g"
        // Jazzer loads its agent into the running JVM, which JDK 21 warns about unless allowed.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        val target = fuzzTarget.className
        val corpus = corpusDirectory
        val working = workingCorpus
        val reproducerDirectory = reproducers
        val seconds = fuzzSeconds
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "--target_class=${target.get()}",
                    // By default, Jazzer instruments only the classes in directories of the
                    // classpath, which leaves out the vtfx modules that this module depends on,
                    // since they come as jars.
                    "--instrumentation_includes=com.google.vtfx.**",
                    "--reproducer_path=${reproducerDirectory.get().asFile}",
                    // An input that makes the target fail is written into the committed corpus,
                    // so that the test task replays it from then on (docs/decisions/0003).
                    "-artifact_prefix=${corpus.get()}/",
                    // libFuzzer reports an input that takes this long as a hang.
                    "-timeout=10",
                    // libFuzzer's limit on the peak resident memory is off: Linux keeps a
                    // process's peak across exec, so the fuzzing JVM starts with the peak of the
                    // Gradle daemon that started it. The heap limit above bounds the memory.
                    "-rss_limit_mb=0",
                    "-max_total_time=${seconds.get()}",
                    working.get().asFile.path,
                    corpus.get(),
                )
            }
        )
        doFirst {
            working.get().asFile.mkdirs()
            reproducerDirectory.get().asFile.mkdirs()
        }
    }

tasks.named("check") {
    dependsOn(fuzz)
}
