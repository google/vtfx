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
 * One row of cells.
 *
 * A cell holds a code point, or [BLANK] if nothing was written, or [WIDE_TAIL] if it is the right
 * half of a wide character. A cell with combining characters keeps the whole cluster in [clusters].
 * Every operation keeps wide characters whole: overwriting either half erases the other.
 */
internal class Line(columns: Int, style: CellStyle = CellStyle.Default) {
    var codes: IntArray = IntArray(columns)
        private set

    var styles: LongArray = LongArray(columns).also { it.fill(style.bits) }
        private set

    private var clusters: Array<String?>? = null

    /** The hyperlink id of each cell (0 for none), allocated when the first link is written. */
    private var links: IntArray? = null

    /**
     * The underline color of each cell (SGR 58) as [TerminalColor.bits], 0 for the text's color,
     * allocated when the first color is written (docs/decisions/0030).
     */
    private var underlineColors: IntArray? = null

    /**
     * The image placements whose top left cell is on this line (docs/decisions/0038), allocated
     * when the first is placed. They move with the line object: scrolling, the scrollback, and
     * reflow take them along, and only [killAnchors] deletes them. Copies made for snapshots do not
     * have them.
     */
    var anchors: ArrayList<Placement>? = null
        private set

    /** Whether the text continues on the next line because of autowrap. */
    var isWrapped: Boolean = false

    val columns: Int
        get() = codes.size

    fun isWideHead(column: Int): Boolean = column + 1 < codes.size && codes[column + 1] == WIDE_TAIL

    /** Returns the text of the cell at [column], or null if it is blank or a wide tail. */
    fun textAt(column: Int): String? {
        val code = codes[column]
        if (code == BLANK || code == WIDE_TAIL) return null
        clusters?.get(column)?.let {
            return it
        }
        return if (code in ' '.code..'~'.code) ASCII_TEXT[code - ' '.code]
        else String(Character.toChars(code))
    }

    /** Returns the hyperlink id of the cell at [column], or 0. */
    fun linkAt(column: Int): Int = links?.get(column) ?: 0

    /** Whether any cell has a hyperlink. */
    fun hasLinks(): Boolean = links?.any { it != 0 } ?: false

    /** Returns the underline color of the cell at [column] as [TerminalColor.bits], or 0. */
    fun underlineColorAt(column: Int): Int = underlineColors?.get(column) ?: 0

    /** Anchors [placement] to this line at [column]. */
    fun anchor(placement: Placement, column: Int) {
        placement.line = this
        placement.column = column
        (anchors ?: ArrayList<Placement>(1).also { anchors = it }) += placement
    }

    /** Forgets [placement], which was deleted. */
    fun unanchor(placement: Placement) {
        anchors?.let {
            it.remove(placement)
            if (it.isEmpty()) anchors = null
        }
    }

    /** Deletes the placements anchored to this line, which is going away. */
    fun killAnchors() {
        val placements = anchors ?: return
        anchors = null
        for (placement in placements.toTypedArray()) placement.kill()
    }

    /** Removes the anchors and returns them, to move them to another line, or returns null. */
    fun takeAnchors(): Array<Placement>? {
        val placements = anchors ?: return null
        anchors = null
        return placements.toTypedArray()
    }

    /**
     * Moves the anchors to [target], which takes this line's place, deleting those beyond its
     * width.
     */
    fun moveAnchorsTo(target: Line) {
        val placements = takeAnchors() ?: return
        for (placement in placements) {
            if (placement.column < target.columns) {
                target.anchor(placement, placement.column)
            } else {
                placement.kill()
            }
        }
    }

    /**
     * Writes a character of [width] 1 or 2 at [column], with hyperlink [link] or 0, and
     * [underlineColor] as [TerminalColor.bits] or 0. The caller ensures that it fits.
     */
    fun set(
        column: Int,
        codePoint: Int,
        width: Int,
        style: CellStyle,
        link: Int = 0,
        underlineColor: Int = 0,
    ) {
        breakWide(column)
        if (width == 2) breakWide(column + 1)
        codes[column] = codePoint
        styles[column] = style.bits
        clusters?.set(column, null)
        if (width == 2) {
            codes[column + 1] = WIDE_TAIL
            styles[column + 1] = style.bits
            clusters?.set(column + 1, null)
        }
        if (link != 0 || links != null) {
            val array = links ?: IntArray(codes.size).also { links = it }
            array[column] = link
            if (width == 2) array[column + 1] = link
        }
        if (underlineColor != 0 || underlineColors != null) {
            val array = underlineColors ?: IntArray(codes.size).also { underlineColors = it }
            array[column] = underlineColor
            if (width == 2) array[column + 1] = underlineColor
        }
    }

    /** Writes a narrow [codePoint] at [column]: the same as [set] with width 1, for plain text. */
    fun setNarrow(column: Int, codePoint: Int, style: Long, link: Int, underlineColor: Int) {
        val code = codes[column]
        if (code == WIDE_TAIL || (column + 1 < codes.size && codes[column + 1] == WIDE_TAIL))
            breakWide(column)
        codes[column] = codePoint
        styles[column] = style
        clusters?.set(column, null)
        if (link != 0 || links != null) {
            (links ?: IntArray(codes.size).also { links = it })[column] = link
        }
        if (underlineColor != 0 || underlineColors != null) {
            (underlineColors ?: IntArray(codes.size).also { underlineColors = it })[column] =
                underlineColor
        }
    }

    /**
     * Appends a combining [codePoint] to the character at [column]. Returns false if there is no
     * character or the cluster would be longer than [MAX_CLUSTER_LENGTH].
     */
    fun combine(column: Int, codePoint: Int): Boolean {
        val current = textAt(column) ?: return false
        // A supplementary code point takes two UTF-16 units.
        if (current.length + Character.charCount(codePoint) > MAX_CLUSTER_LENGTH) return false
        val array = clusters ?: arrayOfNulls<String>(codes.size).also { clusters = it }
        array[column] = current + String(Character.toChars(codePoint))
        return true
    }

    /**
     * Appends [codePoint] to the narrow character at [column] and makes it wide, taking the cell
     * after it, which the caller ensures exists.
     */
    fun makeWide(column: Int, codePoint: Int) {
        breakWide(column + 1)
        val text = textAt(column) ?: return
        val array = clusters ?: arrayOfNulls<String>(codes.size).also { clusters = it }
        array[column] = text + String(Character.toChars(codePoint))
        codes[column + 1] = WIDE_TAIL
        styles[column + 1] = styles[column]
        array[column + 1] = null
        links?.let { it[column + 1] = it[column] }
        underlineColors?.let { it[column + 1] = it[column] }
    }

    /** Whether [other] shows the same cells: characters, styles, hyperlinks, underline colors. */
    fun contentEquals(other: Line): Boolean =
        codes.contentEquals(other.codes) &&
            styles.contentEquals(other.styles) &&
            sameClusters(other) &&
            sameLinks(other) &&
            sameUnderlineColors(other)

    private fun sameClusters(other: Line): Boolean {
        val mine = clusters
        val theirs = other.clusters
        if (mine == null || theirs == null)
            return (mine?.all { it == null } ?: true) && (theirs?.all { it == null } ?: true)
        return mine.contentEquals(theirs)
    }

    private fun sameLinks(other: Line): Boolean = sameIds(links, other.links)

    private fun sameUnderlineColors(other: Line): Boolean =
        sameIds(underlineColors, other.underlineColors)

    /** Whether two side arrays, where null means all 0, hold the same values. */
    private fun sameIds(mine: IntArray?, theirs: IntArray?): Boolean {
        if (mine == null || theirs == null)
            return (mine?.all { it == 0 } ?: true) && (theirs?.all { it == 0 } ?: true)
        return mine.contentEquals(theirs)
    }

    /** A hash of the cells, consistent with [contentEquals]. */
    fun contentHashCode(): Int = 31 * codes.contentHashCode() + styles.contentHashCode()

    /** The column after the last cell that is not blank, or 0 if the line is blank. */
    fun contentEnd(): Int {
        var end = codes.size
        while (end > 0 && codes[end - 1] == BLANK) end--
        return end
    }

    /**
     * Whether the last cell is the blank that autowrap leaves when a wide character does not fit at
     * the end of the row: this row wraps onto [next], which starts with a wide character. That cell
     * is not text. A cell that the host erased there looks the same and is taken as one too; a
     * space that it wrote there is text.
     */
    fun endsWithWidePadding(next: Line): Boolean =
        isWrapped && codes.isNotEmpty() && codes[codes.size - 1] == BLANK && next.isWideHead(0)

    /**
     * Copies the character at [column], with its right half if it is wide, to [targetColumn] of
     * [target], whose cells there the caller ensures exist and are blank. The right half of a wide
     * character has the style, hyperlink, and underline color of its left half and no cluster, so
     * only the left half is read.
     */
    fun copyCellTo(column: Int, target: Line, targetColumn: Int) {
        val width = if (isWideHead(column)) 2 else 1
        target.set(
            targetColumn,
            codes[column],
            width,
            CellStyle(styles[column]),
            linkAt(column),
            underlineColorAt(column),
        )
        clusters?.get(column)?.let { cluster ->
            (target.clusters
                ?: arrayOfNulls<String>(target.codes.size).also { target.clusters = it })[
                targetColumn] = cluster
        }
    }

    /** Erases the cells in [from, to). */
    fun erase(from: Int, to: Int, style: CellStyle) {
        val end = minOf(to, codes.size)
        if (from >= end) return
        breakWide(from)
        breakWide(end - 1)
        fillBlank(from, end, style)
    }

    /**
     * Inserts [count] blank cells at [column], shifting the cells right and dropping overflow. A
     * wide character starting at [column] shifts whole; one that [column] splits is erased.
     */
    fun insertBlanks(column: Int, count: Int, style: CellStyle) {
        val n = minOf(count, codes.size - column)
        if (n <= 0) return
        if (codes[column] == WIDE_TAIL) {
            // Both halves, so that the right half does not shift alone.
            breakWide(column)
            codes[column] = BLANK
        }
        // A wide character split by the right edge is dropped.
        val lastKept = codes.size - n - 1
        if (lastKept >= column && isWideHead(lastKept)) erase(lastKept, lastKept + 2, style)
        shift(column, column + n, codes.size - column - n)
        // The cells to fill still hold their values from before the shift, so they are filled
        // without breakWide, which would erase half of a character that has moved.
        fillBlank(column, column + n, style)
    }

    /**
     * Deletes [count] cells at [column], shifting the cells left and filling the end with blanks.
     */
    fun deleteChars(column: Int, count: Int, style: CellStyle) {
        val n = minOf(count, codes.size - column)
        if (n <= 0) return
        breakWide(column)
        breakWide(column + n - 1)
        shift(column + n, column, codes.size - column - n)
        // As in insertBlanks: the cells to fill still hold their values from before the shift.
        fillBlank(codes.size - n, codes.size, style)
    }

    /**
     * Sets the clusters, links, and underline colors of the first cells, and the anchored
     * [placements], when expanding a [CompactLine] into this new line. [clusters], unlike the
     * others, must be a new array of [columns] cells, which this line takes. The placements do not
     * learn of it, so this line must only be read or laid out again, as reflow does.
     */
    fun restore(
        clusters: Array<String?>?,
        links: IntArray?,
        underlineColors: IntArray?,
        placements: ArrayList<Placement>?,
    ) {
        this.clusters = clusters
        this.links = links?.copyOf(codes.size)
        this.underlineColors = underlineColors?.copyOf(codes.size)
        anchors = placements
    }

    fun clear(style: CellStyle) {
        codes.fill(BLANK)
        styles.fill(style.bits)
        clusters = null
        links = null
        underlineColors = null
        isWrapped = false
    }

    fun copy(): Line {
        val line = Line(0)
        line.codes = codes.copyOf()
        line.styles = styles.copyOf()
        line.clusters = clusters?.copyOf()
        line.links = links?.copyOf()
        line.underlineColors = underlineColors?.copyOf()
        line.isWrapped = isWrapped
        return line
    }

    /**
     * Returns a compact, immutable copy for the scrollback: without the blank cells at the end,
     * with one style if all cells have the same, and with the clusters joined into one string
     * (docs/decisions/0014). It keeps [anchors], which the caller took from this line.
     */
    fun compact(anchors: Array<Placement>? = null): CompactLine {
        val end = contentEnd()
        var uniform = true
        for (column in 1 until end) {
            if (styles[column] != styles[0]) {
                uniform = false
                break
            }
        }
        var clusterText: String? = null
        var clusterIndex: IntArray? = null
        clusters?.let { array ->
            val count = (0 until end).count { array[it] != null }
            if (count == 0) return@let
            val text = StringBuilder()
            val index = IntArray(count)
            var i = 0
            for (column in 0 until end) {
                val cluster = array[column] ?: continue
                text.append(cluster)
                index[i++] = CompactLine.clusterEntry(column, text.length)
            }
            clusterText = text.toString()
            clusterIndex = index
        }
        return CompactLine(
            columns = codes.size,
            codes = codes.copyOf(end),
            styles = if (uniform) LongArray(minOf(end, 1)) { styles[0] } else styles.copyOf(end),
            trailingStyle = if (codes.isEmpty()) 0L else styles[codes.size - 1],
            clusterText = clusterText,
            clusterIndex = clusterIndex,
            links = links?.copyOf(end)?.takeIf { array -> array.any { it != 0 } },
            underlineColors =
                underlineColors?.copyOf(end)?.takeIf { array -> array.any { it != 0 } },
            isWrapped = isWrapped,
            anchors = anchors,
        )
    }

    /** Returns a copy with [newColumns] cells, truncating or padding with blanks. */
    fun resized(newColumns: Int): Line {
        val line = Line(newColumns)
        val n = minOf(newColumns, codes.size)
        codes.copyInto(line.codes, 0, 0, n)
        styles.copyInto(line.styles, 0, 0, n)
        clusters?.let { line.clusters = it.copyOf(newColumns) }
        links?.let { line.links = it.copyOf(newColumns) }
        underlineColors?.let { line.underlineColors = it.copyOf(newColumns) }
        // A wide character cut in half by the new right edge is dropped.
        if (newColumns in 1 until codes.size && isWideHead(newColumns - 1)) {
            line.codes[newColumns - 1] = BLANK
            line.clusters?.set(newColumns - 1, null)
            line.links?.set(newColumns - 1, 0)
            line.underlineColors?.set(newColumns - 1, 0)
        }
        line.isWrapped = isWrapped
        return line
    }

    /** Fills [from, to) with blanks, without looking at the characters around it. */
    private fun fillBlank(from: Int, to: Int, style: CellStyle) {
        codes.fill(BLANK, from, to)
        styles.fill(style.bits, from, to)
        clusters?.fill(null, from, to)
        links?.fill(0, from, to)
        underlineColors?.fill(0, from, to)
    }

    private fun shift(from: Int, to: Int, length: Int) {
        if (length <= 0) return
        codes.copyInto(codes, to, from, from + length)
        styles.copyInto(styles, to, from, from + length)
        clusters?.let { it.copyInto(it, to, from, from + length) }
        links?.let { it.copyInto(it, to, from, from + length) }
        underlineColors?.let { it.copyInto(it, to, from, from + length) }
    }

    /**
     * If [column] is half of a wide character, erases the other half so that it is not orphaned.
     */
    private fun breakWide(column: Int) {
        if (column < 0 || column >= codes.size) return
        if (codes[column] == WIDE_TAIL && column > 0) {
            codes[column - 1] = BLANK
            clusters?.set(column - 1, null)
        } else if (isWideHead(column)) {
            codes[column + 1] = BLANK
        }
    }

    companion object {
        const val BLANK: Int = 0
        const val WIDE_TAIL: Int = -1

        /** The maximum number of UTF-16 units in a cell, which bounds the memory per cell. */
        const val MAX_CLUSTER_LENGTH: Int = 32

        /**
         * The text of each printable ASCII character, so that the display does not allocate it per
         * cell.
         */
        private val ASCII_TEXT = Array('~' - ' ' + 1) { (' ' + it).toString() }
    }
}

/**
 * A line in the scrollback, stored compactly, since there may be many thousands of them. Expanded
 * to a [Line] of its original width when read.
 */
internal class CompactLine(
    private val columns: Int,
    private val codes: IntArray,
    /** One style per cell of [codes], or one for all of them. */
    private val styles: LongArray,
    /** The style of the blank cells after [codes]: the background they were erased with. */
    private val trailingStyle: Long,
    /**
     * The clusters of the cells that have one, joined in column order, or null if none: one string
     * for the line rather than one per cell, whose overhead would exceed the text.
     */
    private val clusterText: String?,
    /** For each cluster in [clusterText], its column and its end there, as [clusterEntry] packs. */
    private val clusterIndex: IntArray?,
    private val links: IntArray?,
    /** The underline colors of the cells of [codes], or null if all are 0. */
    private val underlineColors: IntArray?,
    val isWrapped: Boolean,
    anchors: Array<Placement>?,
) {
    /**
     * The image placements anchored to this line, which moved here with it from the screen, or
     * null. The scrollback removes those deleted since ([unanchor]), so that deleted placements do
     * not pile up beyond the limit on placements.
     */
    var anchors: Array<Placement>? = anchors
        private set

    /** Forgets [placement], which was deleted, if it is anchored to this line. */
    fun unanchor(placement: Placement) {
        val placements = anchors ?: return
        val index = placements.indexOfFirst { it === placement }
        if (index < 0) return
        anchors =
            if (placements.size == 1) null
            else Array(placements.size - 1) { placements[if (it < index) it else it + 1] }
    }

    fun expand(): Line {
        val line = Line(columns, CellStyle(trailingStyle))
        codes.copyInto(line.codes)
        if (styles.size == codes.size) {
            styles.copyInto(line.styles)
        } else {
            line.styles.fill(styles[0], 0, codes.size)
        }
        line.restore(
            expandClusters(),
            links,
            underlineColors,
            anchors?.filterTo(ArrayList()) { it.isAlive }?.takeIf { it.isNotEmpty() },
        )
        line.isWrapped = isWrapped
        return line
    }

    private fun expandClusters(): Array<String?>? {
        val text = clusterText ?: return null
        val index = clusterIndex ?: return null
        val clusters = arrayOfNulls<String>(columns)
        var start = 0
        for (entry in index) {
            val end = entry and END_MASK
            clusters[entry ushr COLUMN_SHIFT] = text.substring(start, end)
            start = end
        }
        return clusters
    }

    /** Calls [action] with every hyperlink id that a cell refers to. */
    fun forEachLink(action: (Int) -> Unit) {
        links?.forEach { if (it != 0) action(it) }
    }

    /**
     * An estimate of the memory this line takes, for the scrollback's budget and benchmarks. It is
     * fixed when the line is made, so that the scrollback's sum stays right as [unanchor] removes
     * placements.
     */
    val estimatedBytes: Int =
        OBJECT +
            ARRAY +
            codes.size * 4 +
            ARRAY +
            styles.size * 8 +
            (clusterText?.let { OBJECT + ARRAY + it.length * 2 } ?: 0) +
            (clusterIndex?.let { ARRAY + it.size * 4 } ?: 0) +
            (links?.let { ARRAY + it.size * 4 } ?: 0) +
            (underlineColors?.let { ARRAY + it.size * 4 } ?: 0) +
            (anchors?.let { ARRAY + it.size * 4 } ?: 0)

    companion object {
        private const val OBJECT = 16
        private const val ARRAY = 16

        /**
         * The bits of an entry of [clusterIndex] below the column: enough for the end of the
         * clusters of a whole line, at most [TerminalLimits.MAX_DIMENSION] ×
         * [Line.MAX_CLUSTER_LENGTH] = 65536 units.
         */
        private const val COLUMN_SHIFT = 17
        private const val END_MASK = (1 shl COLUMN_SHIFT) - 1

        /** Packs the [column] of a cluster and its [end] in the joined text into one int. */
        fun clusterEntry(column: Int, end: Int): Int = (column shl COLUMN_SHIFT) or end
    }
}
