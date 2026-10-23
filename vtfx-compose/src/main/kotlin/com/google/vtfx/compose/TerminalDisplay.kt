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

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.SearchIndex
import com.google.vtfx.core.SearchLines
import com.google.vtfx.core.SearchMatch
import com.google.vtfx.core.SearchQuery
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.session.SessionObserver
import com.google.vtfx.session.TerminalSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state of a [Terminal] composable's display of a session: what it shows of the scrollback, the
 * selection, the search, when it redraws, and when the terminal follows its size. The session holds
 * the state of the terminal; this holds what belongs to one display of it, and goes away with the
 * composable.
 *
 * Confined to the main thread. The session's notifications, which come on the thread that caused
 * them, are passed to the main thread.
 */
@MainThread
internal class TerminalDisplay(val session: TerminalSession) : SessionObserver {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Incremented after the screen changed, to invalidate the display. */
    private var version by mutableIntStateOf(0)

    /** Incremented when the IME edit buffer changed, to redraw the composing text. */
    private var editorVersion by mutableIntStateOf(0)

    /**
     * How many lines of the scrollback the display shows above the screen, 0 to follow the output.
     */
    var scrollOffset by mutableIntStateOf(0)
        private set

    /**
     * Whether the display shows lines of the scrollback. Not on the alternate screen, which has no
     * scrollback to show, whatever [scrollOffset] the main screen kept.
     */
    val isScrolledBack: Boolean
        get() = scrollOffset > 0 && session.scrollbackSize > 0

    /** The text the user selected on the display, or null. Dropped when the display changes. */
    var selection: TerminalSelection? by mutableStateOf(null)

    /**
     * Incremented each time the display changes and drops the selection, so that a gesture that has
     * not made a selection yet can tell that its snapshot is out of date.
     */
    var changeCount: Int = 0
        private set

    /** Receives the requests of the IME editing model, while this display's view has the focus. */
    var inputHost: InputHost? = null

    private val invalidationPending = AtomicBoolean(false)

    /**
     * The lines that entered the scrollback since the task to follow it was posted, or -1 if no
     * task is posted, so that output from another thread posts one task, not one per receive
     * (docs/design/emulation.md section 10.1). Guarded by [followLock].
     */
    private var linesToFollow = -1L
    private val followLock = Any()

    /** Whether a restart of the IME's input is posted, for the same reason. */
    private val restartPending = AtomicBoolean(false)

    /** Starts receiving the session's notifications. */
    fun attach() {
        session.addObserver(this)
    }

    /** Stops receiving the session's notifications, and drops what waits for the main thread. */
    fun detach() {
        session.removeObserver(this)
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(followLock) { linesToFollow = -1L }
        restartPending.set(false)
    }

    /** Returns what the display shows: the screen, scrolled back by [scrollOffset]. */
    fun displaySnapshot(): TerminalSnapshot = session.snapshot(scrollOffset)

    /**
     * Scrolls the display [lines] lines back into the scrollback (or forward, if negative), within
     * the scrollback. Returns whether it moved.
     */
    fun scrollBy(lines: Int): Boolean {
        val limit = session.scrollbackSize
        val next = (scrollOffset + lines).coerceIn(0, limit)
        if (next == scrollOffset) return false
        scrollOffset = next
        onDisplayChanged()
        return true
    }

    /**
     * Copies the selected text to the clipboard, and drops the selection if [clear]. Returns
     * whether there was a selection to copy and a clipboard to copy it to.
     */
    fun copySelection(clear: Boolean = true): Boolean {
        val selected = selection ?: return false
        if (clear) selection = null
        return session.copyToClipboard(selectedText(selected))
    }

    /** Selects all the text: the scrollback and the screen (docs/decisions/0031). */
    fun selectAll() {
        selection = TerminalSelection.all(displaySnapshot())
    }

    /**
     * Returns the text of [selected], of which the newest lines that fit in [MAX_COPIED_LENGTH]
     * characters. All the text is that of the newest lines, at most [MAX_COPIED_LINES] of them and
     * [MAX_COPIED_CELLS] cells.
     */
    private fun selectedText(selected: TerminalSelection): String {
        if (!selected.isAll) return newestThatFit(selected.text(displaySnapshot()))
        val columns = session.size().first
        val lines = session.lines(minOf(MAX_COPIED_LINES, MAX_COPIED_CELLS / columns))
        if (lines.isEmpty()) return ""
        val text =
            TerminalSelection.text(
                    lines,
                    Cell(0, 0),
                    Cell(lines.last().columns - 1, lines.size - 1),
                )
                .trimEnd('\n')
        return newestThatFit(text)
    }

    /**
     * Returns the newest whole lines of [text] that fit in [MAX_COPIED_LENGTH] characters, or the
     * end of its last line if that alone is longer, without splitting a surrogate pair.
     */
    private fun newestThatFit(text: String): String {
        if (text.length <= MAX_COPIED_LENGTH) return text
        // The newest lines that fit, from the start of a line, or the end of a line too long.
        val from = text.length - MAX_COPIED_LENGTH
        val lineBreak = text.indexOf('\n', from - 1)
        if (lineBreak >= 0) return text.substring(lineBreak + 1)
        return text.substring(if (text[from].isLowSurrogate()) from + 1 else from)
    }

    /**
     * Reads the version, so that a composable reading it is invalidated when the screen changes.
     */
    fun observeVersion(): Int = version

    /**
     * Reads the editor version, so that a reader is invalidated when the composing text changes.
     */
    fun observeEditorVersion(): Int = editorVersion

    // --- Search (docs/decisions/0047) ---

    /** The search of this display, or null. */
    private var searchIndex: SearchIndex? = null

    /** The match that the display moved to, or null. */
    private var currentMatch: SearchMatch? = null

    /** Incremented when the matches or the current one may have changed, to redraw them. */
    private var searchVersion by mutableIntStateOf(0)

    /** What the search found, or null if there is no search. */
    var searchResult: TerminalSearchResult? by mutableStateOf(null)
        private set

    /**
     * Searches for [query], or ends the search if null. The current match becomes the nearest one
     * at or above the current match, or above the bottom of the display if there is none, or else
     * the first one below; the display scrolls to show it.
     */
    fun search(query: SearchQuery?) {
        if (query == null) {
            searchIndex = null
            currentMatch = null
            publishSearch()
            return
        }
        val index = searchIndex?.withQuery(query) ?: SearchIndex(query)
        searchIndex = index
        readLines(index)
        val snapshot = displaySnapshot()
        val bottom = snapshot.firstLine + snapshot.rows - 1
        currentMatch =
            nearest(index.matches, currentMatch ?: SearchMatch(bottom, snapshot.columns, bottom, 0))
        reveal(index)
        publishSearch()
    }

    /** Moves to the match below the current one, or from the last to the first. */
    fun findNext() = move(1)

    /** Moves to the match above the current one, or from the first to the last. */
    fun findPrevious() = move(-1)

    private fun move(step: Int) {
        val index = searchIndex ?: return
        val matches = index.matches
        if (matches.isEmpty()) return
        val current = currentMatch
        val position = if (current == null) -1 else matches.indexOf(current)
        currentMatch =
            when {
                position >= 0 -> matches[Math.floorMod(position + step, matches.size)]
                step > 0 -> matches.first()
                else -> matches.last()
            }
        reveal(index)
        publishSearch()
    }

    /** Returns the matches that [snapshot] shows, oldest first, and the current one, if any. */
    fun visibleMatches(snapshot: TerminalSnapshot): VisibleMatches? {
        searchVersion
        val index = searchIndex ?: return null
        val matches = index.matches
        val top = snapshot.firstLine
        val bottom = top + snapshot.rows - 1
        // The matches are ordered and do not overlap, so their last lines are ordered too.
        var first = 0
        var last = matches.size
        while (first < last) {
            val middle = (first + last) / 2
            if (matches[middle].lastLine < top) first = middle + 1 else last = middle
        }
        var end = first
        while (end < matches.size && matches[end].firstLine <= bottom) end++
        return VisibleMatches(matches.subList(first, end), currentMatch)
    }

    /** Brings the search up to date after the terminal changed, without scrolling. */
    private fun updateSearch() {
        val index = searchIndex ?: return
        readLines(index)
        val current = currentMatch
        if (current != null && current !in index.matches) {
            currentMatch = nearest(index.matches, current)
        }
        publishSearch()
    }

    private fun readLines(index: SearchIndex) {
        readSearchLines(index, SEARCH_READ_LINES, session::searchLines)
    }

    /**
     * Returns the last of [matches] that starts at or before [anchor], or the first one if none
     * does, or null if there are none.
     */
    private fun nearest(matches: List<SearchMatch>, anchor: SearchMatch): SearchMatch? =
        matches.lastOrNull { !anchor.startsBefore(it) } ?: matches.firstOrNull()

    /** Scrolls the display to show the current match, near its middle, if it does not show it. */
    private fun reveal(index: SearchIndex) {
        val match = currentMatch ?: return
        val snapshot = displaySnapshot()
        val top = snapshot.firstLine
        if (match.firstLine >= top && match.lastLine < top + snapshot.rows) return
        val offset = index.screenLine - (match.firstLine - snapshot.rows / 2)
        scrollBy(offset.coerceIn(0L, session.scrollbackSize.toLong()).toInt() - scrollOffset)
    }

    private fun publishSearch() {
        searchVersion++
        val index = searchIndex
        val current = currentMatch
        val result =
            if (index == null) null
            else
                TerminalSearchResult(
                    matchCount = index.matches.size,
                    isMatchCountCapped = index.isCapped,
                    currentMatchIndex =
                        if (current == null) null else index.matches.indexOf(current),
                )
        if (result != searchResult) searchResult = result
    }

    /** Called after the user typed or edited. The display returns to the bottom. */
    fun notifyEditorChanged() {
        editorVersion++
        scrollOffset = 0
        onDisplayChanged()
    }

    /** Counts a change of the display in [changeCount], and drops the selection. */
    private fun onDisplayChanged() {
        changeCount++
        selection = null
    }

    /** The size waiting for [applyPendingSize], or null. */
    private var pendingSize: GridSize? = null
    private var sized = false
    private val applyPendingSize = Runnable {
        val size = pendingSize ?: return@Runnable
        pendingSize = null
        session.resize(size.columns, size.rows, size.cellWidth, size.cellHeight)
    }

    /** A size of the grid, and of a cell in pixels. */
    private class GridSize(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

    /**
     * Requests the size that fits the display, with cells of [cellWidth] by [cellHeight] pixels. A
     * change is applied after the size stays the same for [RESIZE_DELAY_MILLIS], so that an
     * animation (the keyboard showing, for example) resizes the terminal and notifies the host once
     * (requirement 5.5). The first size is applied at once.
     */
    fun resize(columns: Int, rows: Int, cellWidth: Int = 0, cellHeight: Int = 0) {
        if (!sized) {
            sized = true
            session.resize(columns, rows, cellWidth, cellHeight)
            return
        }
        pendingSize = GridSize(columns, rows, cellWidth, cellHeight)
        mainHandler.removeCallbacks(applyPendingSize)
        mainHandler.postDelayed(applyPendingSize, RESIZE_DELAY_MILLIS)
    }

    // --- SessionObserver ---

    @AnyThread
    override fun onOutput(scrolledLines: Long, scrollbackShrank: Boolean, synchronizing: Boolean) {
        if (scrolledLines > 0 || scrollbackShrank) {
            val post =
                synchronized(followLock) {
                    val pending = linesToFollow
                    linesToFollow =
                        if (pending < 0) scrolledLines
                        else if (pending > Long.MAX_VALUE - scrolledLines) Long.MAX_VALUE
                        else pending + scrolledLines
                    pending < 0
                }
            if (post) {
                runOnMain {
                    val lines =
                        synchronized(followLock) { linesToFollow.also { linesToFollow = -1L } }
                    if (lines >= 0) followScrollback(lines)
                }
            }
        }
        if (synchronizing) {
            // Mode 2026: the host is in the middle of an update. Draw when it ends, or after a
            // timeout from the first output not drawn, in case it never ends.
            if (synchronizationTimeoutPending.compareAndSet(false, true)) {
                mainHandler.postDelayed(synchronizationTimeout, SYNCHRONIZED_OUTPUT_TIMEOUT_MILLIS)
            }
        } else {
            synchronizationTimeoutPending.set(false)
            mainHandler.removeCallbacks(synchronizationTimeout)
            invalidate()
        }
    }

    @AnyThread override fun onScreenChanged() = invalidate()

    @AnyThread override fun onUserInput() = runOnMain { notifyEditorChanged() }

    @AnyThread
    override fun onRestartInput() {
        if (restartPending.compareAndSet(false, true)) {
            runOnMain {
                restartPending.set(false)
                inputHost?.restartInput()
            }
        }
    }

    /** Whether [synchronizationTimeout] is posted, so that more output does not postpone it. */
    private val synchronizationTimeoutPending = AtomicBoolean(false)
    private val synchronizationTimeout = Runnable {
        synchronizationTimeoutPending.set(false)
        invalidate()
    }

    /**
     * Keeps showing the same lines while the user looks back into the scrollback, after
     * [scrolledLines] more lines entered it. When the lines shown are no longer kept, shows the
     * oldest ones that are, or the screen if the scrollback was cleared.
     */
    private fun followScrollback(scrolledLines: Long) {
        if (scrollOffset == 0) return
        val size = session.scrollbackSize
        scrollOffset = minOf(scrollOffset + scrolledLines, size.toLong()).toInt()
    }

    @AnyThread
    private fun invalidate() {
        if (invalidationPending.compareAndSet(false, true)) {
            runOnMain {
                invalidationPending.set(false)
                version++
                onDisplayChanged()
                updateSearch()
                inputHost?.onScreenChanged()
            }
        }
    }

    /** Runs [action] now if on the main thread, or posts it there. */
    // Lint does not see that the direct call happens only on the main thread.
    @SuppressLint("ThreadConstraint")
    @AnyThread
    private fun runOnMain(@MainThread action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        /** How long the display size must stay the same before the terminal is resized. */
        const val RESIZE_DELAY_MILLIS = 150L

        /** How long to wait for the end of a synchronized update (mode 2026) before drawing. */
        const val SYNCHRONIZED_OUTPUT_TIMEOUT_MILLIS = 200L

        /**
         * The lines of the scrollback that a search reads with the session's lock held, so that
         * reading the whole scrollback does not hold up output for long.
         */
        const val SEARCH_READ_LINES = 1000

        /** The most lines that copying all the text reads. */
        const val MAX_COPIED_LINES = 10_000

        /** The most cells of the lines that copying all the text reads, to bound its memory. */
        const val MAX_COPIED_CELLS = 2 * 1024 * 1024

        /**
         * The longest text that copying copies, all the text or a selection of the display. A clip
         * goes to the system in one Binder transaction, which fails above about 1 MB; this is 512
         * KB of UTF-16.
         */
        const val MAX_COPIED_LENGTH = 256 * 1024
    }
}

/**
 * Brings [index] up to date with the lines that [read] returns, [minLines] at a time, as
 * `TerminalSession.searchLines` returns them.
 *
 * Each read holds the session's lock only for a part of the scrollback, so output may scroll the
 * terminal between two reads, and faster than they go. So that a redraw ends, it reads at most
 * about twice the lines that the terminal kept at the first read, and leaves the rest to the next
 * redraw, which the output brings (docs/decisions/0062).
 */
internal fun readSearchLines(
    index: SearchIndex,
    minLines: Int,
    read: (from: Long, minLines: Int) -> SearchLines,
) {
    var reads = 0L
    var maxReads = Long.MAX_VALUE
    while (true) {
        val lines = read(index.nextLine, minLines)
        // Enough to start over, once, after the lines were numbered again, and read them all.
        if (reads == 0L) maxReads = 2 * ((lines.screenLine - lines.oldestLine) / minLines + 2)
        reads++
        if (!index.update(lines) || reads >= maxReads) return
    }
}

/** The matches of a search that a display shows, and the current match, if any. */
internal class VisibleMatches(val matches: List<SearchMatch>, val current: SearchMatch?)

/** What a display needs from the view that hosts the IME connection. */
internal interface InputHost {
    fun restartInput()

    /** The screen changed, so the cursor may have moved. */
    fun onScreenChanged()
}
