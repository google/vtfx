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

import com.google.vtfx.core.graphics.Placement

/**
 * Lines that scrolled off the top of the main screen, oldest first, bounded by [capacity] and by a
 * memory budget for [capacity] lines of the current width: [BYTES_PER_LINE] for each line and
 * [BYTES_PER_CELL] for each of its cells. Lines whose cells take more, such as cells full of
 * combining characters, make the oldest lines go before the scrollback has [capacity] of them
 * (docs/decisions/0002).
 */
internal class Scrollback(private val capacity: Int) {
    private val lines = ArrayDeque<CompactLine>()

    /** The sum of the [CompactLine.estimatedBytes] of [lines]. */
    private var bytes = 0L

    val size: Int
        get() = lines.size

    /**
     * The number of lines pushed, including ones dropped since because of the capacity or the
     * budget.
     */
    var pushes: Long = 0L
        private set

    /** Returns the line at [index], 0 being the oldest, as a new [Line] of its original width. */
    operator fun get(index: Int): Line = lines[index].expand()

    /**
     * The value of [pushes] when the oldest line was pushed: the line pushed when [pushes] was `n`
     * is at index `n - firstSerial`. Image placements in the scrollback keep that number.
     */
    val firstSerial: Long
        get() = pushes - lines.size

    /**
     * Keeps a compact copy of [line], with its image placements. The caller may reuse [line], which
     * no longer has them.
     */
    fun push(line: Line) {
        val anchors = line.takeAnchors()
        if (capacity == 0) {
            anchors?.forEach { it.kill() }
            return
        }
        anchors?.forEach { it.moveToScrollback(pushes) }
        add(line.compact(anchors), line.columns)
        pushes++
    }

    /**
     * Adds [line] as the newest, and drops the oldest lines beyond the capacity or the budget for
     * [columns]. The newest line is kept even if it alone is over the budget.
     */
    private fun add(line: CompactLine, columns: Int) {
        lines.addLast(line)
        bytes += line.estimatedBytes
        val budget = capacity * (BYTES_PER_LINE + columns * BYTES_PER_CELL)
        while (lines.size > capacity || (bytes > budget && lines.size > 1)) {
            val dropped = lines.removeFirst()
            bytes -= dropped.estimatedBytes
            dropped.anchors?.forEach { it.kill() }
        }
    }

    /** Removes every line, deleting their image placements. */
    fun clear() {
        for (line in lines) line.anchors?.forEach { it.kill() }
        lines.clear()
        bytes = 0L
    }

    fun forEachLink(action: (Int) -> Unit) {
        lines.forEach { it.forEachLink(action) }
    }

    /**
     * Replaces the lines with [newLines], oldest first, keeping the newest that fit. Their image
     * placements move here; the placements of the lines replaced must already have moved to
     * [newLines] (reflow takes them along).
     */
    fun replaceAll(newLines: List<Line>) {
        lines.clear()
        bytes = 0L
        val kept = newLines.takeLast(capacity)
        for (line in newLines.subList(0, newLines.size - kept.size)) line.killAnchors()
        // The serials are given once the budget has dropped what it drops.
        val anchored = ArrayList<Pair<Array<Placement>, Int>>()
        var count = 0
        for (line in kept) {
            val anchors = line.takeAnchors()
            add(line.compact(anchors), line.columns)
            count++
            if (anchors != null) anchored += Pair(anchors, count)
        }
        for ((anchors, number) in anchored) {
            // The line was the number-th added, so its index is number - 1 - (count - size).
            val index = number - 1 - (count - lines.size)
            for (placement in anchors) {
                if (index >= 0) placement.moveToScrollback(firstSerial + index)
            }
        }
    }

    fun toList(): List<Line> = lines.map { it.expand() }

    /** An estimate of the memory the lines take. */
    fun estimatedBytes(): Long = bytes

    companion object {
        /**
         * The memory budget per line and per cell, by [CompactLine.estimatedBytes]: 15.8 MiB for
         * the default 10,000 lines of 120 columns, within the worst-case target of 16 MiB of
         * docs/decisions/0014. Lines with a different color in every cell take about 1490 bytes at
         * 120 columns, and text with a two-unit cluster in every cell about 1540, within the 1656
         * of the budget, so only lines with more (long clusters, or hyperlinks and colors in every
         * cell) make the scrollback keep fewer lines.
         */
        const val BYTES_PER_LINE = 96L
        const val BYTES_PER_CELL = 13L
    }
}

/** The grid of one screen (main or alternate). Rows are numbered from 0 at the top. */
internal class ScreenBuffer(
    columns: Int,
    rows: Int,
    /** Where lines scrolled off the top go, or null for the alternate screen. */
    private val scrollback: Scrollback?,
) {
    var columns: Int = columns
        private set

    /**
     * The rows as a ring: row 0 is at [start]. Scrolling the whole screen, which output does on
     * every line at the bottom, only moves [start].
     */
    private var lines: Array<Line> = Array(rows) { Line(columns) }
    private var start = 0

    val rows: Int
        get() = lines.size

    operator fun get(row: Int): Line = lines[physical(row)]

    private operator fun set(row: Int, line: Line) {
        lines[physical(row)] = line
    }

    private fun physical(row: Int): Int {
        val index = start + row
        return if (index >= lines.size) index - lines.size else index
    }

    fun forEachLine(action: (Line) -> Unit) {
        for (row in 0 until rows) action(this[row])
    }

    /**
     * Scrolls rows [top, bottom] up by [count], filling the bottom with blank lines. If
     * [saveToScrollback] and the region starts at the top of the screen, the lines that leave the
     * screen are kept in the scrollback, with their image placements; otherwise their placements
     * are deleted.
     */
    fun scrollUp(top: Int, bottom: Int, count: Int, style: CellStyle, saveToScrollback: Boolean) {
        val height = bottom - top + 1
        val n = minOf(count, height)
        if (n <= 0) return
        val scrollback = scrollback?.takeIf { saveToScrollback && top == 0 }
        for (i in 0 until n) {
            val line = this[top + i]
            // The scrollback keeps a compact copy, so the line is reused for the new row.
            if (scrollback != null) scrollback.push(line) else line.killAnchors()
            line.clear(style.erased())
        }
        rotate(top, bottom, n)
    }

    /** Scrolls rows [top, bottom] down by [count], filling the top with blank lines. */
    fun scrollDown(top: Int, bottom: Int, count: Int, style: CellStyle) {
        val height = bottom - top + 1
        val n = minOf(count, height)
        if (n <= 0) return
        for (i in 0 until n) {
            val line = this[bottom - i]
            line.killAnchors()
            line.clear(style.erased())
        }
        rotate(top, bottom, height - n)
    }

    /** Erases every row, deleting the image placements anchored to them. */
    fun eraseAll(style: CellStyle) {
        for (line in lines) {
            line.killAnchors()
            line.clear(style.erased())
        }
    }

    /**
     * Replaces the rows with [newLines], which have [newColumns] cells each. The image placements
     * of the rows replaced must already have moved to [newLines].
     */
    fun replaceLines(newLines: List<Line>, newColumns: Int) {
        lines = newLines.toTypedArray()
        start = 0
        columns = newColumns
    }

    fun toList(): List<Line> = List(rows) { this[it] }

    /**
     * Resizes without reflow. Rows are added and removed at the bottom. Image placements stay on
     * their rows, unless their row or column is gone.
     */
    fun resize(newColumns: Int, newRows: Int) {
        val old = toList()
        lines =
            Array(newRows) { row ->
                if (row < old.size) {
                    old[row].resized(newColumns).also { old[row].moveAnchorsTo(it) }
                } else {
                    Line(newColumns)
                }
            }
        for (row in newRows until old.size) old[row].killAnchors()
        start = 0
        columns = newColumns
    }

    /** Rotates rows [top, bottom] so that row top + [n] becomes row top. */
    private fun rotate(top: Int, bottom: Int, n: Int) {
        val height = bottom - top + 1
        val shift = n % height
        if (shift == 0) return
        if (height == rows) {
            start = physical(shift)
            return
        }
        // A scrolling region: rotate in place by three reversals.
        reverse(top, top + shift - 1)
        reverse(top + shift, bottom)
        reverse(top, bottom)
    }

    private fun reverse(from: Int, to: Int) {
        var i = from
        var j = to
        while (i < j) {
            val line = this[i]
            this[i] = this[j]
            this[j] = line
            i++
            j--
        }
    }
}
