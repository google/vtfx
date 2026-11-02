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

/**
 * Draws braille patterns (U+2800 to U+28FF) as dots on a grid of two columns and four rows that
 * spans the cell, so that the graphs and spinners that tools draw with them (btop, for example)
 * keep an even spacing across cells. Android's system fonts have no braille glyphs.
 */
internal object Braille {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0x2800..0x28FF

    /** Draws [codePoint] in the cell at [topLeft] of [size], for [BuiltinGlyphs]. */
    fun DrawScope.drawBraille(codePoint: Int, topLeft: Offset, size: Size, color: Color) {
        val dots = codePoint - 0x2800
        val columnWidth = size.width / 2
        val rowHeight = size.height / 4
        val radius = minOf(columnWidth, rowHeight) * DOT_DIAMETER / 2
        for (dot in 0 until 8) {
            if (dots and (1 shl dot) == 0) continue
            drawCircle(
                color,
                radius,
                Offset(
                    topLeft.x + (COLUMNS[dot] + 0.5f) * columnWidth,
                    topLeft.y + (ROWS[dot] + 0.5f) * rowHeight,
                ),
            )
        }
    }

    /**
     * The column and the row of each dot: bit n of the offset from U+2800 raises dot n + 1. Dots 1
     * to 3 are the left column from the top, 4 to 6 the right one, and 7 and 8 the bottom row,
     * which eight-dot braille added.
     */
    private val COLUMNS = intArrayOf(0, 0, 0, 1, 1, 1, 0, 1)
    private val ROWS = intArrayOf(0, 1, 2, 0, 1, 2, 3, 3)

    /** The diameter of a dot, as a fraction of the smaller side of its place in the grid. */
    private const val DOT_DIAMETER = 0.7f
}
