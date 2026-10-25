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

@file:OptIn(InternalVtfxApi::class)

package com.google.vtfx.compose

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LineSnapshot
import com.google.vtfx.core.TerminalSnapshot

/**
 * Returns the text of the cell at [column] as the display shows it: null for a blank cell, and for
 * a concealed cell (SGR 8), which is not drawn, so that copying, accessibility, and the text of a
 * hyperlink do not give text that the user cannot see (docs/decisions/0083).
 */
internal fun LineSnapshot.visibleTextAt(column: Int): String? =
    if (styleAt(column).isInvisible) null else textAt(column)

/**
 * Returns the text of the line as the display shows it, a space for each blank ([visibleTextAt]).
 */
internal fun LineSnapshot.visibleText(): String = buildString {
    for (column in 0 until columns) {
        if (widthAt(column) > 0) append(visibleTextAt(column) ?: " ")
    }
}

/** A cell of the display, 0-based. */
internal data class Cell(val column: Int, val row: Int) : Comparable<Cell> {
    override fun compareTo(other: Cell): Int =
        compareValuesBy(this, other, { it.row }, { it.column })
}

/**
 * A selection of text on the display, from [anchor] to [focus] inclusive, in either order. It is in
 * the display's coordinates, so it is dropped whenever the display changes (docs/decisions/0012).
 *
 * @property isAll Whether it selects all the text, the scrollback included, rather than the cells
 *   from [anchor] to [focus], which then cover the display (docs/decisions/0031).
 */
internal data class TerminalSelection(
    val anchor: Cell,
    val focus: Cell,
    val isAll: Boolean = false,
) {
    val start: Cell
        get() = minOf(anchor, focus)

    val end: Cell
        get() = maxOf(anchor, focus)

    /**
     * Returns the selected text of [snapshot]. Lines are separated by "\n" unless the line was
     * wrapped by autowrap, and trailing blanks of each line are dropped. Wide characters are
     * included whole. The blank that autowrap leaves before a wide character that does not fit is
     * not text ([LineSnapshot.endsWithWidePadding]). Concealed cells are blanks ([visibleTextAt]).
     */
    fun text(snapshot: TerminalSnapshot): String =
        text(List(snapshot.rows) { snapshot.line(it) }, start, end)

    companion object {
        /** Selects all of [snapshot]'s display, standing for all the text. */
        fun all(snapshot: TerminalSnapshot): TerminalSelection =
            TerminalSelection(
                Cell(0, 0),
                Cell(snapshot.columns - 1, snapshot.rows - 1),
                isAll = true,
            )

        /** The word at [cell]: the run of non-blank characters around it, or the cell alone. */
        fun word(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
            val line = snapshot.line(cell.row)
            var column = cell.column
            if (column > 0 && line.widthAt(column) == 0) column--
            if (isBlank(line, column))
                return TerminalSelection(Cell(column, cell.row), Cell(column, cell.row))
            var from = column
            while (from > 0 && !isBlank(line, from - 1)) from--
            var to = column
            while (to < line.columns - 1 && !isBlank(line, to + 1)) to++
            return TerminalSelection(Cell(from, cell.row), Cell(to, cell.row))
        }

        /**
         * The line at [cell]: its rows from the one that the previous row does not wrap into, to
         * the one that does not wrap, within the display.
         */
        fun line(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
            var first = cell.row
            while (first > 0 && snapshot.line(first - 1).isWrapped) first--
            var last = cell.row
            while (last < snapshot.rows - 1 && snapshot.line(last).isWrapped) last++
            return TerminalSelection(Cell(0, first), Cell(snapshot.columns - 1, last))
        }

        /**
         * Returns the text of [lines] from [first] to [last], cells of the rows of [lines], as
         * [text] describes.
         */
        fun text(lines: List<LineSnapshot>, first: Cell, last: Cell): String = buildString {
            for (row in first.row..minOf(last.row, lines.size - 1)) {
                val line = lines[row]
                // Within the line, in case the selection was made on a wider display.
                var from = minOf(if (row == first.row) first.column else 0, line.columns - 1)
                val to = if (row == last.row) last.column else line.columns - 1
                // The last cell of the text: before the blank that autowrap left, if it did.
                val next = if (row != last.row && row + 1 < lines.size) lines[row + 1] else null
                val textEnd =
                    if (next != null && line.endsWithWidePadding(next)) line.columns - 2
                    else line.columns - 1
                if (from > 0 && line.widthAt(from) == 0) from--
                val text = buildString {
                    for (column in from..minOf(to, textEnd)) {
                        if (line.widthAt(column) == 0) continue
                        append(line.visibleTextAt(column) ?: " ")
                    }
                }
                val continues = row != last.row && line.isWrapped && to == line.columns - 1
                append(if (continues) text else text.trimEnd())
                if (row != last.row && !continues) append('\n')
            }
        }

        private fun isBlank(line: LineSnapshot, column: Int): Boolean {
            if (line.widthAt(column) == 0) return false
            val text = line.visibleTextAt(column)
            return text == null || text.isBlank()
        }
    }
}
