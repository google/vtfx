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

import android.widget.Magnifier
import androidx.compose.ui.geometry.Offset
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Records where the platform's loupe shows, without its window: Robolectric's surfaces cannot back
 * the window of `android.widget.Magnifier`, which then fails when it is dismissed. Every test of
 * the module has it (robolectric.properties).
 */
@Implements(Magnifier::class)
class ShadowMagnifier {
    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float) {
        shownAt = Offset(sourceCenterX, sourceCenterY)
    }

    @Implementation
    protected fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
        magnifierCenterX: Float,
        magnifierCenterY: Float,
    ) {
        shownAt = Offset(sourceCenterX, sourceCenterY)
    }

    @Implementation protected fun update() {}

    @Implementation
    protected fun dismiss() {
        shownAt = null
    }

    companion object {
        /**
         * The source center of the loupe that was shown last, in the coordinates of the view that
         * it magnifies, or null if it was dismissed.
         */
        @JvmStatic var shownAt: Offset? = null
    }
}
