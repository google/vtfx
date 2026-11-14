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

package com.google.vtfx.input

import com.google.vtfx.core.InternalVtfxApi

/**
 * The IME's composing text and its colored ranges (docs/decisions/0103).
 *
 * Held as one value in [ImeEditor], so that every change of the composing text other than
 * [ImeEditor.setComposingText] drops the ranges, and a change of the ranges alone counts as a
 * change of the edit buffer.
 *
 * @suppress
 */
@InternalVtfxApi
public data class ComposingText(
    public val text: String = "",
    public val colors: List<ColoredRange> = emptyList(),
) {
    @InternalVtfxApi
    public companion object {
        /** Empty composing text, with no colored ranges. */
        public val Empty: ComposingText = ComposingText()
    }
}

/**
 * A colored range `[start, end)` in UTF-16 code units of [ComposingText.text], with an ARGB
 * [color], from a `BackgroundColorSpan` or `ForegroundColorSpan` of the IME (docs/decisions/0103).
 *
 * @suppress
 */
@InternalVtfxApi
public sealed interface ColoredRange {
    /** The inclusive start of the range in UTF-16 code units of [ComposingText.text]. */
    public val start: Int

    /** The exclusive end of the range in UTF-16 code units of [ComposingText.text]. */
    public val end: Int

    /** The ARGB color (`0xAARRGGBB`). */
    public val color: Int

    /** From a `BackgroundColorSpan`, composited in span order over the default background. */
    @InternalVtfxApi
    public data class Background(
        override val start: Int,
        override val end: Int,
        override val color: Int,
    ) : ColoredRange

    /** From a `ForegroundColorSpan`; the last one over a character sets its text color. */
    @InternalVtfxApi
    public data class Foreground(
        override val start: Int,
        override val end: Int,
        override val color: Int,
    ) : ColoredRange
}
