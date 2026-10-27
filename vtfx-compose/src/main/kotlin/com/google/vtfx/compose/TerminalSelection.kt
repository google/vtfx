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
 * A cell of the text, 0-based: [column] of [line], numbered as [TerminalSnapshot.firstLine] numbers
 * lines, so that it names the same cell while the line scrolls into the scrollback.
 */
internal data class TextPosition(val column: Int, val line: Long) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int =
        compareValuesBy(this, other, { it.line }, { it.column })
}

/** Returns the position in the text of [cell] of the display. */
internal fun TerminalSnapshot.position(cell: Cell): TextPosition =
    TextPosition(cell.column, firstLine + cell.row)

/** What a selection grows by when a drag extends it (docs/decisions/0085). */
internal enum class SelectionUnit {
    Cell,
    Word,
    Line,
}

/**
 * A selection of text, from [anchor] to [focus] inclusive, in either order. Its lines are numbered
 * as in snapshots of [generation], so that it stays on its text as the text scrolls; the display
 * drops it when the text changes (docs/decisions/0085).
 *
 * @property isAll Whether Select all made it, from the oldest line to the last row of the screen,
 *   so that the empty lines at its end are not copied (docs/decisions/0085).
 * @property unit What [extendedTo] extends it by: the unit of the press that made it.
 */
internal data class TerminalSelection(
    val anchor: TextPosition,
    val focus: TextPosition,
    val generation: Long,
    val isAll: Boolean = false,
    val unit: SelectionUnit = SelectionUnit.Cell,
) {
    val start: TextPosition
        get() = minOf(anchor, focus)

    val end: TextPosition
        get() = maxOf(anchor, focus)

    /**
     * Returns the selected text of [lines], the first of them being line [firstLine], which must
     * hold the selected lines from the start, or from a later line to copy fewer. Lines are
     * separated by "\n" unless the line was wrapped by autowrap, and trailing blanks of each line
     * are dropped. Wide characters are included whole. The blank that autowrap leaves before a wide
     * character that does not fit is not text ([LineSnapshot.endsWithWidePadding]). Concealed cells
     * are blanks ([visibleTextAt]).
     */
    fun text(lines: List<LineSnapshot>, firstLine: Long): String {
        val start = start
        val end = end
        val from = maxOf(start.line, firstLine)
        if (from > end.line) return ""
        return text(
            lines,
            Cell(if (from == start.line) start.column else 0, (from - firstLine).toInt()),
            Cell(end.column, (end.line - firstLine).toInt()),
        )
    }

    /**
     * Returns the columns of line [line] that the selection covers, or null if it covers none. The
     * last column may be beyond the line, for a selection made on a wider display.
     */
    fun columnsOf(line: Long, columns: Int): IntRange? {
        val start = start
        val end = end
        if (line < start.line || line > end.line) return null
        return (if (line == start.line) start.column else 0)..(if (line == end.line) end.column
            else columns - 1)
    }

    /**
     * Returns this selection extended by its [unit] to [cell] of [snapshot]'s display, whose lines
     * must be numbered as this selection's: from the end of this selection farther from [cell] to
     * the far end of the unit at [cell], so that it covers both. A drag extends what its press
     * selected this way at each move.
     */
    fun extendedTo(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
        val at = at(snapshot, cell, unit)
        val from = minOf(start, at.start)
        val to = maxOf(end, at.end)
        return if (snapshot.position(cell) < start) {
            TerminalSelection(to, from, generation, unit = unit)
        } else {
            TerminalSelection(from, to, generation, unit = unit)
        }
    }

    companion object {
        /** Selects from [anchor] to [focus], cells of [snapshot]'s display, by [unit]. */
        fun onDisplay(
            snapshot: TerminalSnapshot,
            anchor: Cell,
            focus: Cell,
            unit: SelectionUnit = SelectionUnit.Cell,
        ): TerminalSelection =
            TerminalSelection(
                snapshot.position(anchor),
                snapshot.position(focus),
                snapshot.generation,
                unit = unit,
            )

        /** The [unit] at [cell] of [snapshot]'s display: the cell, its [word], or its [line]. */
        fun at(snapshot: TerminalSnapshot, cell: Cell, unit: SelectionUnit): TerminalSelection =
            when (unit) {
                SelectionUnit.Cell -> onDisplay(snapshot, cell, cell)
                SelectionUnit.Word -> word(snapshot, cell)
                SelectionUnit.Line -> line(snapshot, cell)
            }

        /**
         * The word at [cell], by words: the run of non-blank characters around it, or the cell
         * alone, both halves of it if it is wide.
         */
        fun word(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
            val line = snapshot.line(cell.row)
            var column = cell.column
            if (column > 0 && line.widthAt(column) == 0) column--
            if (isBlank(line, column)) {
                val last = if (line.widthAt(column) == 2) column + 1 else column
                return onDisplay(
                    snapshot,
                    Cell(column, cell.row),
                    Cell(last, cell.row),
                    SelectionUnit.Word,
                )
            }
            var from = column
            while (from > 0 && !isBlank(line, from - 1)) from--
            var to = column
            while (to < line.columns - 1 && !isBlank(line, to + 1)) to++
            return onDisplay(snapshot, Cell(from, cell.row), Cell(to, cell.row), SelectionUnit.Word)
        }

        /**
         * The line at [cell], by lines: its rows from the one that the previous row does not wrap
         * into, to the one that does not wrap, within the display.
         */
        fun line(snapshot: TerminalSnapshot, cell: Cell): TerminalSelection {
            var first = cell.row
            while (first > 0 && snapshot.line(first - 1).isWrapped) first--
            var last = cell.row
            while (last < snapshot.rows - 1 && snapshot.line(last).isWrapped) last++
            return onDisplay(
                snapshot,
                Cell(0, first),
                Cell(snapshot.columns - 1, last),
                SelectionUnit.Line,
            )
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

        /**
         * Whether the character at [column] is blank or concealed. The right half of a wide
         * character is blank if its left half is, so that a blank or concealed wide character ends
         * a word on both of its sides.
         */
        private fun isBlank(line: LineSnapshot, column: Int): Boolean {
            val lead = if (column > 0 && line.widthAt(column) == 0) column - 1 else column
            val text = line.visibleTextAt(lead)
            return text == null || text.isBlank()
        }
    }
}
