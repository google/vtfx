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

package com.google.vtfx.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks that the separators of Powerline span their cells (section 7.3 of emulation.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class PowerlineTest {
    @get:Rule val composeRule = createComposeRule()

    /** The size of a cell in pixels, and a capture of the terminal. */
    private class Capture(val cellWidth: Int, val cellHeight: Int, val bitmap: Bitmap) {
        /** The color of the pixel at [x], [y] in the cell at the start of the third row. */
        fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x, 2 * cellHeight + y)
    }

    /**
     * Captures the terminal after the host writes [output] at the start of the third row. The first
     * row is one cell in reverse video, which gives the size of a cell. With [focused], the block
     * cursor is on the cell; otherwise the cursor is hidden.
     */
    private fun capture(output: String, focused: Boolean = false): Capture {
        val session = TerminalSession({})
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        if (focused) composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        val cursor = if (focused) "\u001b[3;1H" else "\u001b[?25l"
        session.feed("\u001b[7m \u001b[m\r\n\r\n$output$cursor".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val cellWidth = (0 until bitmap.width).first { bitmap.getPixel(it, 0) != FOREGROUND }
        val cellHeight = (0 until bitmap.height).first { bitmap.getPixel(0, it) != FOREGROUND }
        return Capture(cellWidth, cellHeight, bitmap)
    }

    /**
     * The pixels on the left edge of the cell over the middle half of its height, where the
     * triangle of U+E0B0 is at least half a cell wide.
     */
    private fun Capture.middleOfTheLeftEdge(): List<Int> =
        (cellHeight / 4 until cellHeight * 3 / 4).map { pixel(0, it) }

    @Test
    fun aSolidSeparatorFillsTheLeftEdgeOfItsCell() {
        val capture = capture("\uE0B0")

        assertEquals(List(capture.cellHeight / 2) { FOREGROUND }, capture.middleOfTheLeftEdge())
    }

    @Test
    fun aSolidSeparatorReachesTheMiddleOfTheRightEdge() {
        val capture = capture("\uE0B0")

        assertEquals(BACKGROUND, capture.pixel(capture.cellWidth - 1, 0))
        assertNotEquals(BACKGROUND, capture.pixel(capture.cellWidth - 1, capture.cellHeight / 2))
    }

    @Test
    fun aSeparatorUnderTheBlockCursorIsDrawnInTheBackground() {
        val capture = capture("\uE0B0", focused = true)

        assertEquals(List(capture.cellHeight / 2) { BACKGROUND }, capture.middleOfTheLeftEdge())
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The default background of the default style. */
        const val BACKGROUND = 0xFF000000.toInt()
    }
}
