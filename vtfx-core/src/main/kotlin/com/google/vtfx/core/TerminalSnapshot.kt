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

import com.google.vtfx.core.graphics.Placeholders

// Not a data class, so that properties can be added without changing what apps compiled earlier
// call (docs/decisions/0037). Only vtfx makes it.
/**
 * The position of the cursor. Whether the host shows it is [TerminalModes.cursorVisible].
 * Immutable, and equal to a state with the same values.
 */
public class CursorState
internal constructor(
    /** The column, 0-based. */
    public val column: Int,
    /** The row, 0-based. */
    public val row: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is CursorState && other.column == column && other.row == row

    override fun hashCode(): Int = 31 * column + row

    override fun toString(): String = "CursorState(column=$column, row=$row)"
}

/**
 * An immutable copy of one line of cells. The functions that take a column throw
 * [IndexOutOfBoundsException] unless it is in `0 until columns`.
 */
public class LineSnapshot
internal constructor(
    internal val line: Line,
    /** The hyperlinks that the cells of [line] refer to. */
    private val links: Map<Int, Hyperlink> = emptyMap(),
) {
    /** The number of cells. */
    public val columns: Int
        get() = line.columns

    /** Whether the text continues on the next line because of autowrap. */
    public val isWrapped: Boolean
        get() = line.isWrapped

    /**
     * Returns the text displayed in the cell at [column]: a grapheme, or null if the cell is blank,
     * the right half of a wide character, or a placeholder of an image (U+10EEEE of the kitty
     * graphics protocol), which shows a part of an image rather than text.
     */
    public fun textAt(column: Int): String? =
        if (line.codes[column] == Placeholders.CODE_POINT) null else line.textAt(column)

    /**
     * Returns the number of cells the character at [column] occupies: 0 for a wide tail, else 1
     * or 2.
     */
    public fun widthAt(column: Int): Int =
        when {
            line.codes[column] == Line.WIDE_TAIL -> 0
            line.isWideHead(column) -> 2
            else -> 1
        }

    /** Returns the style of the cell at [column]. */
    public fun styleAt(column: Int): CellStyle = CellStyle(line.styles[column])

    /**
     * Returns the color of the underline of the cell at [column] (SGR 58), or
     * [TerminalColor.Default] if the underline has the color of the text. It is the default for a
     * cell that is not underlined.
     */
    public fun underlineColorAt(column: Int): TerminalColor {
        // Checked here, since a line without underline colors has no array to check it.
        java.util.Objects.checkIndex(column, line.columns)
        return TerminalColor(line.underlineColorAt(column))
    }

    /** Returns the hyperlink (OSC 8) of the cell at [column], or null. */
    public fun hyperlinkAt(column: Int): Hyperlink? {
        // Checked here, since a line without links has no array to check it.
        java.util.Objects.checkIndex(column, line.columns)
        val id = line.linkAt(column)
        return if (id == 0) null else links[id]
    }

    /**
     * Returns the text of the line, with a space for each blank cell and placeholder of an image,
     * and nothing for wide tails. Trailing blanks are kept.
     */
    public fun text(): String = buildString {
        for (column in 0 until line.columns) {
            if (line.codes[column] == Line.WIDE_TAIL) continue
            append(textAt(column) ?: " ")
        }
    }

    /**
     * Whether the last cell is the blank that autowrap leaves when a wide character does not fit at
     * the end of the row, so that the text of the line, which goes on in [next], does not have it.
     * A cell erased there looks the same and is taken as one too.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun endsWithWidePadding(next: LineSnapshot): Boolean =
        line.endsWithWidePadding(next.line)

    /**
     * Whether [other] shows the same cells (characters, styles, underline colors, and hyperlinks),
     * for a display that keeps what it drew for each line. Hyperlinks are compared by id, which is
     * only meaningful within one terminal.
     */
    public fun contentEquals(other: LineSnapshot): Boolean = line.contentEquals(other.line)

    /** A hash consistent with [contentEquals]. */
    public fun contentHashCode(): Int = line.contentHashCode()

    override fun toString(): String = "LineSnapshot(${text()})"
}

/** An immutable copy of the visible screen, safe to read from any thread. */
public class TerminalSnapshot
internal constructor(
    /** The number of columns. */
    public val columns: Int,
    /** The number of rows of the screen. */
    public val rows: Int,
    /** The cursor, in the screen's coordinates. */
    public val cursor: CursorState,
    /** The modes set by the host. */
    public val modes: TerminalModes,
    /** Whether the alternate screen is displayed. */
    public val isAlternateScreen: Boolean,
    /**
     * How many lines of the scrollback are shown above the screen. The screen is shifted down by as
     * many rows, and [cursor][TerminalSnapshot.cursor] is in the screen's coordinates: it is shown
     * at row `cursor.row + scrollOffset` if that is within [rows][TerminalSnapshot.rows].
     */
    public val scrollOffset: Int,
    private val lines: List<LineSnapshot>,
    /**
     * The rows above the display that autowrap joins to its first line, at most [rows] of them,
     * from the top, for finding the links that the display cuts (docs/decisions/0019).
     */
    internal val linesAbove: List<LineSnapshot> = emptyList(),
    /** The rows below the display that autowrap joins to its last line, at most [rows] of them. */
    internal val linesBelow: List<LineSnapshot> = emptyList(),
    /**
     * Whether the first of [linesAbove] (or of the display, if none) continues a line further
     * above.
     */
    internal val isCutAbove: Boolean = false,
    /**
     * Whether the last of [linesBelow] (or of the display, if none) continues a line further below.
     */
    internal val isCutBelow: Boolean = false,
    /**
     * The images of the kitty graphics protocol that the snapshot shows, in the order to draw them:
     * by z-index, then in the order they were placed.
     *
     * @suppress
     */
    @InternalVtfxApi public val imagePlacements: List<ImagePlacement> = emptyList(),
    /**
     * The cells of the snapshot that are placeholders of images (U+10EEEE of the kitty graphics
     * protocol) of an image that exists, with the part of the image each shows, row by row.
     *
     * @suppress
     */
    @InternalVtfxApi public val imageTiles: List<ImageTile> = emptyList(),
) {
    /**
     * Returns the line at [row], 0-based from the top.
     *
     * @throws IndexOutOfBoundsException unless [row] is in `0 until rows`.
     */
    public fun line(row: Int): LineSnapshot = lines[row]
}
