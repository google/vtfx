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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * Draws the separators of Powerline (U+E0B0 to U+E0B7) and Powerline Extra (U+E0B8 to U+E0BF):
 * triangles, half circles, and diagonals that span the cell, which prompts and status lines put
 * between segments of different backgrounds. Drawn over the whole cell, they meet the backgrounds
 * on either side without a gap, which a font's glyph does only if its metrics match the cell, and
 * they show without a Powerline font. The icons of the same fonts (U+E0A0 to U+E0A3, and U+E0C0 on)
 * are pictures rather than separators, and are left to fonts.
 */
internal object Powerline {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0xE0B0..0xE0BF

    /** Reused across calls: the terminal draws on one thread. */
    private val path = Path()

    /**
     * Draws [codePoint] in the cell at [topLeft] of [size], for [BuiltinGlyphs]. The sides of the
     * shapes that lie on the cell's edges reach a pixel past the cell, and the drawing is clipped
     * to the cell, without antialiasing. Those sides then cover the pixels whose centers are in the
     * cell, as [GridFill] fills backgrounds, and meet the backgrounds of the neighbors without a
     * seam, wherever the row lands: lines are drawn at row 0 and moved to their rows, which are
     * rarely at whole pixels, so snapping to pixels when drawing would not do it.
     */
    fun DrawScope.drawPowerline(codePoint: Int, topLeft: Offset, size: Size, color: Color) {
        val left = topLeft.x
        val top = topLeft.y
        val right = left + size.width
        val bottom = top + size.height
        val middle = top + size.height / 2
        // Past the edges, for the sides on them.
        val outLeft = left - 1
        val outTop = top - 1
        val outRight = right + 1
        val outBottom = bottom + 1
        val line = BuiltinGlyphs.lightLineWidth(size)
        clipRect(left, top, right, bottom) {
            when (codePoint) {
                // Solid and outlined triangles pointing right and left.
                0xE0B0 -> fillPolygon(color, outLeft, outTop, right, middle, outLeft, outBottom)
                0xE0B1 -> strokePolyline(color, line, left, top, right, middle, left, bottom)
                0xE0B2 -> fillPolygon(color, outRight, outTop, left, middle, outRight, outBottom)
                0xE0B3 -> strokePolyline(color, line, right, top, left, middle, right, bottom)
                // Half ellipses with their flat sides on the left and right edges.
                0xE0B4 ->
                    drawArc(
                        color,
                        -90f,
                        180f,
                        true,
                        Offset(outLeft - size.width, top),
                        Size(2 * size.width + 1, size.height),
                    )
                0xE0B5 ->
                    drawArc(
                        color,
                        -90f,
                        180f,
                        false,
                        Offset(left - size.width + line / 2, top + line / 2),
                        Size(2 * size.width - line, size.height - line),
                        style = Stroke(line),
                    )
                0xE0B6 ->
                    drawArc(
                        color,
                        90f,
                        180f,
                        true,
                        Offset(left, top),
                        Size(2 * size.width + 1, size.height),
                    )
                0xE0B7 ->
                    drawArc(
                        color,
                        90f,
                        180f,
                        false,
                        Offset(left + line / 2, top + line / 2),
                        Size(2 * size.width - line, size.height - line),
                        style = Stroke(line),
                    )
                // Triangles in the corners, and the diagonals between them. U+E0BD and U+E0BF
                // repeat U+E0BB and U+E0B9.
                0xE0B8 ->
                    fillPolygon(color, outLeft, outTop, outLeft, outBottom, outRight, outBottom)
                0xE0BA ->
                    fillPolygon(color, outRight, outTop, outRight, outBottom, outLeft, outBottom)
                0xE0BC -> fillPolygon(color, outLeft, outTop, outRight, outTop, outLeft, outBottom)
                0xE0BE -> fillPolygon(color, outLeft, outTop, outRight, outTop, outRight, outBottom)
                0xE0B9,
                0xE0BF -> drawLine(color, Offset(left, top), Offset(right, bottom), line)
                0xE0BB,
                0xE0BD -> drawLine(color, Offset(right, top), Offset(left, bottom), line)
                else -> throw IllegalArgumentException("not a separator: U+%04X".format(codePoint))
            }
        }
    }

    private fun DrawScope.fillPolygon(
        color: Color,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
    ) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        drawPath(path, color)
    }

    /** Strokes the two lines from ([x1], [y1]) through ([x2], [y2]) to ([x3], [y3]). */
    private fun DrawScope.strokePolyline(
        color: Color,
        width: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
    ) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt))
    }
}
