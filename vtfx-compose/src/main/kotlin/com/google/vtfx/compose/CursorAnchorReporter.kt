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

import android.graphics.Matrix
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.input.ImeEditor
import com.google.vtfx.session.TerminalSession

/**
 * The position of the terminal's grid in the view: the offset of the grid's top left corner, in
 * view coordinates, and the cell size.
 */
internal class GridGeometry(
    val left: Float,
    val top: Float,
    val metrics: CellMetrics,
    val columns: Int,
    val rows: Int,
)

/**
 * Sends `CursorAnchorInfo` to the IME, so that it can place its candidate window at the composing
 * text (docs/design/ime-contract.md section 7).
 */
internal class CursorAnchorReporter(
    private val view: View,
    private val session: TerminalSession,
    /** Returns the size of the cells of [view], or null if not laid out. */
    private val metrics: () -> CellMetrics?,
) {
    private val inputMethodManager = view.context.getSystemService(InputMethodManager::class.java)
    private var monitoring = false

    fun request(mode: Int) {
        monitoring = mode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        if (mode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) send()
    }

    /** Called when the composing text or the terminal cursor changed. */
    fun onEditorChanged() {
        if (monitoring) send()
    }

    private fun send() {
        val info = build() ?: return
        inputMethodManager.updateCursorAnchorInfo(view, info)
    }

    private fun build(): CursorAnchorInfo? {
        val cellMetrics = metrics() ?: return null
        val grid =
            GridGeometry(
                0f,
                0f,
                cellMetrics,
                (view.width / cellMetrics.width).toInt(),
                (view.height / cellMetrics.height).toInt(),
            )
        val cursor = session.cursorPosition()
        // Character bounds are in view coordinates; the matrix maps them to the screen.
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return session.edit { editor ->
            cursorAnchorInfo(
                editor,
                session.ambiguousWidth,
                grid,
                cursor,
                location[0].toFloat(),
                location[1].toFloat(),
            )
        }
    }
}

/**
 * Returns where the composing text and the insertion point of [editor] are drawn: on [grid], from
 * the terminal [cursor] (column, row), wrapped as the display wraps it. The bounds are in view
 * coordinates, and the matrix translates them by the view's position on the screen, ([screenLeft],
 * [screenTop]).
 */
internal fun cursorAnchorInfo(
    editor: ImeEditor,
    ambiguousWidth: AmbiguousWidth,
    grid: GridGeometry,
    cursor: Pair<Int, Int>,
    screenLeft: Float,
    screenTop: Float,
): CursorAnchorInfo {
    val metrics = grid.metrics
    val composing = editor.composingText
    val layout =
        ComposingLayout(
            composing,
            cursor.first,
            cursor.second,
            grid.columns,
            grid.rows,
            ambiguousWidth,
        )
    val builder = CursorAnchorInfo.Builder().setSelectionRange(editor.cursor, editor.cursor)
    if (composing.isNotEmpty()) {
        val start = editor.composingStart
        builder.setComposingText(start, composing)
        for (cell in layout.cells) {
            val left = grid.left + cell.column * metrics.width
            val top = grid.top + cell.row * metrics.height
            val right = left + cell.width * metrics.width
            for (index in cell.start until cell.end) {
                builder.addCharacterBounds(
                    start + index,
                    left,
                    top,
                    right,
                    top + metrics.height,
                    CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
                )
            }
        }
    }
    val x = grid.left + layout.endColumn * metrics.width
    val top = grid.top + layout.endRow * metrics.height
    builder.setInsertionMarkerLocation(
        x,
        top,
        top + metrics.baseline,
        top + metrics.height,
        CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
    )
    builder.setMatrix(Matrix().apply { setTranslate(screenLeft, screenTop) })
    return builder.build()
}
