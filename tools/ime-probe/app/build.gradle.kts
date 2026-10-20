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

plugins {
    id("com.android.application")
}

android {
    namespace = "vtfx.tools.imeprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "vtfx.tools.imeprobe"
        // The minSdk of vtfx, so that IMEs can be recorded on every version vtfx supports.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
}
