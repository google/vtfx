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

// A sample app that shows a terminal connected to a pseudo host: an echoing line editor, or a
// replay of recorded output. The emulator end-to-end tests drive it (scripts/e2e_ime.py).
plugins {
    id("vtfx.android-application")
}

android {
    namespace = "com.google.vtfx.sample"

    defaultConfig {
        applicationId = "com.google.vtfx.sample"
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // For scripts/benchmark.py: built as a release build, so that ART runs the app at the speed
        // of an app that users install, but signed with the debug key and profileable by the shell,
        // which takes the benchmark extra (docs/decisions/0067).
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        // The recordings of real apps (tools/recordings), for the "replay" command.
        getByName("main").assets.directories.add("../recordings")
        // Canonical fonts, valid font files for the tests of FontStore.
        getByName("test").resources.directories.add("../vtfx-font-gate/src/fuzz/resources")
    }
}

dependencies {
    implementation(project(":vtfx-compose"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)

    testImplementation(libs.junit4)
}
