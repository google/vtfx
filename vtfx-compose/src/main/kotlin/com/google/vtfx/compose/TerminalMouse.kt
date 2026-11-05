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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseButton
import com.google.vtfx.gate.MouseReport
import kotlin.math.pow

/**
 * What a mouse does on the grid (docs/decisions/0085). Other pointers are left to
 * [terminalGestures], which this modifier must come before, so that it sees the events first and
 * consumes the mouse's.
 *
 * When the host asks for mouse reports, the buttons, the wheel, and the motion that the host asked
 * for are reported, unless Shift is held, which leaves the mouse to the terminal as xterm does, or
 * the display shows the scrollback, whose rows the reports cannot tell from the screen's.
 * Otherwise:
 * - The primary button selects: a drag selects the cells it goes over, a double click selects a
 *   word and a triple click a line, and the drag that follows extends them by words or lines; a
 *   click with Shift extends the selection by the unit that made it. A click without a drag is a
 *   tap: [onClick]. A click with Ctrl on a link calls [onLinkClick] instead, and a drag from it
 *   does nothing.
 * - The secondary button calls [onContextClick].
 * - The wheel scrolls as a swipe does, in steps of [WHEEL_LINES] lines a notch.
 *
 * A two-finger swipe on a touchpad, which Compose gives as a pan of the mouse, scrolls as a swipe
 * on the grid does, and is reported as the wheel when the host asks for mouse reports.
 */
internal fun Modifier.terminalMouse(
    display: TerminalDisplay,
    metrics: CellMetrics,
    /** The padding around the grid within this modifier's bounds, where the mouse also acts. */
    padding: PaddingValues,
    layoutDirection: LayoutDirection,
    /** A click that is not on a link, or a click that the host got with the primary button. */
    onClick: () -> Unit,
    /** The clickable link at a cell of the display [TerminalSnapshot], or null. */
    linkAt: (TerminalSnapshot, Cell) -> TerminalLink?,
    /** A click with Ctrl on a link. */
    onLinkClick: (TerminalLink) -> Unit,
    /**
     * A click of the secondary button that the host does not get, at a position in this modifier's
     * bounds.
     */
    onContextClick: (Offset) -> Unit,
    /**
     * The icon of the pointer: a hand over a link with Ctrl held, the I-beam elsewhere, and the
     * arrow while the host gets the mouse or when the pointer leaves.
     */
    onPointerIcon: (PointerIcon) -> Unit,
): Modifier =
    this.pointerInput(display, metrics, padding, layoutDirection) {
        val gridOffset =
            Offset(
                padding.calculateLeftPadding(layoutDirection).roundToPx().toFloat(),
                padding.calculateTopPadding().roundToPx().toFloat(),
            )
        val mouse =
            MouseHandler(
                display,
                metrics,
                gridOffset,
                viewConfiguration,
                onClick,
                linkAt,
                onLinkClick,
                onContextClick,
                onPointerIcon,
            )
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                mouse.handle(event, change)
                // The touch gestures and the scrolling of the ancestors do not see the mouse.
                change.consume()
            }
        }
    }

/** The lines that a notch of the wheel scrolls through the scrollback, or the arrow keys sent. */
private const val WHEEL_LINES = 3

/** How much a notch of the wheel with Ctrl zooms the display. */
private const val WHEEL_ZOOM = 1.2f

/**
 * How long the fingers of a touchpad's pan may rest before they are lifted and still fling, as long
 * as Compose's VelocityTracker waits before it takes a pointer to have stopped.
 */
private const val PAN_STOPPED_MILLIS = 40L

/** The buttons of a mouse that the terminal tells apart. */
private enum class Button(val report: MouseButton) {
    Primary(MouseButton.Left),
    Tertiary(MouseButton.Middle),
    Secondary(MouseButton.Right);

    fun isPressed(event: PointerEvent): Boolean =
        when (this) {
            Primary -> event.buttons.isPrimaryPressed
            Tertiary -> event.buttons.isTertiaryPressed
            Secondary -> event.buttons.isSecondaryPressed
        }
}

/**
 * A press of the primary button with Ctrl on [link] at [cell] of the display, which clicks it when
 * released in the same cell.
 */
private class LinkPress(val link: TerminalLink, val cell: Cell) {
    /** Whether the pointer left the cell, which makes the press click nothing. */
    var left = false
}

/**
 * A selection that the primary button makes: [origin] is what the press selected (a cell, a word,
 * or a line, or the end of the selection that a Shift+click extends), and moving extends it by its
 * unit to the pointer ([TerminalSelection.extendedTo]).
 */
private class SelectionDrag(
    val origin: TerminalSelection,
    /** The selection last made, which the display drops when its text changes; null before. */
    var selected: TerminalSelection?,
) {
    /** Whether the press made a selection or the pointer left the cell. */
    var started: Boolean = selected != null

    /** Whether the selection was dropped during the drag, which then selects nothing more. */
    var dropped = false
}

private class MouseHandler(
    private val display: TerminalDisplay,
    private val metrics: CellMetrics,
    /** Where the grid starts in the modifier's bounds. */
    private val gridOffset: Offset,
    private val viewConfiguration: ViewConfiguration,
    private val onClick: () -> Unit,
    private val linkAt: (TerminalSnapshot, Cell) -> TerminalLink?,
    private val onLinkClick: (TerminalLink) -> Unit,
    private val onContextClick: (Offset) -> Unit,
    private val onPointerIcon: (PointerIcon) -> Unit,
) {
    private val session = display.session

    /** The buttons pressed after the last event. */
    private val pressed = mutableSetOf<Button>()

    /** The buttons whose press the host got, so that it gets their release. */
    private val reported = mutableSetOf<Button>()

    /** The cell last reported, 1-based, so that motion is reported only to another cell. */
    private var reportedCell: Pair<Int, Int>? = null

    private var drag: SelectionDrag? = null

    private var linkPress: LinkPress? = null

    /** The time and cell of the last press of the primary button, and how many clicks it made. */
    private var lastPressMillis = Long.MIN_VALUE
    private var lastPressCell: Cell? = null
    private var clicks = 0

    /** The part of a notch that the wheel turned and that did not scroll yet. */
    private var wheelRemainder = 0f

    /** The two-finger swipe on a touchpad going on, or null. */
    private var pan: TouchpadPan? = null

    /**
     * A two-finger swipe on a touchpad: it moves the zoomed display and scrolls as a swipe on the
     * grid does, with [scroller], and flings at its end with the speed that [velocity] measures of
     * the fingers' [position] (docs/decisions/0100).
     */
    private inner class TouchpadPan(scrolling: SwipeScrolling) {
        /** The last event of the swipe, with the pointer's position for wheel reports. */
        var change: PointerInputChange? = null
        var modifiers: PointerKeyboardModifiers? = null

        val scroller =
            SwipeScroller(display, metrics, scrolling) { lines ->
                scroll(
                    checkNotNull(change),
                    checkNotNull(modifiers),
                    scrolling,
                    reports = lines,
                    lines = lines,
                )
            }
        val velocity = VelocityTracker()

        /** Where the fingers are, from where they started, in pixels of the display. */
        var position = Offset.Zero

        /** When the fingers last moved. */
        var movedMillis = Long.MIN_VALUE
    }

    fun handle(event: PointerEvent, change: PointerInputChange) {
        val modifiers = event.keyboardModifiers
        when (event.type) {
            PointerEventType.Scroll -> wheel(change, modifiers)
            PointerEventType.PanStart -> {
                display.stopFling()
                pan = startPan(modifiers)
                pan?.let { pan(it, change, modifiers) }
            }
            PointerEventType.PanMove -> pan?.let { pan(it, change, modifiers) }
            PointerEventType.PanEnd -> {
                pan?.let {
                    pan(it, change, modifiers)
                    fling(it, change)
                }
                pan = null
            }
            // A pinch on a touchpad zooms as a pinch of two fingers on the grid does
            // (docs/decisions/0100). It is never reported.
            PointerEventType.ScaleStart -> display.stopFling()
            PointerEventType.ScaleChange ->
                display.zoomBy(
                    change.scaleFactor,
                    change.position - gridOffset,
                    change.position - gridOffset,
                )
            PointerEventType.Exit -> {
                reportedCell = null
                onPointerIcon(PointerIcon.Default)
            }
            else -> {
                // A press stops a fling, as a touch does, and then acts as usual.
                if (event.type == PointerEventType.Press) display.stopFling()
                for (button in Button.entries) {
                    if (button in pressed && !button.isPressed(event)) {
                        pressed -= button
                        release(button, change, modifiers)
                    }
                }
                for (button in Button.entries) {
                    if (button !in pressed && button.isPressed(event)) {
                        pressed += button
                        press(button, change, modifiers)
                    }
                }
                if (event.type == PointerEventType.Move) move(change, modifiers)
            }
        }
    }

    /** The point of the grid under [position], through the zoom of the display. */
    private fun grid(position: Offset): Offset = display.toGrid(position - gridOffset)

    /**
     * Whether the host gets the mouse: it asked for reports, Shift is not held, and the display
     * does not show the scrollback, whose rows a report, which carries a row of the screen, cannot
     * tell. A button's press decides it for its release and its drag.
     */
    private fun isReporting(modifiers: PointerKeyboardModifiers): Boolean =
        !modifiers.isShiftPressed && isReportingTouches(display)

    private fun report(
        button: MouseButton,
        action: MouseAction,
        cell: Pair<Int, Int>,
        modifiers: PointerKeyboardModifiers,
    ) {
        session.sendMouse(
            MouseReport(
                button,
                action,
                cell.first,
                cell.second,
                alt = modifiers.isAltPressed,
                ctrl = modifiers.isCtrlPressed,
            )
        )
        reportedCell = cell
    }

    private fun press(
        button: Button,
        change: PointerInputChange,
        modifiers: PointerKeyboardModifiers,
    ) {
        if (isReporting(modifiers)) {
            // A click that the host gets drops the selection, as a tap that it gets does.
            if (button == Button.Primary) display.selection = null
            reported += button
            report(
                button.report,
                MouseAction.Press,
                cellAt(grid(change.position), metrics, session),
                modifiers,
            )
            return
        }
        when (button) {
            Button.Primary -> {
                val linkPress = pressLink(change, modifiers)
                if (linkPress != null) {
                    // A click on a link drops the selection, as a click elsewhere does.
                    display.selection = null
                    this.linkPress = linkPress
                } else {
                    select(change, modifiers)
                }
            }
            Button.Secondary -> onContextClick(change.position)
            // Android has no primary selection to paste, as X has.
            Button.Tertiary -> {}
        }
    }

    /**
     * The press of the link under [change] if Ctrl is held, which makes the press a click on the
     * link rather than a selection: links open on Ctrl+click in every app, as in desktop terminals.
     */
    private fun pressLink(
        change: PointerInputChange,
        modifiers: PointerKeyboardModifiers,
    ): LinkPress? {
        if (!modifiers.isCtrlPressed) return null
        val snapshot = display.displaySnapshot()
        val cell = displayCell(grid(change.position), metrics, snapshot)
        val link = linkAt(snapshot, cell) ?: return null
        return LinkPress(link, cell)
    }

    private fun select(change: PointerInputChange, modifiers: PointerKeyboardModifiers) {
        val snapshot = display.displaySnapshot()
        val cell = displayCell(grid(change.position), metrics, snapshot)
        val time = change.uptimeMillis
        clicks =
            if (
                cell == lastPressCell &&
                    time - lastPressMillis <= viewConfiguration.doubleTapTimeoutMillis
            )
                clicks % 3 + 1
            else 1
        lastPressMillis = time
        lastPressCell = cell
        val position = snapshot.position(cell)
        val generation = snapshot.generation
        // Not one made before the lines were numbered again, which the display is about to drop.
        val current = display.selection?.takeIf { it.generation == generation }
        val (origin, selected) =
            if (clicks == 1 && modifiers.isShiftPressed && current != null) {
                // Extends the selection by its unit from its end farther from the cell, which may
                // have scrolled off the display.
                val anchor = if (position < current.start) current.end else current.start
                val origin = TerminalSelection(anchor, anchor, generation, unit = current.unit)
                origin to origin.extendedTo(snapshot, cell)
            } else {
                val unit =
                    when (clicks) {
                        1 -> SelectionUnit.Cell
                        2 -> SelectionUnit.Word
                        else -> SelectionUnit.Line
                    }
                val origin = TerminalSelection.at(snapshot, cell, unit)
                origin to if (clicks == 1) null else origin
            }
        display.selection = selected
        drag = SelectionDrag(origin, selected)
    }

    private fun move(change: PointerInputChange, modifiers: PointerKeyboardModifiers) {
        val held = reported.firstOrNull()
        if (held != null) {
            val cell = cellAt(grid(change.position), metrics, session)
            if (cell != reportedCell) report(held.report, MouseAction.Motion, cell, modifiers)
            return
        }
        val linkPress = linkPress
        if (linkPress != null) {
            val snapshot = display.displaySnapshot()
            if (displayCell(grid(change.position), metrics, snapshot) != linkPress.cell) {
                linkPress.left = true
            }
            return
        }
        val drag = drag
        if (drag != null) {
            extend(drag, grid(change.position))
            return
        }
        if (pressed.isNotEmpty()) return
        if (isReporting(modifiers)) {
            // The host asked for all motion (mode 1003); the session leaves it out otherwise.
            onPointerIcon(PointerIcon.Default)
            val cell = cellAt(grid(change.position), metrics, session)
            if (cell != reportedCell) report(MouseButton.None, MouseAction.Motion, cell, modifiers)
        } else {
            hover(change.position, modifiers)
        }
    }

    /**
     * Tells [onPointerIcon] the icon for the cell of the display under [position], which the host
     * does not get: a hand over a link that a click with [modifiers] would open.
     */
    private fun hover(position: Offset, modifiers: PointerKeyboardModifiers) {
        reportedCell = null
        val snapshot = display.displaySnapshot()
        val cell = displayCell(grid(position), metrics, snapshot)
        val clickable = modifiers.isCtrlPressed && linkAt(snapshot, cell) != null
        onPointerIcon(if (clickable) PointerIcon.Hand else PointerIcon.Text)
    }

    private fun extend(drag: SelectionDrag, position: Offset) {
        // Output may have scrolled the text since the press: the pointer is over what the display
        // shows now.
        val snapshot = display.displaySnapshot()
        val cell = displayCell(position, metrics, snapshot)
        val origin = drag.origin
        if (!drag.started && snapshot.position(cell) == origin.start) return
        // The display drops the selection when its text changes: the rest of the drag selects
        // nothing, as with a long press. A drag that has not moved yet has no selection to be
        // dropped, but its origin is numbered as the lines were at the press.
        if (
            drag.dropped ||
                snapshot.generation != origin.generation ||
                (drag.started && display.selection !== drag.selected)
        ) {
            drag.dropped = true
            drag.started = true
            drag.selected = null
            return
        }
        val selected = origin.extendedTo(snapshot, cell)
        drag.started = true
        drag.selected = selected
        display.selection = selected
    }

    private fun release(
        button: Button,
        change: PointerInputChange,
        modifiers: PointerKeyboardModifiers,
    ) {
        if (reported.remove(button)) {
            report(
                button.report,
                MouseAction.Release,
                cellAt(grid(change.position), metrics, session),
                modifiers,
            )
            if (button == Button.Primary) onClick()
            return
        }
        if (button != Button.Primary) return
        val linkPress = linkPress
        if (linkPress != null) {
            this.linkPress = null
            if (!linkPress.left) onLinkClick(linkPress.link)
            return
        }
        val drag = drag ?: return
        this.drag = null
        if (drag.started) return
        // A click: a tap, without the keyboard's long press.
        onClick()
    }

    private fun wheel(change: PointerInputChange, modifiers: PointerKeyboardModifiers) {
        display.stopFling()
        val reporting = isReporting(modifiers)
        if (modifiers.isCtrlPressed && !reporting) {
            // Ctrl+wheel zooms about the pointer, as in browsers, and touchpads that give a pinch
            // as the wheel with Ctrl do (docs/decisions/0100). Up, away from the user, zooms in.
            val at = change.position - gridOffset
            display.zoomBy(WHEEL_ZOOM.pow(-change.scrollDelta.y), at, at)
            return
        }
        // Positive when turned up, toward earlier content, as a swipe down.
        wheelRemainder -= change.scrollDelta.y
        val notches = wheelRemainder.toInt()
        if (notches == 0) return
        wheelRemainder -= notches
        // Saturated, so that a delta of more notches than lines can count does not overflow and
        // scroll the other way, as scrollByPages does.
        val lines =
            (notches.toLong() * WHEEL_LINES)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        scroll(
            change,
            modifiers,
            SwipeScrolling.of(session, reporting),
            reports = notches,
            lines = lines,
        )
    }

    /**
     * Starts a two-finger swipe on a touchpad, which Compose gives as a pan of the mouse. Whether
     * the host gets it as the wheel is decided when it starts, as for a swipe on the grid, so that
     * a swipe that scrolls down to the bottom of the scrollback stops there and does not go on to
     * scroll the host.
     */
    private fun startPan(modifiers: PointerKeyboardModifiers): TouchpadPan =
        TouchpadPan(SwipeScrolling.of(session, isReporting(modifiers)))

    /** Moves [pan] by the moves of [change], as a swipe on the grid moves. */
    private fun pan(
        pan: TouchpadPan,
        change: PointerInputChange,
        modifiers: PointerKeyboardModifiers,
    ) {
        pan.change = change
        pan.modifiers = modifiers
        // Moves that came between two events are in the historical changes.
        for (historical in change.historical) {
            move(pan, historical.uptimeMillis, historical.panOffset)
        }
        move(pan, change.uptimeMillis, change.panOffset)
    }

    private fun move(pan: TouchpadPan, uptimeMillis: Long, panOffset: Offset) {
        // The pan offset is an amount of scrolling, as the wheel's delta, so it goes the other way
        // than the fingers. Not zoomed, only vertical moves count, as for a finger.
        val moved = -panOffset
        val delta = if (display.zoom == 1f) Offset(0f, moved.y) else moved
        // The start and the moves: an event without a move, as the end, would count as the
        // fingers stopping there. Compared by components, which a negated zero passes.
        if (delta.x == 0f && delta.y == 0f && pan.movedMillis != Long.MIN_VALUE) return
        pan.position += delta
        pan.movedMillis = uptimeMillis
        pan.velocity.addPosition(uptimeMillis, pan.position)
        pan.scroller.drag(delta)
    }

    /**
     * Flings [pan], which ended with [change], with the speed of the fingers at their last move,
     * unless they rested before they were lifted, as Compose's VelocityTracker takes a pointer to
     * have stopped.
     */
    private fun fling(pan: TouchpadPan, change: PointerInputChange) {
        if (change.uptimeMillis - pan.movedMillis > PAN_STOPPED_MILLIS) return
        val maximum = viewConfiguration.maximumFlingVelocity
        val speed = pan.velocity.calculateVelocity(Velocity(maximum, maximum))
        display.startFling(Offset(speed.x, speed.y), pan.scroller)
    }

    /**
     * Scrolls toward earlier content if positive, as [scrolling]: [reports] wheel reports at the
     * pointer's cell, [lines] arrow keys, or [lines] lines through the scrollback.
     */
    private fun scroll(
        change: PointerInputChange,
        modifiers: PointerKeyboardModifiers,
        scrolling: SwipeScrolling,
        reports: Int,
        lines: Int,
    ) {
        when (scrolling) {
            SwipeScrolling.Wheel -> {
                val button = if (reports > 0) MouseButton.WheelUp else MouseButton.WheelDown
                val cell = cellAt(grid(change.position), metrics, session)
                repeat(session.scrollEventsFor(reports)) {
                    report(button, MouseAction.Press, cell, modifiers)
                }
            }
            SwipeScrolling.ArrowKeys ->
                repeat(session.scrollEventsFor(lines)) {
                    display.sendScrollKey(if (lines > 0) TerminalKey.Up else TerminalKey.Down)
                }
            SwipeScrolling.Scrollback -> {
                display.scrollBy(lines)
                // The pointer did not move, but what is under it did, and the host may get the
                // mouse again at the bottom: the pointer icon follows.
                if (pressed.isEmpty()) {
                    if (isReporting(modifiers)) {
                        onPointerIcon(PointerIcon.Default)
                    } else {
                        hover(change.position, modifiers)
                    }
                }
            }
        }
    }
}
