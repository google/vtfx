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
 */
internal class LineText(val lines: List<LineSnapshot>) {
    val text: String

    /** The cell of each character of [text], as row * columns + column. */
    private val cells: IntArrayList

    private val columns = lines[0].columns

    init {
        val builder = StringBuilder()
        val cells = IntArrayList()
        for ((row, line) in lines.withIndex()) {
            val end =
                if (row < lines.lastIndex && line.endsWithWidePadding(lines[row + 1]))
                    line.columns - 1
                else line.columns
            for (column in 0 until end) {
                if (line.widthAt(column) == 0) continue
                val cellText = line.textAt(column) ?: " "
                builder.append(cellText)
                repeat(cellText.length) { cells.add(row * columns + column) }
            }
        }
        text = builder.toString()
        this.cells = cells
    }

    /**
     * Returns the cells of the characters [start] until [end] of [text], a run on each row they
     * span, from the top, with rows counted from the first of [lines]. A wide character is covered
     * whole.
     */
    fun spans(start: Int, end: Int): List<CellSpan> {
        val spans = mutableListOf<CellSpan>()
        var index = start
        while (index < end) {
            val row = cells[index] / columns
            val line = lines[row]
            val first = cells[index] % columns
            var last = first
            while (index < end && cells[index] / columns == row) {
                val column = cells[index] % columns
                last = maxOf(last, column + line.widthAt(column))
                index++
            }
            spans += CellSpan(row, first, last)
        }
        return spans
    }
}

/** A growable list of ints without boxing. */
private class IntArrayList {
    private var values = IntArray(256)
    private var size = 0

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Int = values[index]
}
