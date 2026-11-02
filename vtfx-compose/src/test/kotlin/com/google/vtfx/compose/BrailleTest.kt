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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks that braille patterns are drawn as dots on a grid over the cell (section 7.3). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class BrailleTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * The colors at the centers of the eight places of dots of the cell at the start of the third
     * row, by column (left, then right) and row (top to bottom), after the host writes [output]
     * there. The first row is one cell in reverse video, which gives the size of a cell.
     */
    private fun dotColors(output: String, focused: Boolean = false): List<List<Int>> {
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
        return (0..1).map { column ->
            (0..3).map { row -> bitmap.dotColor(column, row, cellWidth, cellHeight) }
        }
    }

    private fun Bitmap.dotColor(column: Int, row: Int, cellWidth: Int, cellHeight: Int): Int =
        getPixel(
            ((column + 0.5f) * cellWidth / 2).toInt(),
            (2 * cellHeight + (row + 0.5f) * cellHeight / 4).toInt(),
        )

    @Test
    fun allEightDotsFillTheirPlaces() {
        val dots = dotColors("\u28FF")

        assertEquals(List(2) { List(4) { FOREGROUND } }, dots)
    }

    @Test
    fun theBitsOfTheCodePointRaiseDotsOneToEight() {
        // Dots 1, 5, and 7: the top left, the right of the second row, and the bottom left.
        val dots = dotColors("\u2851")

        assertEquals(
            listOf(
                listOf(FOREGROUND, BACKGROUND, BACKGROUND, FOREGROUND),
                listOf(BACKGROUND, FOREGROUND, BACKGROUND, BACKGROUND),
            ),
            dots,
        )
    }

    @Test
    fun aPatternUnderTheBlockCursorIsDrawnInTheBackground() {
        val dots = dotColors("\u28FF", focused = true)

        assertEquals(List(2) { List(4) { BACKGROUND } }, dots)
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The default background of the default style. */
        const val BACKGROUND = 0xFF000000.toInt()
    }
}
