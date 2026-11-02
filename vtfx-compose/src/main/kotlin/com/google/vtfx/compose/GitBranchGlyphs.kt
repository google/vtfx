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
 * Draws the branch drawing characters (U+F5D0 to U+F60D) of the flog-symbols set, which git graph
 * tools such as vim-flog print and Android's system fonts lack. Their lines and arcs are those of
 * [BoxDrawing], so that a graph joins box drawing characters in the next cells; commits are circles
 * on the lines.
 */
internal object GitBranchGlyphs {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0xF5D0..0xF60D

    /**
     * Draws [codePoint] in the cell at [topLeft] of [size], for [BuiltinGlyphs]. Straight lines are
     * filled with [fill], so that they join the next cells without seams.
     */
    fun DrawScope.drawGitBranch(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        when {
            codePoint >= FIRST_NODE -> drawNode(codePoint - FIRST_NODE, topLeft, size, color, fill)
            codePoint in 0xF5D2..0xF5D5 -> drawFading(codePoint, topLeft, size, color, fill)
            else ->
                for (part in LINES[codePoint - 0xF5D0]) {
                    with(BoxDrawing) { drawBox(part.code, topLeft, size, color, fill) }
                }
        }
    }

    private const val FIRST_NODE = 0xF5EE

    /**
     * U+F5D0 to U+F5ED as the box drawing characters that draw them on top of each other: lines,
     * arcs, and the arcs of branches that leave or join a line. The fading lines U+F5D2 to U+F5D5
     * are drawn by [drawFading].
     */
    private val LINES =
        listOf(
            // F5D0
            "─",
            "│",
            "",
            "",
            "",
            "",
            "╭",
            "╮",
            "╰",
            "╯",
            "│╰",
            "│╭",
            "╰╭",
            "│╯",
            "│╮",
            "╯╮",
            // F5E0
            "─╮",
            "─╭",
            "╮╭",
            "─╯",
            "─╰",
            "╯╰",
            "│╯╰",
            "│╮╭",
            "─╯╮",
            "─╰╭",
            "│╯╭",
            "│╰╮",
            "─╯╭",
            "─╰╮",
        )

    /**
     * Draws the lines that fade out (U+F5D2 to the right, U+F5D3 to the left, U+F5D4 down, U+F5D5
     * up), which end a branch that a graph cuts off: dashes that shorten away from the edge where
     * the line comes in, which joins a line in the next cell.
     */
    private fun DrawScope.drawFading(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val light = BuiltinGlyphs.lightLineWidth(size)
        val horizontal = codePoint <= 0xF5D3
        val fromStart = codePoint == 0xF5D2 || codePoint == 0xF5D4
        val length = if (horizontal) size.width else size.height
        val slot = length / DASHES
        for (k in 0 until DASHES) {
            val dash = slot * (DASHES - k) / (DASHES + 1)
            val start = if (fromStart) slot * k else length - slot * k - dash
            if (horizontal) {
                fill.rect(
                    this,
                    color,
                    Offset(topLeft.x + start, topLeft.y + size.height / 2 - light / 2),
                    Size(dash, light),
                )
            } else {
                fill.rect(
                    this,
                    color,
                    Offset(topLeft.x + size.width / 2 - light / 2, topLeft.y + start),
                    Size(light, dash),
                )
            }
        }
    }

    private const val DASHES = 4

    private const val UP = 8
    private const val RIGHT = 4
    private const val DOWN = 2
    private const val LEFT = 1

    /** The lines from the commits U+F5EE to U+F60D, filled and hollow in turn, by pairs. */
    private val NODE_ARMS =
        intArrayOf(
            0,
            RIGHT,
            LEFT,
            LEFT or RIGHT,
            DOWN,
            UP,
            UP or DOWN,
            RIGHT or DOWN,
            LEFT or DOWN,
            UP or RIGHT,
            UP or LEFT,
            UP or DOWN or RIGHT,
            UP or DOWN or LEFT,
            LEFT or RIGHT or DOWN,
            LEFT or RIGHT or UP,
            UP or DOWN or LEFT or RIGHT,
        )

    /**
     * Draws commit [node]: a circle, filled or hollow, as wide as the cell less a line width a
     * side, with lines from it to the middles of the edges that [NODE_ARMS] gives.
     */
    private fun DrawScope.drawNode(
        node: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val light = BuiltinGlyphs.lightLineWidth(size)
        val hollow = node % 2 == 1
        val arms = NODE_ARMS[node / 2]
        // The centers of the lines as BoxDrawing fills them, on whole pixels, so that the circle
        // sits on the lines.
        val centerX = topLeft.x + size.width / 2
        val centerY = topLeft.y + size.height / 2
        val circleX = (centerX - light / 2).roundToInt() + light / 2
        val circleY = (centerY - light / 2).roundToInt() + light / 2
        val radius = minOf(size.width, size.height) / 2 - light
        // The lines reach into the circle's outline, so that they meet it without a gap.
        val reach = radius - light / 2
        val right = topLeft.x + size.width
        val bottom = topLeft.y + size.height
        if (arms and UP != 0) {
            fill.rect(
                this,
                color,
                Offset(centerX - light / 2, topLeft.y),
                Size(light, circleY - reach - topLeft.y),
            )
        }
        if (arms and DOWN != 0) {
            fill.rect(
                this,
                color,
                Offset(centerX - light / 2, circleY + reach),
                Size(light, bottom - circleY - reach),
            )
        }
        if (arms and LEFT != 0) {
            fill.rect(
                this,
                color,
                Offset(topLeft.x, centerY - light / 2),
                Size(circleX - reach - topLeft.x, light),
            )
        }
        if (arms and RIGHT != 0) {
            fill.rect(
                this,
                color,
                Offset(circleX + reach, centerY - light / 2),
                Size(right - circleX - reach, light),
            )
        }
        clipRect(
            topLeft.x.roundToInt().toFloat(),
            topLeft.y.roundToInt().toFloat(),
            right.roundToInt().toFloat(),
            bottom.roundToInt().toFloat(),
        ) {
            if (hollow) {
                drawCircle(
                    color,
                    radius - light / 2,
                    Offset(circleX, circleY),
                    style = Stroke(light),
                )
            } else {
                drawCircle(color, radius, Offset(circleX, circleY))
            }
        }
    }
}
