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

// Reads fonts that users choose and writes them again in one canonical form (docs/decisions/0040).
// What reaches native code is decided by the verifier of vtfx-font-gate, so this module is not
// under
// security review (docs/decisions/0041).
plugins {
    id("vtfx.fuzz")
}

dependencies {
    // The public API returns SanitizedFont and takes FontLimits.
    api(project(":vtfx-font-gate"))
}
