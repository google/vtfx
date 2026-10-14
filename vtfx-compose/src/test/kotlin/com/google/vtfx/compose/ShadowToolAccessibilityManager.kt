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

import android.view.accessibility.AccessibilityManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityManager

/**
 * Says whether accessibility requests come from a service that declares isAccessibilityTool, such
 * as TalkBack. Compose shows sensitive nodes only to such services, and Robolectric always says no.
 */
@Implements(AccessibilityManager::class)
class ShadowToolAccessibilityManager : ShadowAccessibilityManager() {
    @Implementation protected fun isRequestFromAccessibilityTool(): Boolean = requestsFromTool

    companion object {
        /** Whether requests come from an accessibility tool. Tests that change it restore it. */
        @JvmStatic var requestsFromTool = true
    }
}
