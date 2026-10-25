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

// The API reference of a library module, generated with Dokka and aggregated by the root project
// (vtfx.api-reference). Declarations marked with InternalVtfxApi are not public API
// (docs/decisions/0087); their KDoc has @suppress, which scripts/check_kdoc.py checks, so that the
// reference leaves them out.

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    dokkaSourceSets.configureEach {
        // The descriptions of the module and its packages.
        includes.from("Module.md")
        // Links each declaration to its source in the published repository, at the tag of the
        // version (docs/decisions/0090), or at the commit that -Pvtfx.sourceRef names, which the
        // reference of the default branch is built from (docs/decisions/0090).
        sourceLink {
            // Read outside the lambda, which the configuration cache stores without the project.
            val sourceDirectory = "${project.name}/src/main/kotlin"
            localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
            remoteUrl(
                providers.gradleProperty("vtfx.sourceRef").orElse("v$version").map {
                    "https://github.com/google/vtfx/blob/$it/$sourceDirectory"
                }
            )
            remoteLineSuffix.set("#L")
        }
    }
    pluginsConfiguration.html {
        // The same notice as the license headers of the sources; also set by vtfx.api-reference.
        footerMessage.set(
            "Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0."
        )
    }
}
