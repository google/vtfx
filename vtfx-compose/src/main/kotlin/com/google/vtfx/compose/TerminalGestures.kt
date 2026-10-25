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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseButton
import com.google.vtfx.gate.MouseReport
import com.google.vtfx.session.TerminalSession
import kotlin.math.abs

/**
 * What touches do on the grid (docs/decisions/0085); [terminalMouse] handles a mouse:
 *
 * - A tap shows the keyboard, and if the host asked for mouse events, it is also a click. A tap
 *   drops the selection.
 * - A long press selects the word under it, and moving the finger then extends the selection.
 *   [onSelectionEnd] is called when the finger is lifted, with the position to show a menu at.
 * - A vertical swipe scrolls: as the mouse wheel if the host asked for mouse events, as the arrow
 *   keys on the alternate screen (as xterm's alternateScroll), and through the scrollback
 *   otherwise.
 * - While the display shows the scrollback, the host does not get taps and swipes as the mouse
 *   (docs/decisions/0085): they act as if it had not asked.
 */
internal fun Modifier.terminalGestures(
    display: TerminalDisplay,
    metrics: CellMetrics,
    onTap: () -> Unit,
    /**
     * Called for a tap on a cell of the display [TerminalSnapshot], with the kind of pointer and
     * the modifier keys held, unless the host gets the tap as a click. Returns whether a link took
     * the tap; if not, [onTap] is called.
     */
    onLinkTap: (TerminalSnapshot, Cell, PointerType, PointerKeyboardModifiers) -> Boolean,
    onSelectionEnd: (Offset) -> Unit,
): Modifier {
    val session = display.session
    return this.pointerInput(display, metrics) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A mouse, which terminalMouse handled and consumed.
                if (down.isConsumed) return@awaitEachGesture
                val pressed =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change =
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull Press.Moved
                            if (change.changedToUp()) return@withTimeoutOrNull Press.Tap
                            if (
                                change.isConsumed ||
                                    (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                            ) {
                                return@withTimeoutOrNull Press.Moved
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") Press.Moved
                    }
                when (pressed) {
                    Press.Tap -> {
                        display.selection = null
                        val linkTapped =
                            if (isReportingTouches(display)) {
                                // The host asked for the clicks, links included.
                                val (column, row) = cellAt(down.position, metrics, session)
                                session.sendMouse(
                                    MouseReport(MouseButton.Left, MouseAction.Press, column, row)
                                )
                                session.sendMouse(
                                    MouseReport(MouseButton.Left, MouseAction.Release, column, row)
                                )
                                false
                            } else {
                                val snapshot = display.displaySnapshot()
                                onLinkTap(
                                    snapshot,
                                    displayCell(down.position, metrics, snapshot),
                                    down.type,
                                    currentEvent.keyboardModifiers,
                                )
                            }
                        if (!linkTapped) onTap()
                    }
                    Press.Moved -> {}
                    null -> {
                        // A long press: select the word, then follow the finger until it is lifted.
                        val snapshot = display.displaySnapshot()
                        val start = displayCell(down.position, metrics, snapshot)
                        val word = TerminalSelection.word(snapshot, start)
                        var selected: TerminalSelection? = word
                        display.selection = word
                        var position = down.position
                        while (true) {
                            // The Initial pass comes before the scroll detector, which must not see
                            // these moves.
                            val change =
                                awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                                    it.id == down.id
                                } ?: break
                            // Before consuming it, which changedToUp would then not see.
                            val lifted = change.changedToUpIgnoreConsumed()
                            change.consume()
                            if (lifted) break
                            // The selection is dropped when the display changes, and [snapshot] is
                            // then out of date: the rest of the gesture selects nothing.
                            if (display.selection !== selected) selected = null
                            if (selected == null) continue
                            position = change.position
                            val cell = displayCell(position, metrics, snapshot)
                            selected =
                                if (cell == start) word
                                else {
                                    TerminalSelection(
                                        if (cell < start) word.end else word.start,
                                        cell,
                                    )
                                }
                            display.selection = selected
                        }
                        if (selected != null && display.selection === selected)
                            onSelectionEnd(position)
                    }
                }
            }
        }
        .pointerInput(display, metrics) {
            var remainder = 0f
            // Decided when the swipe starts, so that a swipe that scrolls down to the bottom of
            // the scrollback stops there and does not go on to scroll the host.
            var reporting = false
            detectVerticalDragGestures(
                onDragStart = {
                    remainder = 0f
                    reporting = isReportingTouches(display)
                },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    remainder += dragAmount
                    val lines = (remainder / metrics.height).toInt()
                    if (lines != 0) {
                        remainder -= lines * metrics.height
                        scroll(display, lines, cellAt(change.position, metrics, session), reporting)
                    }
                },
            )
        }
}

/**
 * Whether the host gets touches as the mouse: it asked for mouse reports, and the display does not
 * show the scrollback, whose rows a report, which carries a row of the screen, cannot tell.
 */
internal fun isReportingTouches(display: TerminalDisplay): Boolean =
    !display.isScrolledBack && display.session.modes().mouseTracking != MouseTracking.None

private enum class Press {
    Tap,
    Moved,
}

/** Returns the 0-based cell of [snapshot] at [position]. */
internal fun displayCell(position: Offset, metrics: CellMetrics, snapshot: TerminalSnapshot): Cell =
    displayCell(position, metrics, snapshot.columns, snapshot.rows)

private fun displayCell(position: Offset, metrics: CellMetrics, columns: Int, rows: Int): Cell =
    Cell(
        (position.x / metrics.width).toInt().coerceIn(0, columns - 1),
        (position.y / metrics.height).toInt().coerceIn(0, rows - 1),
    )

/**
 * Scrolls by [lines]: positive when the finger moves down, which shows earlier content, as a scroll
 * wheel turned up does. As the mouse wheel if [reporting].
 */
private fun scroll(display: TerminalDisplay, lines: Int, cell: Pair<Int, Int>, reporting: Boolean) {
    val session = display.session
    when {
        reporting ->
            repeat(abs(lines)) {
                val button = if (lines > 0) MouseButton.WheelUp else MouseButton.WheelDown
                session.sendMouse(MouseReport(button, MouseAction.Press, cell.first, cell.second))
            }
        session.isAlternateScreen() ->
            repeat(abs(lines)) {
                session.sendScrollKey(if (lines > 0) TerminalKey.Up else TerminalKey.Down)
            }
        else -> display.scrollBy(lines)
    }
}

/** Returns the 1-based column and row of the cell at [position], within the screen. */
internal fun cellAt(
    position: Offset,
    metrics: CellMetrics,
    session: TerminalSession,
): Pair<Int, Int> {
    val (columns, rows) = session.size()
    val cell = displayCell(position, metrics, columns, rows)
    return Pair(cell.column + 1, cell.row + 1)
}
