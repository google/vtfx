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

package com.google.vtfx.core

/**
 * The text of [lines], rows that autowrap joins, with the cell of each character, so that a range
 * of the text can be mapped back to cells. A blank cell is a space, a wide tail is nothing, and the
 * blank that autowrap leaves before a wide character that did not fit on the next row is not text.
 * If [trimEnd], the spaces at the end of the text are left out, as copying leaves them out.
 *
 * Keeps no reference to [lines], and two bytes for each character besides the text, so that a
 * search can keep the text of the whole scrollback.
 */
internal class LineText(lines: List<LineSnapshot>, trimEnd: Boolean) {
    val text: String

    /** The column of each character of [text], with [WIDE] if the character is wide. */
    private val columns: ShortArray

    /** The index in [text] of the first character of each row. */
    private val rowStarts = IntArray(lines.size)

    /**
     * The runs of characters of [text] in concealed cells (SGR 8), as the start and end of each, in
     * order, or null if there are none.
     */
    private val concealed: IntArray?

    init {
        val builder = StringBuilder()
        val columns = ShortArrayList()
        val concealed = IntArrayList()
        for ((row, line) in lines.withIndex()) {
            rowStarts[row] = builder.length
            val end =
                if (row < lines.lastIndex && line.endsWithWidePadding(lines[row + 1]))
                    line.columns - 1
                else line.columns
            for (column in 0 until end) {
                val width = line.widthAt(column)
                if (width == 0) continue
                val cellText = line.textAt(column) ?: " "
                if (line.styleAt(column).isInvisible) {
                    concealed.addRun(builder.length, builder.length + cellText.length)
                }
                builder.append(cellText)
                val value = (column or if (width == 2) WIDE else 0).toShort()
                repeat(cellText.length) { columns.add(value) }
            }
        }
        var length = builder.length
        if (trimEnd) while (length > 0 && builder[length - 1] == ' ') length--
        text = builder.substring(0, length)
        this.columns = columns.toArray(length)
        this.concealed = if (concealed.size == 0) null else concealed.toArray(concealed.size)
    }

    /** Whether any of the characters [start] until [end] of [text] is in a concealed cell. */
    fun isConcealed(start: Int, end: Int): Boolean {
        val runs = concealed ?: return false
        // The first run that ends after start; the ends are at odd indices, in order.
        var low = 0
        var high = runs.size / 2
        while (low < high) {
            val middle = (low + high) / 2
            if (runs[middle * 2 + 1] <= start) low = middle + 1 else high = middle
        }
        return low < runs.size / 2 && runs[low * 2] < end
    }

    /**
     * Returns the cells of the characters [start] until [end] of [text], a run on each row they
     * span, from the top, with rows counted from the first of the lines. A wide character is
     * covered whole.
     */
    fun spans(start: Int, end: Int): List<CellSpan> {
        val spans = mutableListOf<CellSpan>()
        // The last row that starts at or before start.
        var row = rowStarts.binarySearch(start).let { if (it >= 0) it else -it - 2 }
        var index = start
        while (index < end) {
            // Skips rows without text, which start where the next one does.
            while (row < rowStarts.lastIndex && rowStarts[row + 1] <= index) row++
            val rowEnd = if (row < rowStarts.lastIndex) rowStarts[row + 1] else text.length
            val first = columnAt(index)
            var last = first
            while (index < end && index < rowEnd) {
                val value = columns[index].toInt()
                last = maxOf(last, (value and WIDE.inv()) + if (value and WIDE != 0) 2 else 1)
                index++
            }
            spans += CellSpan(row, first, last)
            row++
        }
        return spans
    }

    private fun columnAt(index: Int): Int = columns[index].toInt() and WIDE.inv()

    private companion object {
        /** Marks a wide character in [columns], above [TerminalLimits.MAX_DIMENSION] columns. */
        const val WIDE = 1 shl 14
    }
}

/** A growable list of ints without boxing. */
private class IntArrayList {
    private var values = IntArray(16)
    var size = 0
        private set

    /** Adds the run [start] until [end], joined to the last one if that ends at [start]. */
    fun addRun(start: Int, end: Int) {
        if (size > 0 && values[size - 1] == start) {
            values[size - 1] = end
            return
        }
        if (size + 2 > values.size) values = values.copyOf(values.size * 2)
        values[size++] = start
        values[size++] = end
    }

    /** Returns the first [length] values. */
    fun toArray(length: Int): IntArray = values.copyOf(length)
}

/** A growable list of shorts without boxing. */
private class ShortArrayList {
    private var values = ShortArray(256)
    private var size = 0

    fun add(value: Short) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    /** Returns the first [length] values. */
    fun toArray(length: Int): ShortArray = values.copyOf(length)
}
