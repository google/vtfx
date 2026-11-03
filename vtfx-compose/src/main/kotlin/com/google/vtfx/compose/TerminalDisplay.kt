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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LineSnapshot
import com.google.vtfx.core.SearchIndex
import com.google.vtfx.core.SearchLines
import com.google.vtfx.core.SearchMatch
import com.google.vtfx.core.SearchQuery
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.session.SessionObserver
import com.google.vtfx.session.TerminalSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state of a [Terminal] composable's display of a session: what it shows of the scrollback, the
 * zoom, the selection, the search, the fling and the loupe of touches, when it redraws, and when
 * the terminal follows its size. The session holds the state of the terminal; this holds what
 * belongs to one display of it, and goes away with the composable.
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

    /**
     * [isScrolledBack] as a snapshot state, for the app: also changed by the screen, which may
     * switch to the alternate screen or lose its scrollback, without recomposing its readers on
     * every output.
     */
    val scrolledBackState = derivedStateOf { observeVersion().let { isScrolledBack } }

    /**
     * The text the user selected, or null. It stays on its text as the text scrolls, and is dropped
     * when that text changes or the lines are numbered again (docs/decisions/0085). Setting it
     * hides the handles until [showHandles].
     */
    var selection: TerminalSelection?
        get() = selectionState
        set(value) {
            selectionState = value
            handlesShown = false
            watchedLines = null
            // The lines of the screen now, against which the next output is compared.
            if (value != null) watch(value, session.searchLines(Long.MAX_VALUE, 1))
        }

    /**
     * Whether the selection shows the handles that drag its ends: a touch made it or last moved it
     * (docs/decisions/0097). False without a selection.
     */
    val showsHandles: Boolean
        get() = handlesShown && selectionState != null

    /**
     * Where the loupe shows the grid magnified while a touch selects, in pixels of the grid, or
     * null to hide it (docs/decisions/0100).
     */
    var loupeCenter: Offset? by mutableStateOf(null)

    /**
     * Shows the loupe over the row of [cell], at [x] within the [columns] of cells of [metrics].
     */
    fun showLoupe(cell: Cell, x: Float, columns: Int, metrics: CellMetrics) {
        loupeCenter =
            Offset(x.coerceIn(0f, columns * metrics.width), (cell.row + 0.5f) * metrics.height)
    }

    /** Hides the loupe. */
    fun hideLoupe() {
        loupeCenter = null
    }

    /** Shows the handles of the current selection, until [selection] is set again. */
    fun showHandles() {
        handlesShown = selectionState != null
    }

    /**
     * Kept apart from the selection, which the display replaces when it cuts it to the lines left
     * in the scrollback: the cut selection keeps its handles.
     */
    private var handlesShown by mutableStateOf(false)

    /**
     * Compared by identity: a long press or a drag tells that the display dropped or replaced its
     * selection when [selection] is no longer the one it set. With the default structural policy, a
     * selection equal to the current one, which a move within a cell makes, would not be stored:
     * the gesture would find the earlier one instead of its own and stop selecting.
     */
    private var selectionState: TerminalSelection? by
        mutableStateOf(null, referentialEqualityPolicy())

    /**
     * The selected lines that the screen held when the selection was made or last checked, from
     * line [WatchedLines.first]. Lines of the scrollback do not change until the lines are numbered
     * again, but those of the screen do, and they may also scroll into the scrollback before the
     * next check: the check compares these with what the lines hold then. Null without a selection.
     */
    private var watchedLines: WatchedLines? = null

    private class WatchedLines(val first: Long, val lines: List<LineSnapshot>)

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

    /**
     * Stops receiving the session's notifications, and drops what waits for the main thread.
     *
     * The flags that say a task is posted are cleared after the tasks are removed, so that
     * notifications after [attach] post them again. A notification already running on another
     * thread may still post its task after this; the task then runs once, as it would have before.
     */
    fun detach() {
        session.removeObserver(this)
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(followLock) { linesToFollow = -1L }
        restartPending.set(false)
        invalidationPending.set(false)
        synchronizationTimeoutPending.set(false)
        pendingSize = null
    }

    /** Returns what the display shows: the screen, scrolled back by [scrollOffset]. */
    fun displaySnapshot(): TerminalSnapshot = session.snapshot(scrollOffset)

    /**
     * Scrolls the display [lines] lines back into the scrollback (or forward, if negative), within
     * the scrollback. The selection stays on its text.
     */
    fun scrollBy(lines: Int) {
        val limit = session.scrollbackSize
        scrollOffset = (scrollOffset.toLong() + lines).coerceIn(0L, limit.toLong()).toInt()
    }

    /**
     * Scrolls the display [pages] screens back into the scrollback (or forward, if negative), and
     * stops a fling.
     */
    fun scrollByPages(pages: Int) {
        stopFling()
        val rows = session.size().second.toLong()
        scrollBy((pages * rows).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    /** Shows the oldest lines of the scrollback, and stops a fling. */
    fun scrollToTop() {
        stopFling()
        scrollOffset = session.scrollbackSize
    }

    /** Shows the screen, following the output, and stops a fling. */
    fun scrollToBottom() {
        stopFling()
        scrollOffset = 0
    }

    // --- Zoom (docs/decisions/0100) ---

    /**
     * How much the display is magnified, from 1 to [MAX_ZOOM]. The grid keeps its columns and rows;
     * the display shows a part of it larger.
     */
    var zoom by mutableFloatStateOf(1f)
        private set

    /** The point of the grid at the top left of the display, in pixels of the grid. */
    var pan: Offset
        get() = panState
        private set(value) {
            if (value == panState) return
            panState = value
            // The IME places its candidates at the composing text, which moved on the screen.
            inputHost?.onScreenChanged()
        }

    private var panState by mutableStateOf(Offset.Zero)

    /** The size of the grid in pixels, which [pan] stays within, or zero before the layout. */
    private var viewportSize = Size.Zero

    /** The size of a cell in pixels, for following the cursor, or zero before the layout. */
    private var cellSize = Size.Zero

    /**
     * Whether the display keeps the cursor in view as the zoomed display moves: from the user's
     * input until the user pans or zooms.
     */
    private var followsCursor = false

    /** Returns the point of the grid that [position] on the display shows. */
    fun toGrid(position: Offset): Offset = position / zoom + pan

    /** Returns where the display shows [position] of the grid. */
    fun toDisplay(position: Offset): Offset = (position - pan) * zoom

    /** Sets the size of the grid and of its cells in pixels, which the zoom stays within. */
    fun setViewport(size: Size, cell: Size) {
        viewportSize = size
        cellSize = cell
        pan = clampPan(pan, zoom)
    }

    /**
     * Zooms by [factor] about the point of the display at [from], which moves to [to], as two
     * fingers that pinch and move do, within 1 and [MAX_ZOOM] and the grid.
     */
    fun zoomBy(factor: Float, from: Offset, to: Offset) {
        followsCursor = false
        val anchor = toGrid(from)
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        zoom = newZoom
        pan = clampPan(anchor - to / newZoom, newZoom)
        inputHost?.onScreenChanged()
    }

    /**
     * Moves the zoomed display with a finger that moved by [delta] on the display, within the grid.
     * Returns the part of [delta] left when the display reached an edge of the grid.
     */
    fun panBy(delta: Offset): Offset {
        followsCursor = false
        val old = pan
        pan = clampPan(old - delta / zoom, zoom)
        return delta - (old - pan) * zoom
    }

    /** Shows the whole grid again. */
    fun resetZoom() {
        zoom = 1f
        pan = Offset.Zero
        inputHost?.onScreenChanged()
    }

    /** Sets the zoom and the pan that a saved view gives, within the grid once it is laid out. */
    private fun setZoom(newZoom: Float, newPan: Offset) {
        zoom = newZoom.coerceIn(1f, MAX_ZOOM)
        pan = clampPan(newPan, zoom)
    }

    private fun clampPan(pan: Offset, zoom: Float): Offset {
        // Before the layout, a saved pan is kept until the size is known.
        if (viewportSize == Size.Zero) return if (zoom == 1f) Offset.Zero else pan
        val room = viewportSize * (1 - 1 / zoom)
        return Offset(pan.x.coerceIn(0f, room.width), pan.y.coerceIn(0f, room.height))
    }

    /**
     * Moves the zoomed display the least that shows the cursor's cell, if it follows the cursor.
     */
    private fun revealCursor() {
        if (!followsCursor || zoom == 1f || cellSize == Size.Zero) return
        val (column, row) = session.cursorPosition()
        val displayRow = row + if (isScrolledBack) scrollOffset else 0
        val left = column * cellSize.width
        val top = displayRow * cellSize.height
        val visible = viewportSize / zoom
        var x = pan.x
        var y = pan.y
        if (left + cellSize.width > x + visible.width) x = left + cellSize.width - visible.width
        if (left < x) x = left
        if (top + cellSize.height > y + visible.height) y = top + cellSize.height - visible.height
        if (top < y) y = top
        pan = clampPan(Offset(x, y), zoom)
    }

    /**
     * The fling that goes on scrolling after a swipe, which `Terminal` runs while it is set, or
     * null. Set to stop a running fling; compared by identity, so that a fling equal to the last
     * one starts again.
     */
    var fling: Fling? by mutableStateOf(null, referentialEqualityPolicy())

    /** Stops the fling, if one is running. */
    fun stopFling() {
        fling = null
    }

    /** Whether [sendScrollKey] is sending a key, which then does not stop the fling. */
    private var sendingScrollKey = false

    /**
     * Sends an arrow key of a scroll gesture on the alternate screen. The session tells the display
     * of it as of the user's input, which returns to the bottom and drops the selection as other
     * input does, but does not stop the fling that may be sending it.
     */
    fun sendScrollKey(key: TerminalKey) {
        sendingScrollKey = true
        try {
            session.sendScrollKey(key)
        } finally {
            sendingScrollKey = false
        }
    }

    /**
     * Copies the selected text to the clipboard, and drops the selection if [clear]. Returns
     * whether there was a selection to copy and a clipboard to copy it to.
     */
    fun copySelection(clear: Boolean = true): Boolean {
        val selected = selection ?: return false
        if (clear) selection = null
        val text = selectedText(selected)
        if (text == null) {
            // The lines were numbered again or left, and the next redraw would drop it.
            selection = null
            return false
        }
        return session.copyToClipboard(text)
    }

    /**
     * Selects all the text: the scrollback and the screen, from the oldest line to the last row of
     * the screen (docs/decisions/0085).
     */
    fun selectAll() {
        val screen = session.searchLines(Long.MAX_VALUE, 1)
        selection =
            TerminalSelection(
                TextPosition(0, screen.oldestLine),
                TextPosition(
                    screen.lines.last().columns - 1,
                    screen.screenLine + screen.lines.size - 1,
                ),
                screen.generation,
                isAll = true,
            )
    }

    /**
     * Returns the text of [selected], of which the newest lines that fit in [MAX_COPIED_LENGTH]
     * characters, from at most its newest [MAX_COPIED_LINES] lines and [MAX_COPIED_CELLS] cells.
     * Returns null if the lines were numbered again since it was made, or all left the scrollback.
     */
    private fun selectedText(selected: TerminalSelection): String? {
        val end = selected.end.line
        val columns = session.size().first
        val maxLines = minOf(MAX_COPIED_LINES, MAX_COPIED_CELLS / columns)
        val from = maxOf(selected.start.line, end - maxLines + 1)
        val read = session.searchLines(from, (end - from + 1).toInt())
        if (read.generation != selected.generation || end < read.oldestLine) return null
        // Not before from, where the read starts on the screen that the selection starts below.
        val first = maxOf(from, read.firstLine)
        val lines =
            read.lines.subList((first - read.firstLine).toInt(), (end - read.firstLine + 1).toInt())
        val text = selected.text(lines, first)
        return newestThatFit(if (selected.isAll) text.trimEnd('\n') else text)
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

    // --- Search (docs/decisions/0085) ---

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
        val index = searchVersion.let { searchIndex } ?: return null
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
        stopFling()
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

    /**
     * Returns what the display shows, for a display that takes its place: the line at its top, and
     * the search (docs/decisions/0095).
     */
    fun view(): SavedView {
        val snapshot = displaySnapshot()
        return SavedView(
            sessionKey(session),
            snapshot.generation,
            topLine = if (scrollOffset == 0) null else snapshot.firstLine,
            searchIndex?.query,
            currentMatch,
            zoom,
            pan,
        )
    }

    /**
     * Shows what [view] says another display showed: the same lines and current match, if the
     * session is the same and its lines were not numbered again since, or else the bottom, and the
     * same search. Lines that left the scrollback since give way to the oldest one kept, as when
     * the display follows the scrollback.
     */
    fun restore(view: SavedView) {
        val lines = session.searchLines(Long.MAX_VALUE, 1)
        val same = view.sessionKey == sessionKey(session) && view.generation == lines.generation
        val top = view.topLine
        scrollOffset =
            if (!same || top == null) 0
            else
                (lines.screenLine - maxOf(top, lines.oldestLine))
                    .coerceIn(0L, session.scrollbackSize.toLong())
                    .toInt()
        // The match that the search keeps if it still matches.
        currentMatch = if (same) view.currentMatch else null
        search(view.query)
        // The zoom belongs to the display, not to the lines, so it stays for another session too.
        setZoom(view.zoom, view.pan)
    }

    /** Called after the user typed or edited. The display returns to the bottom. */
    fun notifyEditorChanged() {
        editorVersion++
        // Not for the arrow keys of a swipe or a fling, which move the display themselves.
        if (!sendingScrollKey) {
            stopFling()
            followsCursor = true
        }
        scrollOffset = 0
        // The user moved on from the selection (docs/decisions/0085).
        selection = null
        revealCursor()
    }

    /**
     * Drops the selection if its text changed since it was made or last checked, if the lines were
     * numbered again, or if all of it left the scrollback; cuts off the part that left
     * (docs/decisions/0085). Reads the selected lines that the screen held at the last check, so
     * that the work is bounded by the screen's rows, not by the size of the selection.
     */
    private fun checkSelection() {
        val selected = selection ?: return
        val watched = checkNotNull(watchedLines)
        val start = selected.start
        val end = selected.end
        val from = maxOf(start.line, watched.first)
        val read =
            session.searchLines(
                from,
                (end.line - from + 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
            )
        if (read.generation != selected.generation || end.line < read.oldestLine) {
            selection = null
            return
        }
        for ((index, old) in watched.lines.withIndex()) {
            val line = watched.first + index
            // A line that left the scrollback, which is cut off below.
            if (line < read.firstLine) continue
            val new = read.lines[(line - read.firstLine).toInt()]
            if (
                !sameSelectedText(
                    old,
                    new,
                    checkNotNull(selected.columnsOf(line, old.columns)),
                    line < end.line,
                )
            ) {
                selection = null
                return
            }
        }
        val kept =
            if (start.line >= read.oldestLine) selected
            else if (selected.anchor == start)
                selected.copy(anchor = TextPosition(0, read.oldestLine))
            else selected.copy(focus = TextPosition(0, read.oldestLine))
        if (kept != selected) selectionState = kept
        watch(kept, read)
    }

    /**
     * Keeps, as [watchedLines], the lines of [selected] from the first row of the screen, which
     * [read] must hold through the end of the selection.
     */
    private fun watch(selected: TerminalSelection, read: SearchLines) {
        if (read.generation != selected.generation) {
            // Made from a snapshot taken before the lines were numbered again.
            selectionState = null
            return
        }
        val first = maxOf(selected.start.line, read.screenLine)
        // Not beyond the last row, for a selection made on a taller display.
        val last = minOf(selected.end.line, read.firstLine + read.lines.size - 1)
        watchedLines =
            WatchedLines(
                first,
                if (first > last) emptyList()
                else
                    read.lines.subList(
                        (first - read.firstLine).toInt(),
                        (last - read.firstLine + 1).toInt(),
                    ),
            )
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
                checkSelection()
                updateSearch()
                // The host's echo moves the cursor after the input.
                revealCursor()
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
 * redraw, which the output brings (docs/decisions/0081).
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

/**
 * Whether [old] and [new], the same line at two times, show the same text in [columns], as copying
 * reads it: the same characters, widths, and concealed cells, and, if the selection goes on to the
 * next line ([continues]), whether autowrap joins them. Styles that do not hide text do not count,
 * so that a redraw in other colors keeps the selection.
 */
private fun sameSelectedText(
    old: LineSnapshot,
    new: LineSnapshot,
    columns: IntRange,
    continues: Boolean,
): Boolean {
    if (old.columns != new.columns) return false
    if (continues && old.isWrapped != new.isWrapped) return false
    // A wide character whose right half is selected is copied whole.
    var first = columns.first
    if (first > 0 && (old.widthAt(first) == 0 || new.widthAt(first) == 0)) first--
    for (column in first..minOf(columns.last, old.columns - 1)) {
        if (old.widthAt(column) != new.widthAt(column)) return false
        if (old.visibleTextAt(column) != new.visibleTextAt(column)) return false
    }
    return true
}

/** The most that a display magnifies the grid (docs/decisions/0100). */
internal const val MAX_ZOOM = 4f

/** The matches of a search that a display shows, and the current match, if any. */
internal class VisibleMatches(val matches: List<SearchMatch>, val current: SearchMatch?)

/** What a display needs from the view that hosts the IME connection. */
internal interface InputHost {
    fun restartInput()

    /** The screen changed, so the cursor may have moved. */
    fun onScreenChanged()
}
