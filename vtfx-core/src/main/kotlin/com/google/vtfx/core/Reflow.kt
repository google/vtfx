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

package com.google.vtfx.core

/**
 * Rewraps lines to a new width (docs/decisions/0084). A logical line is a run of rows joined by
 * autowrap ([Line.isWrapped]); its cells are laid out again at the new width, and a wide character
 * that does not fit at the end of a row moves to the next one. The blank that autowrap left for
 * such a character ([Line.endsWithWidePadding]) is not laid out again, unless the cursor is on it.
 * Trailing blanks of a logical line are dropped, except up to the cursor.
 *
 * The cursor is reflowed as an insertion point: the cell where the next character goes. With a
 * pending wrap, that is the cell after the last column, so the character just printed stays before
 * the cursor.
 */
internal object Reflow {
    /** The rewrapped rows, and where the cursor went. */
    class Result(
        val rows: List<Line>,
        val cursorRow: Int,
        val cursorColumn: Int,
        val pendingWrap: Boolean,
    )

    fun reflow(
        rows: List<Line>,
        cursorRow: Int,
        cursorColumn: Int,
        pendingWrap: Boolean,
        newColumns: Int,
    ): Result {
        val insertion = if (pendingWrap) cursorColumn + 1 else cursorColumn
        val out = ArrayList<Line>(rows.size)
        var newCursorRow = 0
        var newCursorColumn = 0
        var newPendingWrap = false
        var lastContentRow = -1
        var start = 0
        while (start < rows.size) {
            var end = start
            while (end < rows.size - 1 && rows[end].isWrapped) end++
            var current = Line(newColumns)
            out += current
            var column = 0
            fun nextRow() {
                current.isWrapped = true
                current = Line(newColumns)
                out += current
                column = 0
            }
            for (row in start..end) {
                val line = rows[row]
                val hasCursor = row == cursorRow
                // Image placements move with the cell they are anchored to (docs/decisions/0080).
                val anchors = line.takeAnchors()?.also { array -> array.sortBy { it.column } }
                var nextAnchor = 0
                val contentEnd =
                    when {
                        row == end -> line.contentEnd()
                        // The cursor keeps its cell, so that it stays between the same characters.
                        line.endsWithWidePadding(rows[row + 1]) &&
                            !(hasCursor && insertion >= line.columns - 1) -> line.columns - 1
                        else -> line.columns
                    }
                var c = 0
                while (c < contentEnd) {
                    val width = if (line.isWideHead(c)) 2 else 1
                    if (width > newColumns) {
                        // A wide character cannot be shown one column wide; it is dropped. The
                        // cursor on it stays where the character would have been: on this row,
                        // or waiting at its end if it is full.
                        if (hasCursor && insertion >= c && insertion < c + width) {
                            newCursorRow = out.size - 1
                            newPendingWrap = column == newColumns
                            newCursorColumn = minOf(column, newColumns - 1)
                        }
                        c += width
                        continue
                    }
                    if (column + width > newColumns) nextRow()
                    if (hasCursor && insertion >= c && insertion < c + width) {
                        // On either half of a wide character, stay on the same half.
                        newCursorRow = out.size - 1
                        newCursorColumn = column + (insertion - c)
                    }
                    while (anchors != null && nextAnchor < anchors.size) {
                        if (anchors[nextAnchor].column >= c + width) break
                        current.anchor(anchors[nextAnchor++], column)
                        lastContentRow = out.size - 1
                    }
                    if (line.codes[c] != Line.WIDE_TAIL) line.copyCellTo(c, current, column)
                    column += width
                    c += width
                }
                if (column > 0 || line.contentEnd() > 0) lastContentRow = out.size - 1
                while (anchors != null && nextAnchor < anchors.size) {
                    // Past the content: keep the distance from it, within the row.
                    val anchor = anchors[nextAnchor++]
                    val position = column + maxOf(0, anchor.column - contentEnd)
                    current.anchor(anchor, minOf(position, newColumns - 1))
                    lastContentRow = out.size - 1
                }
                if (hasCursor && insertion >= contentEnd) {
                    // The cursor is past the content: keep its distance from it.
                    var position = column + (insertion - contentEnd)
                    newPendingWrap = position == newColumns && insertion == contentEnd
                    if (newPendingWrap) {
                        // Right after content that fills the row: wait at its last column, as
                        // after printing it, rather than starting a row.
                        position = newColumns - 1
                    }
                    while (position >= newColumns) {
                        nextRow()
                        position -= newColumns
                    }
                    newCursorRow = out.size - 1
                    newCursorColumn = position
                }
            }
            start = end + 1
        }
        // Rows below both the content and the cursor are dropped.
        val keep = maxOf(lastContentRow, newCursorRow) + 1
        return Result(
            out.subList(0, minOf(keep, out.size)).toList(),
            newCursorRow,
            newCursorColumn,
            newPendingWrap,
        )
    }
}
