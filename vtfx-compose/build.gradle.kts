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

// The public API. Displays the terminal as Compose UI and connects the other modules.
plugins {
    id("vtfx.compose")
    id("vtfx.screenshot-test")
    id("vtfx.device-test")
}

android {
    namespace = "com.google.vtfx.compose"
}

dependencies {
    // The public API uses types of these modules: the app creates the TerminalSession of
    // vtfx-session with the HostConnection of vtfx-gate, and sends KeyStrokes of vtfx-core for
    // auxiliary keys.
    api(project(":vtfx-core"))
    api(project(":vtfx-gate"))
    api(project(":vtfx-session"))
    // Not used by the public API, but nearly every app on Android passes its AndroidClipboard and
    // AndroidUrlOpener to the session, so that an app depends on vtfx-compose alone.
    api(project(":vtfx-gate-android"))
    // TerminalFont.Sanitized takes the SanitizedFonts of vtfx-font, which apps make from the fonts
    // that users choose (docs/decisions/0040).
    api(project(":vtfx-font"))
    // The IME editing model, which has no public API.
    implementation(project(":vtfx-input"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    // Makes Hyperlinks, which only vtfx-core can construct, for the tests.
    testImplementation(testFixtures(project(":vtfx-core")))
}

tasks.withType<Test>().configureEach {
    // The recordings of real apps (tools/recordings), replayed by RecordingScreenshotTest. The path
    // is relative to the working directory of the tests, the module directory.
    inputs
        .dir("../recordings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("recordings")
    systemProperty("vtfx.recordingsDir", "../recordings")
}
