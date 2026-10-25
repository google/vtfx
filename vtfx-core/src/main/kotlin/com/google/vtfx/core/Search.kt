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

// The design is in docs/decisions/0085.

/**
 * What to search for: [text] as it is, ignoring the case of letters if [ignoreCase]. Immutable.
 *
 * @throws IllegalArgumentException if [text] is empty.
 * @suppress
 */
@InternalVtfxApi
public class SearchQuery(public val text: String, public val ignoreCase: Boolean) {
    init {
        require(text.isNotEmpty()) { "the text to search for must not be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is SearchQuery && other.text == text && other.ignoreCase == ignoreCase

    override fun hashCode(): Int = text.hashCode() * 2 + if (ignoreCase) 1 else 0

    override fun toString(): String = "SearchQuery(text=$text, ignoreCase=$ignoreCase)"
}

/**
 * A match of a search: the cells from [startColumn] of line [firstLine] until [endColumn] of line
 * [lastLine], through the rows between, which autowrap joins. Lines are numbered as in
 * [SearchLines]. Immutable, and equal to a match of the same cells.
 *
 * @suppress
 */
@InternalVtfxApi
public class SearchMatch(
    public val firstLine: Long,
    public val startColumn: Int,
    public val lastLine: Long,
    public val endColumn: Int,
) {
    /** Whether this match starts before [other] does. */
    public fun startsBefore(other: SearchMatch): Boolean =
        firstLine < other.firstLine ||
            (firstLine == other.firstLine && startColumn < other.startColumn)

    override fun equals(other: Any?): Boolean =
        other is SearchMatch &&
            other.firstLine == firstLine &&
            other.startColumn == startColumn &&
            other.lastLine == lastLine &&
            other.endColumn == endColumn

    override fun hashCode(): Int =
        ((firstLine.hashCode() * 31 + startColumn) * 31 + lastLine.hashCode()) * 31 + endColumn

    override fun toString(): String = "SearchMatch($firstLine:$startColumn-$lastLine:$endColumn)"
}

/**
 * Lines of a terminal, read for a search by [Terminal.searchLines].
 *
 * Lines are numbered so that a line keeps its number when it scrolls into the scrollback. Numbers
 * are meaningful only while [generation] stays the same.
 *
 * @suppress
 */
@InternalVtfxApi
public class SearchLines
internal constructor(
    /**
     * Changes when the lines are numbered again: on a resize, which reflows them, when the
     * scrollback is cleared, and when the screen switches between the main and the alternate one.
     */
    public val generation: Long,
    /** The number of the first of [lines]. */
    public val firstLine: Long,
    /**
     * Lines from the start of a text to search, up to the end of one, or through the last row of
     * the screen. A text is the rows that autowrap joins, up to [MAX_SEARCH_TEXT_ROWS] of them.
     */
    public val lines: List<LineSnapshot>,
    /** The number of the oldest line that the terminal keeps. */
    public val oldestLine: Long,
    /** The number of the first row of the screen. */
    public val screenLine: Long,
    /** Whether [lines] go through the last row of the screen. */
    public val isComplete: Boolean,
)

/**
 * The matches of [query] in the lines of a terminal, kept up to date as the terminal changes.
 *
 * Read the lines from [nextLine] with [Terminal.searchLines] and give them to [update] until it
 * returns false. Lines that have entered the scrollback do not change until the lines are numbered
 * again, so later updates search only the lines that entered it since, and the screen, with the
 * text that goes on into the screen. The text of those lines is kept, so that [withQuery] searches
 * it for another query without reading the lines again: a few bytes for each character of the
 * scrollback, which ends with no trailing blanks.
 *
 * Rows that autowrap joins are searched as one text, as [LinkFinder] reads them, without the blanks
 * at its end, which copying leaves out too, in parts of [MAX_SEARCH_TEXT_ROWS] rows when there are
 * more. A match that covers a concealed cell (SGR 8), which the display does not draw, is not a
 * match. Only the newest [MAX_MATCHES] matches are kept.
 *
 * Not thread-safe.
 *
 * @suppress
 */
@InternalVtfxApi
public class SearchIndex
private constructor(
    /** What this index finds. */
    public val query: SearchQuery,
    /** The text of the lines that ended in the scrollback, oldest first. */
    private val texts: ArrayDeque<SearchText>,
    /** The generation of the lines given to [update], or null before the first. */
    private var generation: Long?,
    /** The line to read from: the start of the first line that may still change. */
    nextLine: Long,
    /** The oldest line kept by the terminal, as of the last update. */
    private var oldestLine: Long,
) {
    /** Creates an index of [query] that has not read any line yet. */
    public constructor(
        query: SearchQuery
    ) : this(query, ArrayDeque(), generation = null, nextLine = Long.MIN_VALUE, Long.MIN_VALUE)

    private val finder = LiteralFinder(query)

    /** The matches, oldest first. */
    private val kept = ArrayDeque<SearchMatch>()

    /** How many of [kept], from the first, are in [texts], which do not change. */
    private var closedCount = 0

    /** The first line of the newest match of [texts] that [MAX_MATCHES] dropped. */
    private var droppedClosedLine = Long.MIN_VALUE

    /** Whether [MAX_MATCHES] dropped matches of the screen in the last update. */
    private var droppedOpen = false

    /** The line to read from: the start of the first line that may still change. */
    public var nextLine: Long = nextLine
        private set

    /** The number of the first row of the screen, as of the last update. */
    public var screenLine: Long = 0
        private set

    init {
        for (text in texts) find(text, closing = true)
    }

    /** The matches, oldest first, ordered by their first cell. */
    public val matches: List<SearchMatch>
        get() = kept

    /** Whether there are more matches than [matches], which keeps only the newest. */
    public val isCapped: Boolean
        get() = droppedOpen || droppedClosedLine >= oldestLine

    /**
     * Returns an index of [newQuery] over the lines this one read, without reading them again. It
     * finds the matches of the screen at the next [update].
     */
    public fun withQuery(newQuery: SearchQuery): SearchIndex =
        SearchIndex(newQuery, ArrayDeque(texts), generation, nextLine, oldestLine)

    /**
     * Applies [read], lines read from [nextLine]. Returns whether to read more: when [read] does
     * not reach the end of the screen, or when the lines were numbered again and must all be read.
     */
    public fun update(read: SearchLines): Boolean {
        if (generation != null && read.generation != generation) {
            // The lines read are numbered differently from those kept.
            texts.clear()
            kept.clear()
            closedCount = 0
            droppedClosedLine = Long.MIN_VALUE
            nextLine = Long.MIN_VALUE
            generation = null
            return true
        }
        generation = read.generation
        oldestLine = read.oldestLine
        screenLine = read.screenLine
        while (texts.isNotEmpty() && texts.first().lastLine < oldestLine) texts.removeFirst()
        while (kept.isNotEmpty() && kept.first().firstLine < oldestLine) {
            kept.removeFirst()
            if (closedCount > 0) closedCount--
        }
        // The screen may have changed since.
        while (kept.size > closedCount) kept.removeLast()
        droppedOpen = false
        val lines = read.lines
        var row = 0
        while (row < lines.size) {
            val first = row
            while (
                row < lines.lastIndex &&
                    lines[row].isWrapped &&
                    row - first + 1 < MAX_SEARCH_TEXT_ROWS
            ) {
                row++
            }
            val text = SearchText(read.firstLine + first, lines.subList(first, row + 1))
            // A line that ends in the scrollback does not change; the next reads start after it.
            val closing = text.lastLine < read.screenLine
            find(text, closing)
            if (closing) {
                texts.addLast(text)
                nextLine = text.lastLine + 1
            }
            row++
        }
        return !read.isComplete
    }

    /** Adds the matches in [line], which goes into [texts] if [closing]. */
    private fun find(line: SearchText, closing: Boolean) {
        val text = line.text
        finder.find(text.text) { start, end ->
            val visible = !text.isConcealed(start, end)
            if (visible) {
                val spans = text.spans(start, end)
                add(
                    closing,
                    SearchMatch(
                        line.firstLine + spans.first().row,
                        spans.first().start,
                        line.firstLine + spans.last().row,
                        spans.last().end,
                    ),
                )
            }
            visible
        }
    }

    /** Adds [match], of a line that goes into [texts] if [closing]. */
    private fun add(closing: Boolean, match: SearchMatch) {
        kept.addLast(match)
        if (closing) closedCount++
        if (kept.size <= MAX_MATCHES) return
        val dropped = kept.removeFirst()
        // The oldest match is of a line in texts, or else of the line being searched.
        if (closedCount > 0) {
            closedCount--
            droppedClosedLine = maxOf(droppedClosedLine, dropped.firstLine)
        } else {
            droppedOpen = true
        }
    }

    @InternalVtfxApi
    public companion object {
        /**
         * The most matches kept, so that text full of them, which the host decides, takes bounded
         * memory and drawing.
         */
        public val MAX_MATCHES: Int = 1000
    }
}

/**
 * The most rows that a search joins into one text. A line that autowrap joins through more rows is
 * searched in parts of this many rows from where the search started reading it, and a match does
 * not go from one part to the next. So a line that wraps through the whole scrollback, which the
 * host decides, does not make each update read and search all of it again (docs/decisions/0081).
 */
internal const val MAX_SEARCH_TEXT_ROWS = 100

/** The text of [rows], which autowrap joins, the first of them being line [firstLine]. */
private class SearchText(val firstLine: Long, rows: List<LineSnapshot>) {
    val lastLine: Long = firstLine + rows.size - 1
    val text = LineText(rows, trimEnd = true)
}

/**
 * Finds [query] in text, in time linear in the text and the query (Knuth-Morris-Pratt), so that
 * text that the host decides cannot make a search slow. Letters are compared one UTF-16 unit at a
 * time, as [String.regionMatches] compares them when it ignores case.
 */
internal class LiteralFinder(query: SearchQuery) {
    private val ignoreCase = query.ignoreCase
    private val pattern = CharArray(query.text.length) { fold(query.text[it]) }

    /** For each prefix of [pattern], the length of its longest proper prefix that is a suffix. */
    private val failure =
        IntArray(pattern.size).also { failure ->
            var k = 0
            for (i in 1 until pattern.size) {
                while (k > 0 && pattern[i] != pattern[k]) k = failure[k - 1]
                if (pattern[i] == pattern[k]) k++
                failure[i] = k
            }
        }

    /**
     * Calls [onMatch] with the start and end of each occurrence in [text], in order, that does not
     * overlap one that [onMatch] accepted by returning true.
     */
    fun find(text: String, onMatch: (start: Int, end: Int) -> Boolean) {
        var k = 0
        var acceptedEnd = 0
        for (i in text.indices) {
            val c = fold(text[i])
            while (k > 0 && c != pattern[k]) k = failure[k - 1]
            if (c == pattern[k]) k++
            if (k == pattern.size) {
                val start = i + 1 - k
                if (start >= acceptedEnd && onMatch(start, i + 1)) acceptedEnd = i + 1
                k = failure[k - 1]
            }
        }
    }

    private fun fold(c: Char): Char =
        if (ignoreCase) Character.toLowerCase(Character.toUpperCase(c)) else c
}
