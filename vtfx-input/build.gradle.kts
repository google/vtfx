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

// Converts key input and IME edits into input for the host.
plugins {
    id("vtfx.fuzz")
}

dependencies {
    implementation(project(":vtfx-core"))
}

dependencies {
    testImplementation(testFixtures(project(":vtfx-core")))
}

tasks.test {
    // The IME spec runner reads the human-owned oracles in spec/ime and the traces they refer to
    // (docs/design/testing.md section 4). They are declared as inputs so that the tests rerun when
    // they change. The path is relative to the working directory of the tests, the module
    // directory, to keep the build cache relocatable.
    inputs.dir("../spec").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("spec")
    inputs
        .dir("../tools/ime-probe/traces")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("traces")
    systemProperty("vtfx.repoRoot", "..")
}
