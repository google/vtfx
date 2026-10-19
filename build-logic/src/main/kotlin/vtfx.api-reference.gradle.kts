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

// The HTML API reference of all library modules: ./gradlew :dokkaGenerate writes it to
// build/dokka/html. Not part of the check: it is for publishing (docs/decisions/0018).

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    moduleName.set("vtfx")
    pluginsConfiguration.html {
        // The same notice as the license headers of the sources; also set by vtfx.dokka.
        footerMessage.set(
            "Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0."
        )
    }
}

dependencies {
    for (module in
        listOf(
            "vtfx-core",
            "vtfx-input",
            "vtfx-gate",
            "vtfx-gate-android",
            "vtfx-session",
            "vtfx-compose",
            "vtfx-font",
        )) {
        "dokka"(project(":$module"))
    }
}
