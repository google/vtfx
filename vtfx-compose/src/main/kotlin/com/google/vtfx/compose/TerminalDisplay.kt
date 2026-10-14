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
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.session.SessionObserver
import com.google.vtfx.session.TerminalSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state of a [Terminal] composable's display of a session: what it shows of the scrollback, the
 * selection, when it redraws, and when the terminal follows its size. The session holds the state
 * of the terminal; this holds what belongs to one display of it, and goes away with the composable.
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

    /** Starts receiving the session's notifications. */
    fun attach() {
        session.addObserver(this)
    }

    /** Stops receiving the session's notifications, and drops what waits for the main thread. */
    fun detach() {
        session.removeObserver(this)
        mainHandler.removeCallbacksAndMessages(null)
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
        changeCount++
        selection = null
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
     * Returns the text of [selected]. All the text is that of the newest lines, at most
     * [MAX_COPIED_LINES] of them and [MAX_COPIED_CELLS] cells, of which the newest that fit in
     * [MAX_COPIED_LENGTH] characters.
     */
    private fun selectedText(selected: TerminalSelection): String {
        if (!selected.isAll) return selected.text(displaySnapshot())
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

    /** Called after the user typed or edited. The display returns to the bottom. */
    fun notifyEditorChanged() {
        editorVersion++
        scrollOffset = 0
        changeCount++
        selection = null
    }

    /** The size waiting for [applyPendingSize], or null. */
    private var pendingSize: Pair<Int, Int>? = null
    private var sized = false
    private val applyPendingSize = Runnable {
        val (columns, rows) = pendingSize ?: return@Runnable
        pendingSize = null
        session.resize(columns, rows)
    }

    /**
     * Requests the size that fits the display. A change is applied after the size stays the same
     * for [RESIZE_DELAY_MILLIS], so that an animation (the keyboard showing, for example) resizes
     * the terminal and notifies the host once (requirement 5.5). The first size is applied at once.
     */
    fun resize(columns: Int, rows: Int) {
        if (!sized) {
            sized = true
            session.resize(columns, rows)
            return
        }
        pendingSize = Pair(columns, rows)
        mainHandler.removeCallbacks(applyPendingSize)
        mainHandler.postDelayed(applyPendingSize, RESIZE_DELAY_MILLIS)
    }

    // --- SessionObserver ---

    @AnyThread
    override fun onOutput(scrolledLines: Long, scrollbackShrank: Boolean, synchronizing: Boolean) {
        if (scrolledLines > 0 || scrollbackShrank) runOnMain { followScrollback(scrolledLines) }
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

    @AnyThread override fun onRestartInput() = runOnMain { inputHost?.restartInput() }

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
                changeCount++
                selection = null
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

        /** The most lines that copying all the text reads. */
        const val MAX_COPIED_LINES = 10_000

        /** The most cells of the lines that copying all the text reads, to bound its memory. */
        const val MAX_COPIED_CELLS = 2 * 1024 * 1024

        /**
         * The longest text that copying all the text copies. A clip goes to the system in one
         * Binder transaction, which fails above about 1 MB; this is 512 KB of UTF-16.
         */
        const val MAX_COPIED_LENGTH = 256 * 1024
    }
}

/** What a display needs from the view that hosts the IME connection. */
internal interface InputHost {
    fun restartInput()

    /** The screen changed, so the cursor may have moved. */
    fun onScreenChanged()
}
