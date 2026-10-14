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

import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.CharWidth

/**
 * Where each character of the IME's composing text goes on the grid: from the terminal cursor,
 * wrapping at the right edge (docs/design/ime-contract.md section 7). The terminal model is not
 * changed.
 *
 * The composing text comes from the IME, which is not trusted (requirement 6.1), and has no length
 * limit. So that a frame does work bounded by the grid, the layout stops at the bottom of the grid
 * ([rows]), and, like a terminal cell, a cell takes at most [MAX_CELL_LENGTH] UTF-16 units of
 * combining characters; the rest are not shown.
 */
internal class ComposingLayout(
    text: String,
    startColumn: Int,
    startRow: Int,
    columns: Int,
    rows: Int,
    ambiguousWidth: AmbiguousWidth,
) {
    /** A character of the composing text: [start, end) in the text, at [column], [row]. */
    class Cell(val start: Int, val end: Int, val column: Int, val row: Int, val width: Int)

    val cells: List<Cell>

    /**
     * Where the cursor goes after the composing text. [endRow] is [rows] or more if it is below the
     * grid.
     */
    val endColumn: Int
    val endRow: Int

    init {
        val result = mutableListOf<Cell>()
        var column = startColumn
        var row = startRow
        // No more text than the grid can show fits in it.
        val end = minOf(text.length.toLong(), rows.toLong() * columns * MAX_CELL_LENGTH).toInt()
        var i = 0
        while (i < end && row < rows) {
            val codePoint = text.codePointAt(i)
            val next = i + Character.charCount(codePoint)
            val width = CharWidth.of(codePoint, ambiguousWidth)
            if (width == 0 && result.isNotEmpty()) {
                // A combining character joins the previous cell, up to the limit.
                val last = result.last()
                if (next - last.start <= MAX_CELL_LENGTH) {
                    result[result.lastIndex] =
                        Cell(last.start, next, last.column, last.row, last.width)
                }
            } else {
                val cellWidth = maxOf(width, 1)
                if (column + cellWidth > columns && column > 0) {
                    column = 0
                    row++
                    if (row >= rows) break
                }
                result += Cell(i, next, column, row, cellWidth)
                column += cellWidth
            }
            i = next
        }
        cells = result
        endColumn = column
        endRow = row
    }

    companion object {
        /** The longest text of a cell in UTF-16 units, as in the terminal model. */
        const val MAX_CELL_LENGTH: Int = 32
    }
}
