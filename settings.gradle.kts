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

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Fonts under the SIL Open Font License that the tests of vtfx-font read, as files of
        // their GitHub repositories at pinned commits (docs/decisions/0071). The group is the
        // owner, the module the repository, the version the commit, and the artifact the path.
        exclusiveContent {
            forRepository {
                ivy("https://raw.githubusercontent.com/") {
                    name = "GitHub files"
                    patternLayout {
                        artifact("[organisation]/[module]/[revision]/[artifact].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter {
                includeGroup("googlefonts")
                includeGroup("notofonts")
            }
        }
    }
}

rootProject.name = "vtfx"

// See docs/design/architecture.md for the responsibility of each module and the allowed
// dependencies between them.
include(":vtfx-core")

include(":vtfx-input")

include(":vtfx-gate")

include(":vtfx-gate-android")

include(":vtfx-session")

include(":vtfx-compose")

include(":vtfx-font")

include(":vtfx-font-gate")

include(":vtfx-font-gate-android")

// A sample app with a pseudo host, for manual checks and the emulator end-to-end tests.
include(":vtfx-sample")
