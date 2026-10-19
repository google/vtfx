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

package com.google.vtfx.gate.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/**
 * A context on a device where every activity that would take an intent requires a permission that
 * this app does not hold, so the system refuses to start it, as the activity manager does.
 * Robolectric does not model the permissions of other apps' activities.
 */
class PermissionDeniedContext(base: Context) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) {
        throw SecurityException("Permission Denial: starting $intent requires a permission")
    }
}
