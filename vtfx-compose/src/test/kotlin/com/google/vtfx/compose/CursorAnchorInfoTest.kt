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
import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import androidx.compose.ui.geometry.Offset
import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.input.HostInput
import com.google.vtfx.input.ImeEditor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The positions sent to the IME for its candidate window (requirement 5.3,
 * docs/design/ime-contract.md section 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class CursorAnchorInfoTest {
    private val editor =
        ImeEditor(
            object : ImeEditor.Listener {
                override fun send(input: HostInput) {}

                override fun restartInput() {}
            }
        )
    private val metrics = CellMetrics(width = 10f, height = 20f, baseline = 15f)

    private fun info(columns: Int, cursor: Pair<Int, Int>, zoom: GridZoom = GridZoom.None) =
        cursorAnchorInfo(
            editor,
            AmbiguousWidth.Narrow,
            GridGeometry(5f, 7f, metrics, columns, 5),
            cursor,
            100f,
            200f,
            zoom,
        )

    @Test
    fun composingTextIsAtTheTerminalCursor() {
        editor.commitText("ab")
        editor.setComposingText("日本")
        val info = info(columns = 10, cursor = Pair(3, 1))
        assertEquals(2, info.composingTextStart)
        assertEquals("日本", info.composingText.toString())
        assertEquals(4, info.selectionStart)
        // Each wide character takes two cells, from column 3 of row 1.
        assertEquals(RectF(35f, 27f, 55f, 47f), info.getCharacterBounds(2))
        assertEquals(RectF(55f, 27f, 75f, 47f), info.getCharacterBounds(3))
        assertEquals(75f, info.insertionMarkerHorizontal)
        assertEquals(27f, info.insertionMarkerTop)
        assertEquals(42f, info.insertionMarkerBaseline)
        assertEquals(47f, info.insertionMarkerBottom)
        assertEquals(Matrix().apply { setTranslate(100f, 200f) }, info.matrix)
    }

    @Test
    fun composingTextWrapsAtTheRightMargin() {
        editor.setComposingText("日本")
        val info = info(columns = 6, cursor = Pair(4, 1))
        assertEquals(RectF(45f, 27f, 65f, 47f), info.getCharacterBounds(0))
        assertEquals(RectF(5f, 47f, 25f, 67f), info.getCharacterBounds(1))
        assertEquals(25f, info.insertionMarkerHorizontal)
        assertEquals(47f, info.insertionMarkerTop)
    }

    @Test
    fun withoutComposingTextOnlyTheInsertionPointIsSent() {
        val info = info(columns = 10, cursor = Pair(2, 0))
        assertEquals(-1, info.composingTextStart)
        assertEquals(null, info.getCharacterBounds(0))
        assertEquals(25f, info.insertionMarkerHorizontal)
        assertEquals(7f, info.insertionMarkerTop)
    }

    @Test
    fun theMatrixMapsTheBoundsThroughTheZoom() {
        editor.setComposingText("日本")
        // Twice as large, from the point (20, 10) of the grid, which shows 50 by 50 pixels of it.
        val zoom = GridZoom(2f, Offset(20f, 10f))
        val info = info(columns = 10, cursor = Pair(3, 1), zoom = zoom)
        val bounds = RectF(info.getCharacterBounds(0))
        info.matrix.mapRect(bounds)
        // (30, 20) of the grid is (20, 20) on the display, from the grid's corner at (105, 207)
        // on the screen.
        assertEquals(RectF(125f, 227f, 165f, 267f), bounds)
        assertEquals(CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION, info.getCharacterBoundsFlags(0))
        assertEquals(CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION, info.getCharacterBoundsFlags(1))
        // Right of and below what the display shows.
        val far = info(columns = 10, cursor = Pair(8, 3), zoom = zoom)
        assertEquals(CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION, far.getCharacterBoundsFlags(0))
        assertEquals(CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION, far.insertionMarkerFlags)
    }
}
