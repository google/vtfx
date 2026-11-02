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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Draws the symbols for legacy computing (U+1FB00 to U+1FBAF, but the unassigned U+1FB93) as shapes
 * that fill the cell, so that mosaics, bars, and graphs made of them join across cells. Android's
 * system fonts have no glyphs for them.
 *
 * Rectangles are filled with [GridFill]. Other shapes are antialiased paths clipped to the cell, so
 * that their edges on the cell boundary fall on the same pixels as the rectangles and meet the
 * shapes and backgrounds of the next cells without seams. The medium shades are the foreground at
 * half alpha, as [BoxDrawing] draws U+2592; the inverse medium shades have the same density, so
 * they are drawn the same way.
 */
internal object LegacyComputing {
    fun isDrawn(codePoint: Int): Boolean = codePoint in 0x1FB00..0x1FBAF && codePoint != 0x1FB93

    /** The path of the shape being drawn, reused across draws, since the renderer draws many. */
    private val path = Path()

    /**
     * Draws [codePoint] in the cell at [topLeft] of [size], for [BuiltinGlyphs]. Rectangles are
     * filled with [fill], so that they join the next cells without seams.
     */
    fun DrawScope.drawLegacyComputing(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        when (codePoint) {
            in 0x1FB00..0x1FB3B -> drawSextants(codePoint, topLeft, size, color, fill)
            in 0x1FB3C..0x1FB6F ->
                drawPolygons(WEDGES[codePoint - 0x1FB3C], topLeft, size, color, alpha = 1f)
            in 0x1FB70..0x1FB94 -> drawRects(RECTS[codePoint - 0x1FB70], topLeft, size, color, fill)
            0x1FB95,
            0x1FB96 -> drawCheckerBoard(codePoint == 0x1FB96, topLeft, size, color, fill)
            0x1FB97 -> drawRects(HEAVY_HORIZONTAL_FILL, topLeft, size, color, fill)
            0x1FB98,
            0x1FB99 -> drawDiagonalFill(codePoint == 0x1FB99, topLeft, size, color)
            in 0x1FB9A..0x1FB9F -> {
                val alpha = if (codePoint >= 0x1FB9C) 0.5f else 1f
                drawPolygons(TRIANGLES[codePoint - 0x1FB9A], topLeft, size, color, alpha)
            }
            0x1FBAF -> drawHorizontalWithStroke(topLeft, size, color, fill)
            else -> drawDiagonals(DIAGONALS[codePoint - 0x1FBA0], topLeft, size, color)
        }
    }

    // --- Sextants ---

    /**
     * U+1FB00 to U+1FB3B: the cell in two columns and three rows, numbered 1 to 6 left to right and
     * top to bottom, as in the names ("BLOCK SEXTANT-135"). Bit n - 1 of a pattern is cell n. The
     * code points take the patterns from 1 to 62 in order, leaving out 21 (the left half, U+258C)
     * and 42 (the right half, U+2590), which are block elements.
     */
    private fun DrawScope.drawSextants(
        codePoint: Int,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        var pattern = codePoint - 0x1FB00 + 1
        if (pattern >= 21) pattern++
        if (pattern >= 42) pattern++
        val halfWidth = size.width / 2
        for (cell in 0 until 6) {
            if (pattern and (1 shl cell) == 0) continue
            val row = cell / 2
            val top = topLeft.y + size.height * row / 3
            val bottom = topLeft.y + size.height * (row + 1) / 3
            fill.rect(
                this,
                color,
                Offset(topLeft.x + halfWidth * (cell % 2), top),
                Size(halfWidth, bottom - top),
            )
        }
    }

    // --- Polygons ---

    /**
     * The polygons of U+1FB3C to U+1FB6F, from their names: the smooth mosaic wedges cut the cell
     * along a line between points of the sextant grid and fill the side with the named corner; the
     * triangular blocks meet at the center of the cell. Each vertex is two digits: x in halves of
     * the width (0 to 2) and y in sixths of the height (0 to 6); polygons are separated by spaces.
     */
    private val WEDGES =
        listOf(
            // 1FB3C
            "041606",
            "042606",
            "021606",
            "022606",
            "001606",
            "0210202606",
            "02202606",
            "0410202606",
            "04202606",
            "06102026",
            "04222606",
            "162426",
            "062426",
            "162226",
            "062226",
            "162026",
            // 1FB4C
            "1022260600",
            "00222606",
            "1024260600",
            "00242606",
            "10260600",
            "02242606",
            "1604002026",
            "26040020",
            "1602002026",
            "26020020",
            "16002026",
            "100200",
            "200200",
            "100400",
            "200400",
            "100600",
            // 1FB5C
            "22040020",
            "2416060020",
            "24060020",
            "2216060020",
            "22060020",
            "20160600",
            "221020",
            "220020",
            "241020",
            "240020",
            "261020",
            "24020020",
            "0020260613",
            "0013202606",
            "0020132606",
            "0020261306",
            // 1FB6C
            "001306",
            "002013",
            "202613",
            "061326",
        )

    /**
     * The polygons of U+1FB9A to U+1FB9F, as in [WEDGES]: two triangles meeting at the center, and
     * half the cell cut along a diagonal (the triangular medium shades, drawn at half alpha).
     */
    private val TRIANGLES =
        listOf("002013 061326", "001306 202613", "002006", "002026", "202606", "002606")

    /** The vertices of a polygon being drawn, in sextant grid units and in pixels. */
    private val gridX = IntArray(MAX_VERTICES)
    private val gridY = IntArray(MAX_VERTICES)
    private val pixelX = FloatArray(MAX_VERTICES)
    private val pixelY = FloatArray(MAX_VERTICES)

    private const val MAX_VERTICES = 5

    /**
     * Draws [polygons] in the cell, clipped to it. Lines are recorded once for every row they move
     * to (`LineCache`), so a cell's edges land on fractions of a pixel, and an antialiased edge
     * along them would leave a seam of partial coverage against the next cell. So each polygon is
     * made larger than the cell, by a pixel past every edge it lies on, with its diagonals going on
     * in their directions, and the rectangle clip cuts it at the cell's edges, which then fall on
     * the same pixels as [GridFill]'s rectangles.
     */
    private fun DrawScope.drawPolygons(
        polygons: String,
        topLeft: Offset,
        size: Size,
        color: Color,
        alpha: Float,
    ) {
        path.reset()
        var i = 0
        while (i < polygons.length) {
            var count = 0
            while (i < polygons.length && polygons[i] != ' ') {
                gridX[count] = polygons[i] - '0'
                gridY[count] = polygons[i + 1] - '0'
                count++
                i += 2
            }
            i++
            addPolygon(count, topLeft, size)
        }
        clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
            drawPath(path, color.copy(alpha = color.alpha * alpha))
        }
    }

    /**
     * Adds the first [count] vertices of [gridX] and [gridY] to [path], made larger than the cell.
     */
    private fun addPolygon(count: Int, topLeft: Offset, size: Size) {
        fun isOnEdge(a: Int, b: Int): Boolean =
            (gridX[a] == gridX[b] && gridX[a] % 2 == 0) ||
                (gridY[a] == gridY[b] && gridY[a] % 6 == 0)
        for (v in 0 until count) {
            pixelX[v] = topLeft.x + size.width * gridX[v] / 2
            pixelY[v] = topLeft.y + size.height * gridY[v] / 6
        }
        for (v in 0 until count) {
            val previous = (v + count - 1) % count
            val next = (v + 1) % count
            val x = pixelX[v]
            val y = pixelY[v]
            val outX = if (gridX[v] == 0) -1f else if (gridX[v] == 2) 1f else 0f
            val outY = if (gridY[v] == 0) -1f else if (gridY[v] == 6) 1f else 0f
            val diagonal =
                when {
                    outX == 0f && outY == 0f -> -1 // The center of the cell stays.
                    !isOnEdge(v, previous) -> previous
                    !isOnEdge(v, next) -> next
                    else -> -1
                }
            var movedX = x + outX
            var movedY = y + outY
            if (diagonal >= 0) {
                // On along the diagonal, until a pixel past the edges.
                val dx = x - pixelX[diagonal]
                val dy = y - pixelY[diagonal]
                val scale = 1f / minOf(abs(dx), abs(dy))
                movedX = x + dx * scale
                movedY = y + dy * scale
            }
            if (v == 0) path.moveTo(movedX, movedY) else path.lineTo(movedX, movedY)
        }
        path.close()
    }

    // --- Rectangles ---

    /**
     * The rectangles of U+1FB70 to U+1FB94 (U+1FB93 is unassigned): each is four digits, left, top,
     * right, and bottom in eighths of the cell, and '~' after them for a medium shade.
     */
    private val RECTS =
        listOf(
            // 1FB70: vertical and horizontal one eighth blocks 2 to 7.
            "1028",
            "2038",
            "3048",
            "4058",
            "5068",
            "6078",
            "0182",
            "0283",
            "0384",
            "0485",
            "0586",
            "0687",
            // 1FB7C: combinations of one eighth blocks along the edges.
            "0018 0788",
            "0018 0081",
            "7088 0081",
            "7088 0788",
            "0081 0788",
            "0081 0283 0485 0788",
            // 1FB82: upper and right blocks.
            "0082",
            "0083",
            "0085",
            "0086",
            "0087",
            "6088",
            "5088",
            "3088",
            "2088",
            "1088",
            // 1FB8C: medium shades.
            "0048~",
            "4088~",
            "0084~",
            "0488~",
            "0088~",
            "0084 0488~",
            "0084~ 0488",
            "",
            "0048~ 4088",
        )

    /**
     * U+1FB97: two stripes, each a quarter of the height, half a cell apart, so that the stripes
     * keep the same spacing across the cells above and below, and run on into the cells beside.
     */
    private const val HEAVY_HORIZONTAL_FILL = "0183 0587"

    private fun DrawScope.drawRects(
        rects: String,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        var i = 0
        while (i < rects.length) {
            val shade = i + 4 < rects.length && rects[i + 4] == '~'
            val left = topLeft.x + size.width * (rects[i] - '0') / 8
            val top = topLeft.y + size.height * (rects[i + 1] - '0') / 8
            val right = topLeft.x + size.width * (rects[i + 2] - '0') / 8
            val bottom = topLeft.y + size.height * (rects[i + 3] - '0') / 8
            val rectColor = if (shade) color.copy(alpha = color.alpha * 0.5f) else color
            fill.rect(this, rectColor, Offset(left, top), Size(right - left, bottom - top))
            i += if (shade) 6 else 5
        }
    }

    // --- Pattern fills ---

    /**
     * U+1FB95 and U+1FB96 ([inverse]): squares a quarter of the width wide and an eighth of the
     * height high, which are about square in a cell twice as high as it is wide, and large enough
     * to stay a visible pattern rather than a gray at the usual cell sizes. The counts are even, so
     * the squares alternate across the cell boundaries and a run of cells is one checker board.
     */
    private fun DrawScope.drawCheckerBoard(
        inverse: Boolean,
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val parity = if (inverse) 1 else 0
        for (row in 0 until 8) {
            val top = topLeft.y + size.height * row / 8
            val bottom = topLeft.y + size.height * (row + 1) / 8
            for (column in 0 until 4) {
                if ((row + column) % 2 != parity) continue
                val left = topLeft.x + size.width * column / 4
                val right = topLeft.x + size.width * (column + 1) / 4
                fill.rect(this, color, Offset(left, top), Size(right - left, bottom - top))
            }
        }
    }

    /** The diagonal stripes in a cell of U+1FB98 and U+1FB99, each as wide as the gap after it. */
    private const val DIAGONAL_STRIPES = 2

    /**
     * U+1FB98 and U+1FB99 ([rising], lower left to upper right): stripes parallel to a diagonal of
     * the cell, a whole number of them per cell. A stripe that leaves a cell through an edge enters
     * the next cell at the same place along that edge, so a block of cells is one striped area,
     * whatever the cell's aspect ratio.
     */
    private fun DrawScope.drawDiagonalFill(
        rising: Boolean,
        topLeft: Offset,
        size: Size,
        color: Color,
    ) {
        val left = topLeft.x
        val right = topLeft.x + size.width
        // A falling stripe from (a, 0) to (a + 1, 1) in units of the cell, a rising one mirrored,
        // going on a pixel past the top and the bottom for the clip to cut (as in drawPolygons).
        val past = 1f / size.height
        val top = topLeft.y - 1f
        val bottom = topLeft.y + size.height + 1f
        fun x(units: Float) = if (rising) right - size.width * units else left + size.width * units
        path.reset()
        for (stripe in -DIAGONAL_STRIPES until DIAGONAL_STRIPES) {
            val start = stripe.toFloat() / DIAGONAL_STRIPES
            val end = start + 0.5f / DIAGONAL_STRIPES
            path.moveTo(x(start - past), top)
            path.lineTo(x(end - past), top)
            path.lineTo(x(end + 1 + past), bottom)
            path.lineTo(x(start + 1 + past), bottom)
            path.close()
        }
        clipRect(left, topLeft.y, right, topLeft.y + size.height) { drawPath(path, color) }
    }

    // --- Lines ---

    /**
     * The light diagonals of U+1FBA0 to U+1FBAE, as lines through the middles of the edges: 'u'
     * (up), 'r' (right), 'd' (down), and 'l' (left). Lines are separated by spaces.
     */
    private val DIAGONALS =
        listOf(
            "ul",
            "ur",
            "ld",
            "rd",
            "uld",
            "urd",
            "ldr",
            "lur",
            "ul rd",
            "ur ld",
            "urdl",
            "uldr",
            "lurd",
            "ruld",
            "urdlu",
        )

    /**
     * Draws [lines] with the light line width. Each segment goes on past the edges by a line width
     * and is clipped to the cell, so that its end covers the cell edge where a box drawing line of
     * the next cell meets it, rather than stopping at a butt end across the diagonal.
     */
    private fun DrawScope.drawDiagonals(lines: String, topLeft: Offset, size: Size, color: Color) {
        val light = BuiltinGlyphs.lightLineWidth(size)
        val left = topLeft.x
        val top = topLeft.y
        val right = topLeft.x + size.width
        val bottom = topLeft.y + size.height
        // The centers of the lines of BoxDrawing, which they join.
        val centerX = topLeft.x + size.width / 2
        val centerY = topLeft.y + size.height / 2
        fun point(side: Char): Offset =
            when (side) {
                'u' -> Offset(centerX, top)
                'r' -> Offset(right, centerY)
                'd' -> Offset(centerX, bottom)
                else -> Offset(left, centerY)
            }
        clipRect(left, top, right, bottom) {
            for (i in 1 until lines.length) {
                if (lines[i] == ' ' || lines[i - 1] == ' ') continue
                val start = point(lines[i - 1])
                val end = point(lines[i])
                val delta = end - start
                val extension = delta * (light / sqrt(delta.x * delta.x + delta.y * delta.y))
                drawLine(color, start - extension, end + extension, light)
            }
        }
    }

    /**
     * U+1FBAF: a light horizontal line, as U+2500, crossed by a vertical stroke over the middle
     * half of the height, which stops short of the edges so that it does not join the cells above
     * and below as U+253C does.
     */
    private fun DrawScope.drawHorizontalWithStroke(
        topLeft: Offset,
        size: Size,
        color: Color,
        fill: GridFill,
    ) {
        val light = BuiltinGlyphs.lightLineWidth(size)
        val centerX = topLeft.x + size.width / 2
        val centerY = topLeft.y + size.height / 2
        fill.rect(this, color, Offset(topLeft.x, centerY - light / 2), Size(size.width, light))
        fill.rect(
            this,
            color,
            Offset(centerX - light / 2, topLeft.y + size.height / 4),
            Size(light, size.height / 2),
        )
    }
}
