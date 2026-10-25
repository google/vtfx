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

// SECURITY REVIEW REQUIRED. Makes typefaces from SanitizedFonts, the only code of vtfx that hands
// font data to the platform's native font code (docs/decisions/0086, docs/decisions/0091).
plugins {
    id("vtfx.android-library")
}

android {
    namespace = "com.google.vtfx.font.gate.android"
}

dependencies {
    api(project(":vtfx-font-gate"))
    // Only for the InternalVtfxApi annotation, which apps' code never sees here: vtfx-compose,
    // which uses this module, depends on vtfx-core itself.
    compileOnly(project(":vtfx-core"))
}
