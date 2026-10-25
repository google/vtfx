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
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseButton
import com.google.vtfx.gate.MouseReport
import kotlin.math.abs

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
 *   word and a triple click a line, and the drag that follows extends them; a click with Shift
 *   extends the selection. A click without a drag is a tap: [onLinkClick], else [onClick].
 * - The secondary button calls [onContextClick].
 * - The wheel scrolls as a swipe does, in steps of [WHEEL_LINES] lines a notch.
 */
internal fun Modifier.terminalMouse(
    display: TerminalDisplay,
    metrics: CellMetrics,
    /** The padding around the grid within this modifier's bounds, where the mouse also acts. */
    padding: PaddingValues,
    layoutDirection: LayoutDirection,
    /** A click that no link took, or a click that the host got with the primary button. */
    onClick: () -> Unit,
    /**
     * Called for a click on a cell of the display [TerminalSnapshot]. Returns whether a link took
     * the click; if not, [onClick] is called.
     */
    onLinkClick: (TerminalSnapshot, Cell, PointerType, PointerKeyboardModifiers) -> Boolean,
    /**
     * A click of the secondary button that the host does not get, at a position in this modifier's
     * bounds.
     */
    onContextClick: (Offset) -> Unit,
    /**
     * The cell of the display under a pointer that hovers and that the host does not get, or null
     * when it leaves.
     */
    onHover: (TerminalSnapshot?, Cell?) -> Unit,
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
                onLinkClick,
                onContextClick,
                onHover,
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
 * A selection that the primary button makes: [unit] is what the press selected (a cell, a word, or
 * a line), and moving extends it from the far end of [unit] to the cell under the pointer.
 */
private class SelectionDrag(
    val snapshot: TerminalSnapshot,
    val unit: TerminalSelection,
    /** The selection last made, which the display drops when it changes; null before a move. */
    var selected: TerminalSelection?,
    val position: Offset,
    /** [TerminalDisplay.changeCount] at the press, when [snapshot] was taken. */
    val changeCount: Int,
) {
    /** Whether the press made a selection or the pointer left the cell. */
    var started: Boolean = selected != null

    /** Whether the display changed during the drag, which then selects nothing more. */
    var dropped = false
}

private class MouseHandler(
    private val display: TerminalDisplay,
    private val metrics: CellMetrics,
    /** Where the grid starts in the modifier's bounds. */
    private val gridOffset: Offset,
    private val viewConfiguration: ViewConfiguration,
    private val onClick: () -> Unit,
    private val onLinkClick:
        (TerminalSnapshot, Cell, PointerType, PointerKeyboardModifiers) -> Boolean,
    private val onContextClick: (Offset) -> Unit,
    private val onHover: (TerminalSnapshot?, Cell?) -> Unit,
) {
    private val session = display.session

    /** The buttons pressed after the last event. */
    private val pressed = mutableSetOf<Button>()

    /** The buttons whose press the host got, so that it gets their release. */
    private val reported = mutableSetOf<Button>()

    /** The cell last reported, 1-based, so that motion is reported only to another cell. */
    private var reportedCell: Pair<Int, Int>? = null

    private var drag: SelectionDrag? = null

    /** The time and cell of the last press of the primary button, and how many clicks it made. */
    private var lastPressMillis = Long.MIN_VALUE
    private var lastPressCell: Cell? = null
    private var clicks = 0

    /** The part of a notch that the wheel turned and that did not scroll yet. */
    private var wheelRemainder = 0f

    fun handle(event: PointerEvent, change: PointerInputChange) {
        val modifiers = event.keyboardModifiers
        when (event.type) {
            PointerEventType.Scroll -> wheel(change, modifiers)
            PointerEventType.Exit -> {
                reportedCell = null
                onHover(null, null)
            }
            else -> {
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

    /** Returns [position] in the modifier's bounds as a position on the grid. */
    private fun grid(position: Offset): Offset = position - gridOffset

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
            Button.Primary -> select(change, modifiers)
            Button.Secondary -> onContextClick(change.position)
            // Android has no primary selection to paste, as X has.
            Button.Tertiary -> {}
        }
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
        val current = display.selection
        val (unit, selected) =
            if (clicks == 1 && modifiers.isShiftPressed && current != null) {
                // Extends the selection from its end farther from the cell.
                val anchor = if (cell < current.start) current.end else current.start
                TerminalSelection(anchor, anchor) to TerminalSelection(anchor, cell)
            } else {
                val unit =
                    when (clicks) {
                        1 -> TerminalSelection(cell, cell)
                        2 -> TerminalSelection.word(snapshot, cell)
                        else -> TerminalSelection.line(snapshot, cell)
                    }
                unit to if (clicks == 1) null else unit
            }
        display.selection = selected
        drag = SelectionDrag(snapshot, unit, selected, grid(change.position), display.changeCount)
    }

    private fun move(change: PointerInputChange, modifiers: PointerKeyboardModifiers) {
        val held = reported.firstOrNull()
        if (held != null) {
            val cell = cellAt(grid(change.position), metrics, session)
            if (cell != reportedCell) report(held.report, MouseAction.Motion, cell, modifiers)
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
            onHover(null, null)
            val cell = cellAt(grid(change.position), metrics, session)
            if (cell != reportedCell) report(MouseButton.None, MouseAction.Motion, cell, modifiers)
        } else {
            hover(change.position)
        }
    }

    /** Tells [onHover] the cell of the display under [position], which the host does not get. */
    private fun hover(position: Offset) {
        reportedCell = null
        val snapshot = display.displaySnapshot()
        onHover(snapshot, displayCell(grid(position), metrics, snapshot))
    }

    private fun extend(drag: SelectionDrag, position: Offset) {
        val snapshot = drag.snapshot
        val cell = displayCell(position, metrics, snapshot)
        val unit = drag.unit
        if (!drag.started && cell == unit.start) return
        // The selection is dropped when the display changes, and the snapshot is then out of
        // date: the rest of the drag selects nothing, as with a long press. A drag that has not
        // moved yet has no selection to be dropped, so the display's changes are counted too.
        if (
            drag.dropped ||
                display.changeCount != drag.changeCount ||
                (drag.started && display.selection !== drag.selected)
        ) {
            drag.dropped = true
            drag.started = true
            drag.selected = null
            return
        }
        val selected =
            when {
                cell in unit.start..unit.end -> unit
                cell < unit.start -> TerminalSelection(unit.end, cell)
                else -> TerminalSelection(unit.start, cell)
            }
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
        val drag = drag ?: return
        this.drag = null
        if (drag.started) return
        // A click: a tap, without the keyboard's long press.
        val snapshot = display.displaySnapshot()
        val cell = displayCell(drag.position, metrics, snapshot)
        if (!onLinkClick(snapshot, cell, change.type, modifiers)) onClick()
    }

    private fun wheel(change: PointerInputChange, modifiers: PointerKeyboardModifiers) {
        // Positive when turned up, toward earlier content, as a swipe down.
        wheelRemainder -= change.scrollDelta.y
        val notches = wheelRemainder.toInt()
        if (notches == 0) return
        wheelRemainder -= notches
        val count = abs(notches)
        when {
            isReporting(modifiers) -> {
                val button = if (notches > 0) MouseButton.WheelUp else MouseButton.WheelDown
                val cell = cellAt(grid(change.position), metrics, session)
                repeat(count) { report(button, MouseAction.Press, cell, modifiers) }
            }
            session.isAlternateScreen() ->
                repeat(count * WHEEL_LINES) {
                    session.sendScrollKey(if (notches > 0) TerminalKey.Up else TerminalKey.Down)
                }
            else -> {
                display.scrollBy(notches * WHEEL_LINES)
                // The pointer did not move, but what is under it did, and the host may get the
                // mouse again at the bottom: the pointer icon follows.
                if (pressed.isEmpty()) {
                    if (isReporting(modifiers)) onHover(null, null) else hover(change.position)
                }
            }
        }
    }
}
