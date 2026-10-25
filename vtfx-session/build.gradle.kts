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

// A terminal session without a UI: the terminal, the input, and the requests to the host, for the
// Compose UI and for JVM programs alike (docs/decisions/0084).
plugins {
    id("vtfx.jvm-library")
}

dependencies {
    // The public API uses types of these modules: the app gives the HostConnection and the policy
    // of vtfx-gate, reads TerminalSnapshots and sends KeyStrokes of vtfx-core.
    api(project(":vtfx-core"))
    api(project(":vtfx-gate"))
    implementation(project(":vtfx-input"))
}
