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

// SECURITY REVIEW REQUIRED. The only module that builds bytes for the host and decides on
// requests with external effects.
plugins {
    id("vtfx.fuzz")
}

dependencies {
    implementation(project(":vtfx-core"))
    // Makes TerminalModes, which only vtfx-core can construct, for the tests.
    testImplementation(testFixtures(project(":vtfx-core")))
}

// Writes the classpath of the esctest2 harness (EsctestHost in the tests), which
// scripts/esctest.py runs as the terminal under test. Not part of the check.
val esctestClasspath =
    tasks.register("esctestClasspath") {
        val classpath = sourceSets.test.get().runtimeClasspath
        val output = layout.buildDirectory.file("esctest-classpath.txt")
        inputs.files(classpath)
        outputs.file(output)
        doLast {
            output.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator))
        }
    }
