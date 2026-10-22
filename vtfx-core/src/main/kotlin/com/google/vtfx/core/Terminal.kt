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

import com.google.vtfx.core.graphics.GraphicsLayer
import com.google.vtfx.core.graphics.Placeholders
import com.google.vtfx.core.graphics.Placement
import com.google.vtfx.core.graphics.StoredImage
import com.google.vtfx.core.parser.Parser

/**
 * A terminal: interprets host output and maintains the screen state.
 *
 * It has no means to send anything to the host. Queries and other requests with external effects
 * are reported as [TerminalEvent]s to [listener].
 *
 * Not thread-safe: calls must not overlap. They may come from any thread, serialized by the caller,
 * for example with a lock. Use [snapshot] to hand the state to another thread.
 *
 * @suppress
 */
@InternalVtfxApi
public class Terminal(
    columns: Int,
    rows: Int,
    limits: TerminalLimits = TerminalLimits(),
    /** The width of East Asian Ambiguous characters, which must match the host's. */
    ambiguousWidth: AmbiguousWidth = AmbiguousWidth.Narrow,
    listener: TerminalEventListener = TerminalEventListener {},
) {
    init {
        checkSize(columns, rows)
    }

    private var dispatching = false
    private val emulator = Emulator(columns, rows, limits, ambiguousWidth, listener)
    private val parser = Parser(emulator, limits.maxStringLength)

    /** The number of columns. */
    public val columns: Int
        get() = emulator.columns

    /** The number of rows. */
    public val rows: Int
        get() = emulator.rows

    /** The cursor. */
    public val cursor: CursorState
        get() = CursorState(emulator.cursorX, emulator.cursorY)

    /** The modes set by the host. */
    public val modes: TerminalModes
        get() = emulator.modes

    /** Whether the alternate screen is displayed. */
    public val isAlternateScreen: Boolean
        get() = emulator.isAlternateScreen

    /**
     * The number of times the cursor went to another line: to a row that autowrap does not join
     * with its own, onto a line that a line feed or a reverse index scrolled in, or onto a line of
     * the other screen; a reset (RIS) also counts. Following long text over rows by autowrap does
     * not count. Its change across [write] tells whether the host went to another line, even when
     * the cursor ends up on the row and the screen it started on.
     */
    public val lineChanges: Long
        get() = emulator.lineChanges

    /** The number of lines in the scrollback. */
    public val scrollbackSize: Int
        get() = emulator.scrollbackSize()

    /**
     * The number of lines that have entered the scrollback, including ones dropped since because of
     * its capacity. Its change across [write] is how far the scrollback moved, which
     * [scrollbackSize] stops telling once the scrollback is full.
     */
    public val scrollbackPushes: Long
        get() = emulator.scrollbackPushes()

    /**
     * Interprets [length] bytes of host output from [offset]. Sequences may be split across calls.
     */
    public fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "range out of bounds"
        }
        runGuarded { parser.parse(bytes, offset, length) }
    }

    /**
     * Changes the size. The main screen and the scrollback are reflowed to the new width; the
     * alternate screen is cut or padded.
     */
    public fun resize(columns: Int, rows: Int) {
        checkSize(columns, rows)
        runGuarded { emulator.resize(columns, rows) }
    }

    /**
     * Sets the cursor style shown until the host sets one with DECSCUSR or mode 12, and again after
     * DECSCUSR 0 or RIS. Returns whether the displayed style changed.
     */
    public fun setDefaultCursorStyle(shape: CursorShape, blinking: Boolean): Boolean =
        emulator.setDefaultCursorStyle(shape, blinking)

    /** Resets the terminal to its initial state, as RIS does. The scrollback is kept. */
    public fun reset() {
        runGuarded { emulator.reset() }
    }

    /**
     * Returns copies of the last [maxLines] lines of the scrollback and the screen, oldest first,
     * or all of them if there are fewer. The alternate screen has no scrollback, so there they are
     * the screen's rows. Lines of the scrollback are resized to [columns], as in [snapshot].
     *
     * @throws IllegalArgumentException if [maxLines] is negative.
     */
    public fun lines(maxLines: Int): List<LineSnapshot> {
        require(maxLines >= 0) { "maxLines must not be negative: $maxLines" }
        val screen = emulator.screen
        val scrollbackSize = if (emulator.isAlternateScreen) 0 else emulator.scrollbackSize()
        val lineCount = scrollbackSize + screen.rows
        val first = maxOf(0, lineCount - maxLines)
        return List(lineCount - first) {
            val index = first + it
            if (index < scrollbackSize) {
                lineSnapshot(emulator.scrollbackLine(index).resized(emulator.columns), copy = false)
            } else {
                lineSnapshot(screen[index - scrollbackSize])
            }
        }
    }

    /**
     * Returns copies of lines for a search, from line [from], numbered as [SearchLines] says: at
     * least [minLines] lines of the scrollback, if there are as many, and more up to the end of a
     * text to search (a line that autowrap does not join to the next, or [MAX_SEARCH_TEXT_ROWS]
     * rows of one that it does), or, once the lines reach the screen, through the screen's last
     * row. [from] is clamped to the oldest line kept and the screen's first row. Lines of the
     * scrollback are resized to [columns], as in [snapshot].
     *
     * @throws IllegalArgumentException unless [minLines] is positive.
     */
    public fun searchLines(from: Long, minLines: Int): SearchLines {
        require(minLines > 0) { "minLines must be positive: $minLines" }
        val alternate = emulator.isAlternateScreen
        val screen = emulator.screen
        val screenLine = emulator.scrollbackPushes()
        val oldestLine = screenLine - if (alternate) 0 else emulator.scrollbackSize()
        val firstLine = from.coerceIn(oldestLine, screenLine)
        val lines = ArrayList<LineSnapshot>()
        var line = firstLine
        // The rows read of a text that has not ended, as SearchIndex divides the lines into texts.
        var textRows = 0
        while (line < screenLine && (line - firstLine < minLines || textRows > 0)) {
            val index = (line - oldestLine).toInt()
            val snapshot =
                lineSnapshot(emulator.scrollbackLine(index).resized(emulator.columns), copy = false)
            lines += snapshot
            textRows =
                if (snapshot.isWrapped && textRows + 1 < MAX_SEARCH_TEXT_ROWS) textRows + 1 else 0
            line++
        }
        val isComplete = line == screenLine
        if (isComplete) for (row in 0 until screen.rows) lines += lineSnapshot(screen[row])
        return SearchLines(
            // Switching the screen numbers the lines again too.
            generation = emulator.scrollbackGeneration() * 2 + if (alternate) 1 else 0,
            firstLine = firstLine,
            lines = lines,
            oldestLine = oldestLine,
            screenLine = screenLine,
            isComplete = isComplete,
        )
    }

    /**
     * Returns a copy of the screen, scrolled back by [scrollOffset] lines into the scrollback. The
     * offset is clamped to the scrollback's size, and is 0 on the alternate screen, which has no
     * scrollback.
     */
    public fun snapshot(scrollOffset: Int = 0): TerminalSnapshot {
        val screen = emulator.screen
        // Lines are counted from the oldest in the scrollback, which the alternate screen does not
        // show, through the last of the screen.
        val scrollbackSize = if (emulator.isAlternateScreen) 0 else emulator.scrollbackSize()
        val lineCount = scrollbackSize + screen.rows
        val offset = scrollOffset.coerceIn(0, scrollbackSize)
        val first = scrollbackSize - offset

        fun lineAt(index: Int): LineSnapshot =
            if (index < scrollbackSize) {
                // Lines in the scrollback may be narrower or wider after a resize.
                lineSnapshot(emulator.scrollbackLine(index).resized(emulator.columns), copy = false)
            } else {
                lineSnapshot(screen[index - scrollbackSize])
            }

        val lines = List(screen.rows) { lineAt(first + it) }
        // The rows of the display's first and last lines that autowrap joins to them beyond it, up
        // to a screen each way.
        val linesAbove = ArrayDeque<LineSnapshot>()
        var above = first - 1
        while (above >= 0 && linesAbove.size < screen.rows) {
            val line = lineAt(above)
            if (!line.isWrapped) break
            linesAbove.addFirst(line)
            above--
        }
        val isCutAbove = linesAbove.size == screen.rows && above >= 0 && lineAt(above).isWrapped
        val linesBelow = mutableListOf<LineSnapshot>()
        var below = first + screen.rows
        while (
            below < lineCount &&
                linesBelow.size < screen.rows &&
                (linesBelow.lastOrNull() ?: lines.last()).isWrapped
        ) {
            linesBelow += lineAt(below)
            below++
        }
        // The last row is marked as wrapped without a row after it where autowrap stays on the
        // bottom row below the scroll region, so the line goes on only if there is one.
        val isCutBelow = below < lineCount && (linesBelow.lastOrNull() ?: lines.last()).isWrapped
        return TerminalSnapshot(
            columns = emulator.columns,
            rows = emulator.rows,
            cursor = cursor,
            modes = emulator.modes,
            isAlternateScreen = emulator.isAlternateScreen,
            scrollOffset = offset,
            firstLine = emulator.scrollbackPushes() - offset,
            lines = lines,
            linesAbove = linesAbove.toList(),
            linesBelow = linesBelow,
            isCutAbove = isCutAbove,
            isCutBelow = isCutBelow,
            imagePlacements = imagePlacements(offset),
            imageTiles = imageTiles(lines),
        )
    }

    /**
     * The placeholders of images among [lines] (docs/decisions/0038), each with the virtual
     * placement that its underline color names, or the newest one of its image.
     */
    private fun imageTiles(lines: List<LineSnapshot>): List<ImageTile> {
        val layer = emulator.layer
        if (!layer.hasVirtualPlacements) return emptyList()
        // The newest virtual placement of each id of an image, and the newest of all under id 0,
        // made once per image, so that the work is linear in the cells and the placements rather
        // than their product.
        val placementsByImage = HashMap<StoredImage, HashMap<Long, Placement>>()
        val tiles = ArrayList<ImageTile>()
        for ((row, line) in lines.withIndex()) {
            for (cell in Placeholders.resolve(line.line)) {
                val image = layer.imageById(cell.imageId) ?: continue
                val byId =
                    placementsByImage.getOrPut(image) {
                        HashMap<Long, Placement>().also { map ->
                            for (placement in image.virtualPlacements) {
                                map[placement.id] = placement
                                map[0L] = placement
                            }
                        }
                    }
                val placement = byId[cell.placementId] ?: continue
                if (cell.row >= placement.rows || cell.imageColumn >= placement.columns) continue
                tiles +=
                    ImageTile(
                        image.image,
                        row,
                        cell.column,
                        placement.columns,
                        placement.rows,
                        cell.row,
                        cell.imageColumn,
                    )
            }
        }
        return tiles
    }

    /**
     * The placements of images that a snapshot scrolled back by [offset] lines shows, with their
     * rows from its top.
     */
    private fun imagePlacements(offset: Int): List<ImagePlacement> {
        val shown = ArrayList<Placement>()
        val rowsOf = HashMap<Placement, Int>()
        emulator.layer.forEachAnchored { placement, row ->
            val top = row + offset
            if (top < emulator.rows && top + placement.rows > 0) {
                shown += placement
                rowsOf[placement] = top
            }
        }
        if (shown.isEmpty()) return emptyList()
        shown.sortWith(
            compareBy<Placement> { it.z }.thenBy { it.image?.id ?: 0L }.thenBy { it.serial }
        )
        return shown.map { placement ->
            ImagePlacement(
                image = checkNotNull(placement.image).image,
                row = rowsOf.getValue(placement),
                column = placement.column,
                left = placement.left,
                top = placement.top,
                width = placement.width,
                height = placement.height,
                sourceX = placement.sourceX,
                sourceY = placement.sourceY,
                sourceWidth = placement.sourceWidth,
                sourceHeight = placement.sourceHeight,
                z = placement.z,
            )
        }
    }

    /**
     * Sets the size of a cell in pixels, which sizes images placed without a size in cells, and
     * which the host can then ask for with XTWINOPS 14 and 16 (docs/decisions/0038). Until it is
     * set, images take cells of 10 by 20 pixels, and those queries are not answered.
     *
     * @throws IllegalArgumentException unless both are from 1 to 1000.
     */
    public fun setCellSize(width: Int, height: Int) {
        emulator.graphics.setCellSize(width, height)
    }

    /** An estimate of the memory the scrollback takes, for benchmarks (docs/decisions/0014). */
    internal fun estimatedScrollbackBytes(): Long = emulator.estimatedScrollbackBytes()

    /** The number of placements that the lines of the scrollback hold, for tests. */
    internal fun scrollbackAnchorCount(): Int = emulator.scrollbackAnchorCount()

    /** The images and placements of the screen displayed, for tests. */
    internal val graphicsLayer: GraphicsLayer
        get() = emulator.layer

    /** The memory that the pixels of all images take. */
    internal val imageBytes: Long
        get() = emulator.graphics.bytes

    /** Returns a copy of the scrollback line at [index], where 0 is the oldest. */
    public fun scrollbackLine(index: Int): LineSnapshot =
        lineSnapshot(emulator.scrollbackLine(index), copy = false)

    /** [copy] is false for a line that is already a fresh copy, such as one from the scrollback. */
    private fun lineSnapshot(line: Line, copy: Boolean = true): LineSnapshot {
        val copy = if (copy) line.copy() else line
        if (!copy.hasLinks()) return LineSnapshot(copy)
        val links = HashMap<Int, Hyperlink>()
        for (column in 0 until copy.columns) {
            val id = copy.linkAt(column)
            if (id != 0 && id !in links) emulator.hyperlink(id)?.let { links[id] = it }
        }
        return LineSnapshot(copy, links)
    }

    private inline fun runGuarded(block: () -> Unit) {
        check(!dispatching) { "Terminal must not be called from its TerminalEventListener" }
        dispatching = true
        try {
            block()
        } finally {
            dispatching = false
        }
    }

    private companion object {
        fun checkSize(columns: Int, rows: Int) {
            require(columns in 1..TerminalLimits.MAX_DIMENSION) { "columns out of range: $columns" }
            require(rows in 1..TerminalLimits.MAX_DIMENSION) { "rows out of range: $rows" }
        }
    }
}
