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

// Conventions for the JVM modules with a Jazzer fuzz test (docs/design/testing.md section 7).
//
// The plain test task only replays the corpus. The fuzz task fuzzes each @FuzzTest; check runs it
// briefly, and scripts/fuzz.sh runs it for longer by setting -Pvtfx.fuzzDuration.

plugins {
    id("vtfx.jvm-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("jazzer-junit").get())
}

val sourceSets = extensions.getByType<SourceSetContainer>()

// The fuzz tasks of the modules run in parallel with each other and with the other tasks, since
// each one takes the same time whatever else runs (docs/decisions/0028).
val fuzz =
    tasks.register<Test>("fuzz") {
        description = "Fuzzes the fuzz tests of this module."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        filter.includeTestsMatching("*FuzzTest")
        environment("JAZZER_FUZZ", "1")
        systemProperty(
            "jazzer.max_duration",
            providers.gradleProperty("vtfx.fuzzDuration").getOrElse("30s"),
        )
        // By default, Jazzer instruments only the classes in directories of the classpath, which
        // leaves out the vtfx modules that this module depends on, since they come as jars.
        systemProperty("jazzer.instrumentation_includes", "com.google.vtfx.**")
        // Jazzer passes this to libFuzzer, which reports an input that takes this long as a hang.
        // JUnit's own enforcement is disabled, since it would apply to the whole fuzzing run.
        systemProperty("junit.jupiter.execution.timeout.default", "10 s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
        // Bounds the memory, so that unbounded growth is reported as OutOfMemoryError.
        maxHeapSize = "1g"
        // Fuzzing explores different inputs on every run, so a result must never be reused.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }

tasks.named("check") {
    dependsOn(fuzz)
}
