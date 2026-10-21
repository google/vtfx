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

// Reads fonts that users choose and writes them again in one canonical form (docs/decisions/0040).
// What reaches native code is decided by the verifier of vtfx-font-gate, so this module is not
// under
// security review (docs/decisions/0041).
plugins {
    id("vtfx.fuzz")
}

fuzzTarget { className.set("com.google.vtfx.font.FontFuzzer") }

dependencies {
    // The public API returns SanitizedFont and takes FontLimits.
    api(project(":vtfx-font-gate"))
}

// CanonicalFixturesTest checks that this module writes the canonical fonts that vtfx-font-gate's
// tests check the verifier with. It reads them as files: those tests cannot depend on this module
// (config/boundaries.toml), and the files are inputs, so that changing one runs the test again.
tasks.named<Test>("test") {
    val fixtures =
        layout.settingsDirectory.dir(
            "vtfx-font-gate/src/fuzz/resources/com/google/vtfx/font/gate/canonical"
        )
    inputs
        .dir(fixtures)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("canonicalFixtures")
    systemProperty("vtfx.canonicalFixtures", fixtures.asFile.absolutePath)
    systemProperty(
        "vtfx.canonicalFixturesActual",
        layout.buildDirectory.dir("canonical-fixtures").get().asFile.absolutePath,
    )
}
