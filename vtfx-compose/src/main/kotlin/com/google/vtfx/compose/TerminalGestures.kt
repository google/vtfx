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

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseButton
import com.google.vtfx.gate.MouseReport
import com.google.vtfx.session.TerminalSession
import kotlin.math.abs
import kotlin.math.sign

/**
 * What touches that stay do on the grid, in pixels of the grid, inside its zoom
 * (docs/decisions/0085); [terminalPanAndZoom] handles touches that move, and [terminalMouse] a
 * mouse:
 *
 * - A tap shows the keyboard, and if the host asked for mouse events, it is also a click. A tap
 *   drops the selection.
 * - A long press selects the word under it, and moving the finger then extends the selection by
 *   words. [onSelectionStart] is called when it selects, and [onSelectionEnd] when the finger is
 *   lifted, with the position to show a menu at.
 * - While the display shows the scrollback, the host does not get taps as the mouse
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
    onSelectionStart: () -> Unit,
    onSelectionEnd: (Offset) -> Unit,
): Modifier {
    val session = display.session
    return this.pointerInput(display, metrics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A mouse, which terminalMouse handled and consumed, or a touch that stopped a
            // fling, which terminalPanAndZoom consumed.
            if (down.isConsumed) return@awaitEachGesture
            when (awaitPress(down)) {
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
                    // A long press: select the word, then follow the finger by words until
                    // it is lifted.
                    val snapshot = display.displaySnapshot()
                    val pressed = displayCell(down.position, metrics, snapshot)
                    val word = TerminalSelection.word(snapshot, pressed)
                    var selected: TerminalSelection? = word
                    display.selection = word
                    onSelectionStart()
                    var position = down.position
                    // The loupe follows the finger until it is lifted (docs/decisions/0100).
                    display.showLoupe(pressed, position.x, snapshot.columns, metrics)
                    try {
                        while (true) {
                            // Consumed in the Initial pass, so that terminalPanAndZoom, which
                            // reads the moves in the Main pass, leaves them.
                            val change =
                                awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                                    it.id == down.id
                                } ?: break
                            // Before consuming it, which changedToUp would then not see.
                            val lifted = change.changedToUpIgnoreConsumed()
                            change.consume()
                            if (lifted) break
                            // The display drops the selection when its text changes: the rest
                            // of
                            // the gesture selects nothing (docs/decisions/0085).
                            if (display.selection !== selected) selected = null
                            if (selected == null) continue
                            position = change.position
                            // Output may have scrolled the text since the press: the finger is
                            // over
                            // what the display shows now.
                            val now = display.displaySnapshot()
                            if (now.generation != word.generation) {
                                selected = null
                                continue
                            }
                            val cell = displayCell(position, metrics, now)
                            selected = word.extendedTo(now, cell)
                            display.selection = selected
                            display.showLoupe(cell, position.x, now.columns, metrics)
                        }
                    } finally {
                        // Also when the gesture is cancelled.
                        display.hideLoupe()
                    }
                    if (selected != null && display.selection === selected) onSelectionEnd(position)
                }
            }
        }
    }
}

/**
 * What touches that move do on the grid, in pixels of the display, outside its zoom
 * (docs/decisions/0100); [terminalGestures], inside the zoom, takes taps and long presses:
 *
 * - Two fingers zoom the display about their middle and move it with it, from 1 to [MAX_ZOOM]
 *   times; the grid keeps its columns and rows. Lifting one finger lets the other go on moving it.
 * - One finger moves the zoomed display first, and scrolls with the rest when the display reaches
 *   its top or bottom: as the mouse wheel if the host asked for mouse events, as the arrow keys on
 *   the alternate screen (as xterm's alternateScroll), and through the scrollback otherwise. Not
 *   zoomed, only vertical moves count, so that an ancestor can take horizontal ones.
 * - The move goes on after the finger is lifted, as a fling that [FlingRunner] runs and the next
 *   touch stops; that touch is then neither a tap nor a long press.
 * - A long press, which [terminalGestures] takes before its finger moves, keeps the finger.
 * - While the display shows the scrollback, the host does not get the moves as the mouse
 *   (docs/decisions/0085).
 */
internal fun Modifier.terminalPanAndZoom(display: TerminalDisplay, metrics: CellMetrics): Modifier =
    pointerInput(display, metrics) {
        awaitEachGesture {
            // In the Initial pass, which reaches this modifier, outside the zoom, before the
            // detectors of taps and long presses inside it, so that what this consumes there
            // keeps them from acting.
            val down = awaitFirstDown(requireUnconsumed = false, PointerEventPass.Initial)
            // A mouse, which terminalMouse handled and consumed.
            if (down.isConsumed) return@awaitEachGesture
            if (display.fling != null) {
                display.stopFling()
                down.consume()
            }
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(down)
            var pointer = down.id
            var pinching = false
            var scroller: SwipeScroller? = null
            // The cell of the finger, which a wheel report carries.
            var cell = Pair(1, 1)
            while (true) {
                val initial = awaitPointerEvent(PointerEventPass.Initial)
                if (!pinching && initial.changes.count { it.pressed } >= 2) {
                    // A second finger turns a swipe into a pinch, which does not fling.
                    pinching = true
                }
                if (pinching) {
                    if (initial.changes.none { it.pressed }) return@awaitEachGesture
                    val from = initial.calculateCentroid(useCurrent = false)
                    val to = initial.calculateCentroid(useCurrent = true)
                    if (from.isSpecified && to.isSpecified) {
                        display.zoomBy(initial.calculateZoom(), from, to)
                    }
                    initial.changes.forEach { it.consume() }
                    continue
                }
                // The same event, after the detectors inside the zoom saw it.
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change =
                    event.changes.firstOrNull { it.id == pointer } ?: return@awaitEachGesture
                if (change.changedToUpIgnoreConsumed()) {
                    val other = event.changes.firstOrNull { it.pressed }
                    if (other != null) {
                        pointer = other.id
                        continue
                    }
                    val current = scroller ?: return@awaitEachGesture
                    velocity.addPointerInputChange(change)
                    val maximum = viewConfiguration.maximumFlingVelocity
                    val speed = velocity.calculateVelocity(Velocity(maximum, maximum))
                    // Not zoomed, the display has no room to move across.
                    val fling =
                        if (display.zoom == 1f) Offset(0f, speed.y) else Offset(speed.x, speed.y)
                    display.startFling(fling, current)
                    return@awaitEachGesture
                }
                // With the moves that the platform batched into this event.
                velocity.addPointerInputChange(change)
                if (scroller == null) {
                    // A long press selecting, which consumes the moves of its finger in the
                    // Initial pass inside the zoom.
                    if (change.isConsumed && !change.changedToDownIgnoreConsumed()) {
                        return@awaitEachGesture
                    }
                    val moved = change.position - down.position
                    val beyond = beyondSlop(moved, display.zoom != 1f, viewConfiguration.touchSlop)
                    if (beyond == null) continue
                    // Decided when the swipe starts, so that a swipe that scrolls down to the
                    // bottom of the scrollback stops there and does not go on to scroll the host.
                    val scrolling = SwipeScrolling.of(display.session, isReportingTouches(display))
                    val started =
                        SwipeScroller(display, metrics, scrolling) { lines ->
                            scroll(display, lines, cell, scrolling)
                        }
                    scroller = started
                    change.consume()
                    cell = cellAt(display.toGrid(change.position), metrics, display.session)
                    started.drag(beyond)
                    continue
                }
                // Before consuming it, which leaves no change to read.
                val moved = change.positionChange()
                change.consume()
                cell = cellAt(display.toGrid(change.position), metrics, display.session)
                scroller.drag(if (display.zoom == 1f) Offset(0f, moved.y) else moved)
            }
        }
    }

/**
 * Returns the part of [moved] beyond the touch slop of [slop] pixels, or null if it is within it:
 * in any direction if [anyDirection], else only vertically, as a vertical scroller decides.
 */
internal fun beyondSlop(moved: Offset, anyDirection: Boolean, slop: Float): Offset? {
    if (anyDirection) {
        val distance = moved.getDistance()
        if (distance <= slop) return null
        return moved * ((distance - slop) / distance)
    }
    if (abs(moved.y) <= slop) return null
    return Offset(0f, moved.y - sign(moved.y) * slop)
}

/**
 * A fling that goes on moving as [scroller] after a swipe, from the finger's [velocity] in pixels
 * per second, positive rightward and downward (docs/decisions/0100). Each axis decelerates by
 * itself; [axesRunning] counts those still moving.
 */
internal class Fling(val velocity: Offset, val scroller: SwipeScroller) {
    var axesRunning = (if (velocity.x != 0f) 1 else 0) + (if (velocity.y != 0f) 1 else 0)
}

/**
 * How a swipe, and the fling after it, scroll once the zoomed display reaches its top or bottom,
 * decided when the swipe starts (docs/design/compose-input.md section 2).
 */
internal enum class SwipeScrolling {
    /** As the mouse wheel, which the host asked for. */
    Wheel,

    /** As the arrow keys, on the alternate screen, as xterm's alternateScroll. */
    ArrowKeys,

    /** Through the scrollback of the main screen. */
    Scrollback;

    companion object {
        /**
         * How a swipe that starts now on [session]'s screen scrolls, as the wheel if [reporting].
         */
        fun of(session: TerminalSession, reporting: Boolean): SwipeScrolling =
            when {
                reporting -> Wheel
                session.isAlternateScreen() -> ArrowKeys
                else -> Scrollback
            }
    }
}

/**
 * Moves the zoomed display, and scrolls with what is left, by the distance that a finger, or two on
 * a touchpad, move in one swipe and in the fling after it, a line per cell height on the display,
 * with [scrollLines]: positive toward earlier content. It scrolls as [scrolling], which the swipe
 * decided when it started. Through the scrollback, it stops at the ends of the scrollback. As the
 * arrow keys or through the scrollback, it stops when the host switches to the other screen, which
 * the swipe did not scroll: arrow keys would be input that the user did not give to the program on
 * the main screen, or to the one that took the alternate screen.
 */
internal class SwipeScroller(
    private val display: TerminalDisplay,
    private val metrics: CellMetrics,
    private val scrolling: SwipeScrolling,
    private val scrollLines: (lines: Int) -> Unit,
) {
    /** The distance moved since the last whole line. */
    private var remainder = 0f

    /**
     * Moves by the [delta] that the finger moved on the display, positive rightward and downward.
     */
    fun drag(delta: Offset) {
        val left = display.panBy(delta)
        val unused = scrollBy(left.y)
    }

    /**
     * Moves the zoomed display across by [pixels] of a fling. Returns the pixels used: 0 at the
     * edge of the grid, which stops the fling across.
     */
    fun flingAcross(pixels: Float): Float = pixels - display.panBy(Offset(pixels, 0f)).x

    /**
     * Moves the zoomed display down by [pixels] of a fling, and scrolls with what is left, with the
     * finger where it was lifted. Returns the pixels used: 0 at the end of the scrollback that it
     * moves toward, which stops the fling.
     */
    fun flingDown(pixels: Float): Float {
        val left = display.panBy(Offset(0f, pixels)).y
        return pixels - left + scrollBy(left)
    }

    /** Scrolls by [pixels] on the display, positive downward, and returns the pixels used. */
    private fun scrollBy(pixels: Float): Float {
        if (pixels == 0f) return 0f
        val session = display.session
        val stopped =
            when (scrolling) {
                SwipeScrolling.Wheel -> false
                SwipeScrolling.ArrowKeys -> !session.isAlternateScreen()
                SwipeScrolling.Scrollback -> {
                    val offset = display.scrollOffset
                    session.isAlternateScreen() ||
                        (pixels > 0 && offset >= session.scrollbackSize) ||
                        (pixels < 0 && offset == 0)
                }
            }
        if (stopped) {
            remainder = 0f
            return 0f
        }
        // A line moves with the finger also when the display is zoomed.
        val lineHeight = metrics.height * display.zoom
        remainder += pixels
        val lines = (remainder / lineHeight).toInt()
        if (lines != 0) {
            remainder -= lines * lineHeight
            scrollLines(lines)
        }
        return pixels
    }
}

/**
 * Runs [display]'s fling while it is set, each axis with Android's deceleration
 * ([ScrollableDefaults]), and clears it when both stop. A composable of its own, so that a fling
 * starting or stopping recomposes only this.
 */
@Composable
internal fun FlingRunner(display: TerminalDisplay) {
    val behavior = ScrollableDefaults.flingBehavior()
    val fling = display.fling
    // One effect per axis, as the module launches no coroutines of its own (docs/decisions/0100).
    LaunchedEffect(display, fling, behavior) {
        if (fling == null || fling.velocity.y == 0f) return@LaunchedEffect
        runAxis(display, fling, behavior, fling.velocity.y, fling.scroller::flingDown)
    }
    LaunchedEffect(display, fling, behavior) {
        if (fling == null || fling.velocity.x == 0f) return@LaunchedEffect
        runAxis(display, fling, behavior, fling.velocity.x, fling.scroller::flingAcross)
    }
}

private suspend fun runAxis(
    display: TerminalDisplay,
    fling: Fling,
    behavior: FlingBehavior,
    velocity: Float,
    move: (Float) -> Float,
) {
    val scope =
        object : ScrollScope {
            override fun scrollBy(pixels: Float): Float = move(pixels)
        }
    val unused = with(behavior) { scope.performFling(velocity) }
    fling.axesRunning--
    if (fling.axesRunning == 0 && display.fling === fling) display.fling = null
}

/**
 * Whether the host gets touches as the mouse: it asked for mouse reports, and the display does not
 * show the scrollback, whose rows a report, which carries a row of the screen, cannot tell.
 */
internal fun isReportingTouches(display: TerminalDisplay): Boolean =
    !display.isScrolledBack && display.session.modes().mouseTracking != MouseTracking.None

/** How a touch that went down ended, before the long-press timeout. */
internal enum class Press {
    /** Lifted without moving beyond the touch slop. */
    Tap,

    /** Moved beyond the touch slop, consumed by another handler, or gone. */
    Moved,
}

/**
 * Follows the touch that went down with [down] until it is lifted or moves, and returns how it
 * ended, or null if it was held for the long-press timeout without either: a long press.
 */
internal suspend fun AwaitPointerEventScope.awaitPress(down: PointerInputChange): Press? =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val change =
                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull Press.Moved
            if (change.changedToUp()) return@withTimeoutOrNull Press.Tap
            if (
                change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                return@withTimeoutOrNull Press.Moved
            }
        }
        @Suppress("UNREACHABLE_CODE") Press.Moved
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
 * Scrolls by [lines] as [scrolling]: positive when the finger moves down, which shows earlier
 * content, as a scroll wheel turned up does. As the mouse wheel at [cell].
 */
private fun scroll(
    display: TerminalDisplay,
    lines: Int,
    cell: Pair<Int, Int>,
    scrolling: SwipeScrolling,
) {
    val session = display.session
    when (scrolling) {
        SwipeScrolling.Wheel ->
            repeat(session.scrollEventsFor(lines)) {
                val button = if (lines > 0) MouseButton.WheelUp else MouseButton.WheelDown
                session.sendMouse(MouseReport(button, MouseAction.Press, cell.first, cell.second))
            }
        SwipeScrolling.ArrowKeys ->
            repeat(session.scrollEventsFor(lines)) {
                display.sendScrollKey(if (lines > 0) TerminalKey.Up else TerminalKey.Down)
            }
        SwipeScrolling.Scrollback -> display.scrollBy(lines)
    }
}

/**
 * The wheel reports or arrow keys that one event of the user sends for [count] notches or lines: as
 * many, but at most the rows of the screen, so that a device that reports a huge move does not make
 * the main thread send reports for ever (docs/design/compose-input.md section 3). The host can do
 * nothing with more than a screen of scrolling at once.
 */
internal fun TerminalSession.scrollEventsFor(count: Int): Int =
    abs(count.toLong()).coerceAtMost(size().second.toLong()).toInt()

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
