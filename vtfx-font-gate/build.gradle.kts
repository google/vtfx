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

// SECURITY REVIEW REQUIRED. Checks that bytes are a font in the canonical form, which is all that
// reaches the platform's native font code, and holds them as SanitizedFont (docs/decisions/0040,
// 0041). Depends on nothing, not even in its tests: they take the canonical fonts that vtfx-font
// writes from src/fuzz/resources, where CanonicalFixturesTest in vtfx-font keeps them current.
plugins {
    id("vtfx.fuzz")
}

fuzzTarget { className.set("com.google.vtfx.font.gate.VerifierFuzzer") }
