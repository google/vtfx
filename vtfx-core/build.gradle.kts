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

// Parses host output and maintains the terminal state. Has no means to reach the host.
plugins {
    id("vtfx.fuzz")
    // Shares test helpers, such as the expected failure list of the spec runners, with the
    // modules that depend on vtfx-core.
    `java-test-fixtures`
}

fuzzTarget { className.set("com.google.vtfx.core.fuzz.TerminalFuzzer") }

tasks.test {
    // The spec runner reads the human-owned oracles in spec/ (docs/design/testing.md). They are
    // declared as inputs so that the tests rerun when they change. The path is relative to the
    // working directory of the tests, the module directory, to keep the build cache relocatable.
    inputs.dir("../spec").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("spec")
    systemProperty("vtfx.specDir", "../spec")
    inputs
        .dir("../spec-proposals")
        .optional()
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("specProposals")
    systemProperty("vtfx.specProposalsDir", "../spec-proposals")
    // The recordings of real apps (tools/recordings), replayed by RecordingReplayTest.
    inputs
        .dir("../recordings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("recordings")
    systemProperty("vtfx.recordingsDir", "../recordings")
}

// Measures the throughput of the terminal model (docs/decisions/0089). Not part of the check:
// timing depends on the machine. scripts/benchmark.py records the result.
tasks.register<JavaExec>("benchmark") {
    description = "Measures how fast the terminal model interprets host output."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.google.vtfx.core.benchmark.TerminalBenchmark")
    args(
        file("../recordings").absolutePath,
        providers.gradleProperty("vtfx.benchmarkSeconds").getOrElse("3"),
    )
    outputs.upToDateWhen { false }
}

// Writes the workloads of the benchmark to files, which scripts/benchmark.py pushes to a device for
// the sample app to measure (section 5 of docs/device-checks.md).
tasks.register<JavaExec>("benchmarkWorkloads") {
    description = "Writes the workloads of the throughput benchmark to build/benchmark-workloads."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.google.vtfx.core.benchmark.TerminalBenchmark")
    val directory = layout.buildDirectory.dir("benchmark-workloads")
    args(
        "--write-workloads",
        directory.get().asFile.absolutePath,
        file("../recordings").absolutePath,
    )
    inputs.dir("../recordings").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(directory)
}

// Writes the classpath of the differential test runner (DifferentialHost in the tests), which
// scripts/alacritty_diff.py runs against alacritty_terminal. Not part of the check.
tasks.register("differentialClasspath") {
    val classpath = sourceSets.test.get().runtimeClasspath
    val output = layout.buildDirectory.file("differential-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
