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

// Conventions for Android library modules with instrumented tests, which run on an emulator and
// are not part of the check (docs/design/testing.md section 8). scripts/e2e_render.py runs them.

import com.android.build.api.dsl.LibraryExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<LibraryExtension> {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
    "androidTestImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "androidTestImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
    // The Compose test rule brings Espresso 3.5, which calls an InputManager method that recent
    // Android versions removed.
    "androidTestImplementation"(libs.findLibrary("espresso-core").get())
}
