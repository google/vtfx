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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt

/**
 * Draws the progress bar and spinner characters (U+EE00 to U+EE0B) that Fira Code put in the
 * private use area and Nerd Fonts adopted. Tools print them when `UNICODE_PROGRESS_BAR` is set, and
 * Android's system fonts have no glyphs for them. Drawn here, the segments of a bar join across
 * cells into one outlined bar, which glyphs of a font with a different advance would not.
 */
internal object ProgressGlyphs {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0xEE00..0xEE0B

    /**
     * Draws [codePoint] in the cell at [topLeft] of [size], for [BuiltinGlyphs]. The bar is made of
     * rectangles filled with [fill] on whole pixels, so that its outline runs on unbroken into the
     * next cells.
     */
    fun DrawScope.drawProgress(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val light = BuiltinGlyphs.lightLineWidth(size)
        val left = topLeft.x.roundToInt().toFloat()
        val right = (topLeft.x + size.width).roundToInt().toFloat()
        val top = topLeft.y.roundToInt().toFloat()
        val bottom = (topLeft.y + size.height).roundToInt().toFloat()
        val index = codePoint - 0xEE00
        if (index < 6) {
            drawBar(index % 3, index >= 3, left, right, top, bottom, light, color, fill)
        } else {
            drawSpinner(index - 6, left, right, top, bottom, light, color)
        }
    }

    private const val LEFT_END = 0
    private const val RIGHT_END = 2

    /**
     * Draws a [segment] of a bar: its left end (`[`), a middle, or its right end (`]`), with the
     * fill inset inside the outline if [filled]. The ends stand off the cell edge by a line width,
     * so that the bar is apart from the text next to it; the middles reach both edges.
     */
    private fun DrawScope.drawBar(
        segment: Int,
        filled: Boolean,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        light: Float,
        color: Color,
        fill: GridFill,
    ) {
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (x1 > x0 && y1 > y0) fill.rect(this, color, Offset(x0, y0), Size(x1 - x0, y1 - y0))
        }
        // The bar takes two thirds of the cell height, about the height of capital letters.
        val inset = ((bottom - top) / 6).roundToInt().toFloat()
        val barTop = top + inset
        val barBottom = bottom - inset
        val outerLeft = if (segment == LEFT_END) left + light else left
        val outerRight = if (segment == RIGHT_END) right - light else right
        rect(outerLeft, barTop, outerRight, barTop + light)
        rect(outerLeft, barBottom - light, outerRight, barBottom)
        if (segment == LEFT_END) rect(outerLeft, barTop, outerLeft + light, barBottom)
        if (segment == RIGHT_END) rect(outerRight - light, barTop, outerRight, barBottom)
        if (!filled) return
        // The fill keeps a gap of a line width from the outline, so that the two stay apart.
        val gap = light * 2
        rect(
            if (segment == LEFT_END) outerLeft + gap else left,
            barTop + gap,
            if (segment == RIGHT_END) outerRight - gap else right,
            barBottom - gap,
        )
    }

    /**
     * The arcs of the spinner frames U+EE06 to U+EE0B: the start angle, clockwise from 12 o'clock,
     * and the sweep, in degrees. Played in order, the arc runs around the circle while it grows to
     * half of it and shrinks back.
     */
    private val SPINNER_ARCS =
        floatArrayOf(-50f, 100f, 0f, 120f, 60f, 120f, 90f, 180f, 180f, 130f, 240f, 120f)

    /**
     * Draws spinner [frame] as an arc of a circle as wide as the cell, less half a line width a
     * side.
     */
    private fun DrawScope.drawSpinner(
        frame: Int,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        light: Float,
        color: Color,
    ) {
        val radius = minOf(right - left, bottom - top) / 2 - light
        if (radius <= 0f) return
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        clipRect(left, top, right, bottom) {
            drawArc(
                color,
                // drawArc measures angles from 3 o'clock.
                SPINNER_ARCS[frame * 2] - 90f,
                SPINNER_ARCS[frame * 2 + 1],
                false,
                Offset(centerX - radius, centerY - radius),
                Size(2 * radius, 2 * radius),
                style = Stroke(width = light),
            )
        }
    }
}
