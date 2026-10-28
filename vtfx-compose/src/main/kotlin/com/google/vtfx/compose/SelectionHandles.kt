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
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalSnapshot

/** The test tags of the handles. */
internal const val START_HANDLE_TAG = "vtfx.selectionStartHandle"
internal const val END_HANDLE_TAG = "vtfx.selectionEndHandle"

/**
 * The positions of the tips of the handles, in pixels of the grid: the bottom left corner of the
 * first selected cell and the bottom right corner of the last. Null for an end that the display
 * does not show.
 */
internal data class HandleTips(val start: Offset?, val end: Offset?)

/**
 * Returns where [selection]'s handles point on [snapshot]'s display, or null if its lines are
 * numbered otherwise, which the display drops at its next check.
 */
internal fun handleTips(
    selection: TerminalSelection,
    snapshot: TerminalSnapshot,
    metrics: CellMetrics,
): HandleTips? {
    if (selection.generation != snapshot.generation) return null
    fun tip(position: TextPosition, column: Int): Offset? {
        val row = position.line - snapshot.firstLine
        if (row < 0 || row >= snapshot.rows) return null
        return Offset(column * metrics.width, (row + 1) * metrics.height)
    }
    // Within the grid, for a selection made on a wider display.
    return HandleTips(
        tip(selection.start, minOf(selection.start.column, snapshot.columns)),
        tip(selection.end, minOf(selection.end.column + 1, snapshot.columns)),
    )
}

/**
 * Shows the handles of [display]'s selection while [TerminalDisplay.showsHandles], each in a popup,
 * so that it may hang beyond the terminal, as Compose's text fields show theirs. Dragging a handle
 * moves its end of the selection by cells ([TerminalSelection.withEndAt], docs/decisions/0097).
 *
 * [modifier] must give this the bounds of the grid. A handle is shown only while its tip is on the
 * display and within the part of the grid that the ancestors leave visible. A handle that would
 * leave the window at its side, the start handle at the first columns of a terminal at the window's
 * left edge, hangs on the other side of its tip instead, as `TextView` turns its handles.
 */
@Composable
internal fun SelectionHandles(
    display: TerminalDisplay,
    metrics: CellMetrics,
    /** Called when a drag of a handle starts. */
    onDragStart: () -> Unit,
    /** Called when a drag ends with the handles shown, with the tip of the dragged handle. */
    onDragEnd: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = remember { CoordinatesHolder() }
    // State, so that scrolling an ancestor shows or hides the handles.
    var visibleBounds by remember { mutableStateOf<Rect?>(null) }
    // The left and right edges of the window, in the grid's coordinates.
    var windowEdges by remember { mutableStateOf(0f..0f) }
    val handleWidth = with(LocalDensity.current) { HANDLE_SIZE.toPx() }
    val tips by
        remember(display, metrics) {
            derivedStateOf {
                val selection = display.selection
                if (!display.showsHandles || selection == null) return@derivedStateOf null
                // Read only while the handles are shown, as the snapshot copies the screen.
                display.observeVersion().let {
                    handleTips(selection, display.displaySnapshot(), metrics)
                }
            }
        }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            grid.value = coordinates
            val window = coordinates.boundsInWindow()
            visibleBounds =
                Rect(
                    coordinates.windowToLocal(window.topLeft),
                    coordinates.windowToLocal(window.bottomRight),
                )
            val root = coordinates.findRootCoordinates()
            windowEdges =
                coordinates.localPositionOf(root, Offset.Zero).x..coordinates
                        .localPositionOf(root, Offset(root.size.width.toFloat(), 0f))
                        .x
        }
    ) {
        val bounds = visibleBounds ?: return@Box
        for (end in SelectionEnd.entries) {
            val tip =
                when (end) {
                    SelectionEnd.Start -> tips?.start
                    SelectionEnd.End -> tips?.end
                }
            // On the edge is visible: the end handle of the last row points at the grid's bottom.
            if (
                tip == null ||
                    tip.x !in bounds.left..bounds.right ||
                    tip.y !in bounds.top..bounds.bottom
            ) {
                continue
            }
            val pointsRight =
                when (end) {
                    SelectionEnd.Start -> tip.x - handleWidth >= windowEdges.start
                    SelectionEnd.End -> tip.x + handleWidth > windowEdges.endInclusive
                }
            SelectionHandle(display, metrics, end, tip, pointsRight, grid, onDragStart, onDragEnd)
        }
    }
}

/**
 * The handle of [end], with its tip at [tip] of the grid that [grid] holds: left of the tip,
 * pointing at the top right, if [pointsRight], else right of the tip, pointing at the top left.
 */
@Composable
private fun SelectionHandle(
    display: TerminalDisplay,
    metrics: CellMetrics,
    end: SelectionEnd,
    tip: Offset,
    pointsRight: Boolean,
    grid: CoordinatesHolder,
    onDragStart: () -> Unit,
    onDragEnd: (Offset) -> Unit,
) {
    // The color of the handles of the app's text fields, which a Material theme sets.
    val color = LocalTextSelectionColors.current.handleColor
    val haptics = LocalHapticFeedback.current
    val handle = remember { CoordinatesHolder() }
    val currentTip by rememberUpdatedState(tip)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Popup(
        popupPositionProvider = HandlePosition(pointsRight, tip.round()),
        // Beyond the window's edges too, as the handle hangs below the last row.
        properties = PopupProperties(clippingEnabled = false),
    ) {
        Spacer(
            Modifier.size(HANDLE_SIZE)
                .testTag(
                    when (end) {
                        SelectionEnd.Start -> START_HANDLE_TAG
                        SelectionEnd.End -> END_HANDLE_TAG
                    }
                )
                .onGloballyPositioned { handle.value = it }
                .drawBehind { drawHandle(pointsRight, color) }
                .pointerInput(display, metrics, end) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val gridCoordinates = grid.value ?: return@awaitEachGesture
                        val handleCoordinates = handle.value ?: return@awaitEachGesture
                        down.consume()
                        // Through the screen: the popup moves with the handle during the drag,
                        // and the grid does not.
                        fun inGrid(position: Offset): Offset =
                            gridCoordinates.screenToLocal(handleCoordinates.localToScreen(position))
                        // The tip keeps its distance from the finger, which holds the handle
                        // below the text.
                        val grab = currentTip - inGrid(down.position)
                        currentOnDragStart()
                        drag(down.id) { change ->
                            change.consume()
                            val selection = display.selection ?: return@drag
                            val snapshot = display.displaySnapshot()
                            if (snapshot.generation != selection.generation) return@drag
                            // The cell whose edge is nearest to the tip, on the row above it.
                            val towardsCell =
                                when (end) {
                                    SelectionEnd.Start -> metrics.width / 2
                                    SelectionEnd.End -> -metrics.width / 2
                                }
                            val point =
                                inGrid(change.position) +
                                    grab +
                                    Offset(towardsCell, -metrics.height / 2)
                            val moved =
                                selection.withEndAt(
                                    end,
                                    snapshot,
                                    displayCell(point, metrics, snapshot),
                                )
                            if (moved != selection) {
                                display.selection = moved
                                display.showHandles()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        val selection = display.selection
                        if (!display.showsHandles || selection == null) return@awaitEachGesture
                        val tips = handleTips(selection, display.displaySnapshot(), metrics)
                        val endTip =
                            when (end) {
                                SelectionEnd.Start -> tips?.start
                                SelectionEnd.End -> tips?.end
                            }
                        currentOnDragEnd(endTip ?: currentTip)
                    }
                }
        )
    }
}

/**
 * Draws a teardrop that points at its top right corner if [pointsRight], else at its top left: a
 * circle with the square corner of that top quadrant, as Compose's text fields draw their handles.
 */
private fun DrawScope.drawHandle(pointsRight: Boolean, color: Color) {
    val radius = size.width / 2
    drawCircle(color, radius, Offset(radius, radius))
    drawRect(color, if (pointsRight) Offset(radius, 0f) else Offset.Zero, Size(radius, radius))
}

/**
 * Places a handle with its tip at [tip] of the popup's anchor, the grid: left of the tip if
 * [pointsRight], else right of it.
 */
private data class HandlePosition(val pointsRight: Boolean, val tip: IntOffset) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = if (pointsRight) tip.x - popupContentSize.width else tip.x
        return IntOffset(anchorBounds.left + left, anchorBounds.top + tip.y)
    }
}

/** The coordinates of a layout, for the gesture detector, which runs outside the composition. */
private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/** The size of a handle, as Compose's text fields draw theirs. */
internal val HANDLE_SIZE = 25.dp
