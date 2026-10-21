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

// Conventions for Android library modules with screenshot tests on the JVM: Robolectric renders
// Compose and Roborazzi compares the images (docs/design/testing.md section 5).

plugins {
    id("vtfx.robolectric")
    id("io.github.takahirom.roborazzi")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

roborazzi {
    // The provisional goldens, owned by agents (docs/design/testing.md section 5).
    outputDir.set(file("src/test/screenshots"))
}

tasks.withType<Test>().configureEach {
    // OfficialGoldensTest compares the provisional goldens with the official goldens that humans
    // promoted to spec/screenshots (docs/design/testing.md section 5). The paths are relative to
    // the working directory of the tests, the module directory, to keep the build cache
    // relocatable.
    inputs
        .dir("../spec/screenshots")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("officialGoldens")
    systemProperty("vtfx.officialGoldensDir", "../spec/screenshots")
    systemProperty("vtfx.provisionalGoldensDir", "src/test/screenshots")
}

dependencies {
    "testImplementation"(libs.findLibrary("roborazzi").get())
    "testImplementation"(libs.findLibrary("roborazzi-compose").get())
    "testImplementation"(libs.findLibrary("roborazzi-junit-rule").get())
    // OfficialGoldensTest takes each official golden as a parameter.
    "testImplementation"(libs.findLibrary("testparameterinjector").get())
    "testImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
}
