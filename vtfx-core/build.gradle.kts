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
    // The recordings of real apps (tools/recordings), replayed by RecordingReplayTest.
    inputs
        .dir("../recordings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("recordings")
    systemProperty("vtfx.recordingsDir", "../recordings")
}

// Measures the throughput of the terminal model (docs/decisions/0014). Not part of the check:
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
