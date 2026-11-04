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

import android.view.View
import android.view.inputmethod.InputMethodManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * Records the selections that the app tells the IME with `InputMethodManager.updateSelection`,
 * which Robolectric's shadow drops. Tests that use it name it in their `@Config(shadows = ...)`.
 */
@Implements(InputMethodManager::class)
class RecordingInputMethodManager : ShadowInputMethodManager() {
    @Implementation
    protected fun updateSelection(
        view: View,
        selStart: Int,
        selEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        selections += ReportedSelection(selStart, selEnd, candidatesStart, candidatesEnd)
    }

    /** A selection and composing region as `updateSelection` reports them. */
    data class ReportedSelection(
        val start: Int,
        val end: Int,
        val composingStart: Int,
        val composingEnd: Int,
    )

    companion object {
        /** The selections reported, in order. Tests clear it before they use it. */
        @JvmStatic val selections = mutableListOf<ReportedSelection>()
    }
}
