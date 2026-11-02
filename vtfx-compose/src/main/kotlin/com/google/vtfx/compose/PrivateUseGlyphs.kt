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

import android.graphics.Paint
import android.graphics.Rect
import com.google.vtfx.core.LineSnapshot
import java.util.IdentityHashMap

/**
 * Fits the glyphs that fonts give private use characters, such as the icons of Nerd Fonts, into
 * their cells (section 7.3 of emulation.md). The icons of a Nerd Font that is not "Mono" are up to
 * two cells wide while they advance one, so they would cover the next character. A glyph that fits
 * is drawn as the font draws it; one that does not may also take the next cell when that cell is
 * blank, as prompts leave a space after an icon for this, and is scaled down, keeping its aspect
 * ratio, to the cells it may take.
 *
 * Holds the ink bounds it measured, so it is used on one thread, as the renderer is.
 */
internal class PrivateUseGlyphs(private val metrics: CellMetrics) {
    /**
     * How to draw a glyph: scaled by [scale] about the point at the left of its cell and [pivotY]
     * below its top, then moved down by [dy].
     */
    class Fit(val scale: Float, val pivotY: Float, val dy: Float)

    /** The ink bounds of glyphs by paint and text, the most recently used [MAX_BOUNDS] of each. */
    private val bounds = IdentityHashMap<Paint, LinkedHashMap<String, Rect>>()

    /**
     * Returns how to draw [text], the text of the cell at [column] of [line], with [paint], or null
     * if it is drawn as the font draws it: when it is not one private use character, or when its
     * glyph fits its cell.
     */
    fun fit(text: String, line: LineSnapshot, column: Int, paint: Paint): Fit? {
        if (!isPrivateUse(text)) return null
        val ink = inkBounds(paint, text)
        if (ink.isEmpty) return null
        val top = metrics.baseline + ink.top
        val bottom = metrics.baseline + ink.bottom
        val cells = if (ink.right > metrics.width && isBlank(line, column + 1)) 2 else 1
        val width = line.widthAt(column).coerceAtLeast(cells) * metrics.width
        if (ink.right <= width && top >= 0 && bottom <= metrics.height) return null
        val scale = minOf(1f, width / ink.right, metrics.height / (bottom - top))
        // Scaled about the center of its ink, which is then kept inside the cell.
        val center = (top + bottom) / 2
        val half = (bottom - top) * scale / 2
        val newCenter = center.coerceIn(half, metrics.height - half)
        return Fit(scale, center, newCenter - center)
    }

    private fun isBlank(line: LineSnapshot, column: Int): Boolean {
        if (column >= line.columns) return false
        val text = line.textAt(column)
        return text == null || text == " "
    }

    private fun inkBounds(paint: Paint, text: String): Rect {
        val cache =
            bounds.getOrPut(paint) {
                object : LinkedHashMap<String, Rect>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rect>) =
                        size > MAX_BOUNDS
                }
            }
        return cache.getOrPut(text) {
            Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        }
    }

    private companion object {
        /**
         * The most glyphs whose bounds are kept for each paint: more than a screen shows, few
         * enough that a host writing every private use character cannot grow the cache much.
         */
        const val MAX_BOUNDS = 1024

        fun isPrivateUse(text: String): Boolean {
            if (text.isEmpty()) return false
            val codePoint = text.codePointAt(0)
            return Character.charCount(codePoint) == text.length &&
                Character.getType(codePoint) == Character.PRIVATE_USE.toInt()
        }
    }
}
