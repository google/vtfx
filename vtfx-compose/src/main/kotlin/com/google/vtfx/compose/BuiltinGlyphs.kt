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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The characters that the terminal draws as shapes rather than with font glyphs, over the cells
 * they occupy (section 7.3 of emulation.md). The shapes reach the edges of the cell, so that they
 * join across cells, which glyphs of fonts do not; and they show without a font that has them. They
 * take precedence over the glyphs of every font, the user's included.
 */
internal object BuiltinGlyphs {
    /** Returns whether [text], the text of a cell, is one character that is drawn here. */
    fun isDrawn(text: String): Boolean {
        if (text.isEmpty()) return false
        val codePoint = text.codePointAt(0)
        return Character.charCount(codePoint) == text.length && isDrawn(codePoint)
    }

    private fun isDrawn(codePoint: Int): Boolean =
        BoxDrawing.isDrawn(codePoint) ||
            Braille.isDrawn(codePoint) ||
            Powerline.isDrawn(codePoint) ||
            LegacyComputing.isDrawn(codePoint) ||
            ProgressGlyphs.isDrawn(codePoint)

    /**
     * Draws [text], which [isDrawn], over the cells at [topLeft] of [size]: both cells of a wide
     * character. Rectangles are filled with [fill], so that they join the next cells without seams.
     */
    fun DrawScope.draw(text: String, topLeft: Offset, size: Size, color: Color, fill: GridFill) {
        val codePoint = text.codePointAt(0)
        when {
            BoxDrawing.isDrawn(codePoint) ->
                with(BoxDrawing) { drawBox(codePoint, topLeft, size, color, fill) }
            Braille.isDrawn(codePoint) ->
                with(Braille) { drawBraille(codePoint, topLeft, size, color) }
            Powerline.isDrawn(codePoint) ->
                with(Powerline) { drawPowerline(codePoint, topLeft, size, color) }
            LegacyComputing.isDrawn(codePoint) ->
                with(LegacyComputing) { drawLegacyComputing(codePoint, topLeft, size, color, fill) }
            ProgressGlyphs.isDrawn(codePoint) ->
                with(ProgressGlyphs) { drawProgress(codePoint, topLeft, size, color, fill) }
            else -> throw IllegalArgumentException("not drawn here: $text")
        }
    }

    /** The width of a light line in a cell of [size]; heavier lines are multiples of it. */
    fun lightLineWidth(size: Size): Float =
        max(1f, (minOf(size.width, size.height) / 10f).roundToInt().toFloat())
}
