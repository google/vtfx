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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws box drawing characters (U+2500 to U+257F) and block elements (U+2580 to U+259F) as lines
 * and rectangles that fill the cell, so that they join across cells. A font's glyphs leave gaps
 * between cells, and fallback fonts do not have the cell size.
 */
internal object BoxDrawing {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0x2500..0x259F

    /**
     * Draws [codePoint] in the cell at [topLeft] of [size]. The caller checks [isDrawn]. Rectangles
     * are filled with [fill], so that they join the next cells without seams.
     */
    fun DrawScope.drawBox(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        if (codePoint >= 0x2580) {
            drawBlock(codePoint, topLeft, size, color, fill)
        } else {
            drawLines(codePoint, topLeft, size, color, fill)
        }
    }

    // --- Lines ---

    private const val NONE = '.'
    private const val LIGHT = 'l'
    private const val HEAVY = 'h'
    private const val DOUBLE = 'd'

    /**
     * The arms of U+2500 to U+257F: up, right, down, and left, each '.' (none), 'l' (light), 'h'
     * (heavy), or 'd' (double). Dashed lines are drawn solid. Arcs and diagonals are '*'.
     */
    private val ARMS =
        listOf(
            // 2500
            ".l.l",
            ".h.h",
            "l.l.",
            "h.h.",
            ".l.l",
            ".h.h",
            "l.l.",
            "h.h.",
            ".l.l",
            ".h.h",
            "l.l.",
            "h.h.",
            ".ll.",
            ".hl.",
            ".lh.",
            ".hh.",
            // 2510
            "..ll",
            "..lh",
            "..hl",
            "..hh",
            "ll..",
            "lh..",
            "hl..",
            "hh..",
            "l..l",
            "l..h",
            "h..l",
            "h..h",
            "lll.",
            "lhl.",
            "hll.",
            "llh.",
            // 2520
            "hlh.",
            "hhl.",
            "lhh.",
            "hhh.",
            "l.ll",
            "l.lh",
            "h.ll",
            "l.hl",
            "h.hl",
            "h.lh",
            "l.hh",
            "h.hh",
            ".lll",
            ".llh",
            ".hll",
            ".hlh",
            // 2530
            ".lhl",
            ".lhh",
            ".hhl",
            ".hhh",
            "ll.l",
            "ll.h",
            "lh.l",
            "lh.h",
            "hl.l",
            "hl.h",
            "hh.l",
            "hh.h",
            "llll",
            "lllh",
            "lhll",
            "lhlh",
            // 2540
            "hlll",
            "llhl",
            "hlhl",
            "hllh",
            "hhll",
            "llhh",
            "lhhl",
            "hhlh",
            "hlhh",
            "hhhl",
            "lhhh",
            "hhhh",
            ".l.l",
            ".h.h",
            "l.l.",
            "h.h.",
            // 2550
            ".d.d",
            "d.d.",
            ".dl.",
            ".ld.",
            ".dd.",
            "..ld",
            "..dl",
            "..dd",
            "ld..",
            "dl..",
            "dd..",
            "l..d",
            "d..l",
            "d..d",
            "ldl.",
            "dld.",
            // 2560
            "ddd.",
            "l.ld",
            "d.dl",
            "d.dd",
            ".dld",
            ".ldl",
            ".ddd",
            "ld.d",
            "dl.l",
            "dd.d",
            "ldld",
            "dldl",
            "dddd",
            "*",
            "*",
            "*",
            // 2570
            "*",
            "*",
            "*",
            "*",
            "...l",
            "l...",
            ".l..",
            "..l.",
            "...h",
            "h...",
            ".h..",
            "..h.",
            ".h.l",
            "l.h.",
            ".l.h",
            "h.l.",
        )

    private fun DrawScope.drawLines(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val light = max(1f, (minOf(size.width, size.height) / 10f).roundToInt().toFloat())
        val heavy = light * 2
        val centerX = topLeft.x + size.width / 2
        val centerY = topLeft.y + size.height / 2
        val arms = ARMS[codePoint - 0x2500]
        if (arms == "*") {
            drawSpecial(codePoint, topLeft, size, color, light, fill)
            return
        }
        val up = arms[0]
        val right = arms[1]
        val down = arms[2]
        val left = arms[3]
        val gap = light * 2
        // Where a straight arm meets the center: past it by half the widest crossing arm, so that
        // corners join without a notch.
        fun reach(style: Char): Float =
            when (style) {
                LIGHT -> light / 2
                HEAVY -> heavy / 2
                DOUBLE -> gap + light / 2
                else -> 0f
            }
        val horizontalReach = maxOf(reach(up), reach(down))
        val verticalReach = maxOf(reach(left), reach(right))
        fun single(style: Char) = if (style == LIGHT) light else heavy
        // A double arm is two lines. Each line ends at the inner line of a crossing double arm on
        // its side, at the outer line if only the other side has a double arm, or at the center.
        fun doubleEnd(sameSide: Char, otherSide: Char): Float =
            when {
                sameSide == DOUBLE -> gap
                otherSide == DOUBLE -> -gap
                else -> -light / 2
            }
        fun drawHorizontalArm(style: Char, isRight: Boolean) {
            if (style == NONE) return
            if (style == DOUBLE) {
                for ((offset, side, other) in
                    listOf(Triple(-gap, up, down), Triple(gap, down, up))) {
                    val centerEnd = doubleEnd(side, other)
                    val start = if (isRight) centerX + centerEnd else topLeft.x
                    val end = if (isRight) topLeft.x + size.width else centerX - centerEnd
                    fill.rect(
                        this,
                        color,
                        Offset(start, centerY + offset - light / 2),
                        Size(end - start, light),
                    )
                }
            } else {
                val t = single(style)
                val start = if (isRight) centerX - horizontalReach else topLeft.x
                val end = if (isRight) topLeft.x + size.width else centerX + horizontalReach
                fill.rect(this, color, Offset(start, centerY - t / 2), Size(end - start, t))
            }
        }
        fun drawVerticalArm(style: Char, isDown: Boolean) {
            if (style == NONE) return
            if (style == DOUBLE) {
                for ((offset, side, other) in
                    listOf(Triple(-gap, left, right), Triple(gap, right, left))) {
                    val centerEnd = doubleEnd(side, other)
                    val start = if (isDown) centerY + centerEnd else topLeft.y
                    val end = if (isDown) topLeft.y + size.height else centerY - centerEnd
                    fill.rect(
                        this,
                        color,
                        Offset(centerX + offset - light / 2, start),
                        Size(light, end - start),
                    )
                }
            } else {
                val t = single(style)
                val start = if (isDown) centerY - verticalReach else topLeft.y
                val end = if (isDown) topLeft.y + size.height else centerY + verticalReach
                fill.rect(this, color, Offset(centerX - t / 2, start), Size(t, end - start))
            }
        }
        drawHorizontalArm(left, isRight = false)
        drawHorizontalArm(right, isRight = true)
        drawVerticalArm(up, isDown = false)
        drawVerticalArm(down, isDown = true)
    }

    /** Arcs (U+256D to U+2570) and diagonals (U+2571 to U+2573). */
    private fun DrawScope.drawSpecial(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        light: Float,
        fill: GridFill,
    ) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = light)
        when (codePoint) {
            // An arc of radius r centered at a corner of a box around the center, joining the
            // middles of two edges.
            0x256D,
            0x256E,
            0x256F,
            0x2570 -> {
                val r = minOf(w, h) / 2
                val cx = topLeft.x + w / 2
                val cy = topLeft.y + h / 2
                val (dx, dy, start) =
                    when (codePoint) {
                        0x256D -> Triple(r, r, 180f) // ╭: from the right edge to the bottom
                        0x256E -> Triple(-r, r, 270f) // ╮
                        0x256F -> Triple(-r, -r, 0f) // ╯
                        else -> Triple(r, -r, 90f) // ╰
                    }
                drawArc(
                    color,
                    start,
                    90f,
                    false,
                    Offset(cx + dx - r, cy + dy - r),
                    Size(2 * r, 2 * r),
                    style = stroke,
                )
                // The straight parts from the arc to the cell edges.
                val x = if (dx > 0) cx + r else topLeft.x
                fill.rect(this, color, Offset(x, cy - light / 2), Size(w / 2 - r, light))
                val y = if (dy > 0) cy + r else topLeft.y
                fill.rect(this, color, Offset(cx - light / 2, y), Size(light, h / 2 - r))
            }
            else -> {
                if (codePoint != 0x2572) {
                    drawLine(
                        color,
                        Offset(topLeft.x + w, topLeft.y),
                        Offset(topLeft.x, topLeft.y + h),
                        light,
                    )
                }
                if (codePoint != 0x2571) {
                    drawLine(color, topLeft, Offset(topLeft.x + w, topLeft.y + h), light)
                }
            }
        }
    }

    // --- Blocks ---

    private fun DrawScope.drawBlock(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        gridFill: GridFill,
    ) {
        val w = size.width
        val h = size.height
        fun fill(x: Float, y: Float, width: Float, height: Float, alpha: Float = 1f) =
            gridFill.rect(
                this,
                color.copy(alpha = color.alpha * alpha),
                Offset(topLeft.x + x, topLeft.y + y),
                Size(width, height),
            )
        when (codePoint) {
            0x2580 -> fill(0f, 0f, w, h / 2)
            in 0x2581..0x2588 -> {
                val eighths = codePoint - 0x2580
                fill(0f, h - h * eighths / 8, w, h * eighths / 8)
            }
            in 0x2589..0x258F -> {
                val eighths = 0x2590 - codePoint
                fill(0f, 0f, w * eighths / 8, h)
            }
            0x2590 -> fill(w / 2, 0f, w / 2, h)
            0x2591 -> fill(0f, 0f, w, h, 0.25f)
            0x2592 -> fill(0f, 0f, w, h, 0.5f)
            0x2593 -> fill(0f, 0f, w, h, 0.75f)
            0x2594 -> fill(0f, 0f, w, h / 8)
            0x2595 -> fill(w * 7 / 8, 0f, w / 8, h)
            else -> {
                // Quadrants: upper left, upper right, lower left, lower right.
                val quadrants = QUADRANTS[codePoint - 0x2596]
                if (quadrants and 8 != 0) fill(0f, 0f, w / 2, h / 2)
                if (quadrants and 4 != 0) fill(w / 2, 0f, w / 2, h / 2)
                if (quadrants and 2 != 0) fill(0f, h / 2, w / 2, h / 2)
                if (quadrants and 1 != 0) fill(w / 2, h / 2, w / 2, h / 2)
            }
        }
    }

    /** U+2596 to U+259F as bits: 8 upper left, 4 upper right, 2 lower left, 1 lower right. */
    private val QUADRANTS =
        intArrayOf(2, 1, 8, 8 or 2 or 1, 8 or 1, 8 or 4 or 2, 8 or 4 or 1, 4, 4 or 2, 4 or 2 or 1)
}
