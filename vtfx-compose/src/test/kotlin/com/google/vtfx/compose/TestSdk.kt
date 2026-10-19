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

package com.google.vtfx.compose

/**
 * The SDK that Robolectric emulates. Not compileSdk (37): with Robolectric 4.17, Espresso's event
 * injection, which the Compose test rule uses, fails on 37 (NoSuchMethodException in
 * InputManagerEventInjectionStrategy).
 */
const val SDK = 36

/** The minSdk of the Android modules (docs/decisions/0039-minsdk-29.md). */
const val MIN_SDK = 29
