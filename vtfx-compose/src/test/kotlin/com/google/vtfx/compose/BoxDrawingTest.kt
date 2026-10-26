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
import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks that box drawing characters and block elements fill the cells they occupy, also when East
 * Asian Ambiguous characters are two cells wide (section 7.3 of emulation.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class BoxDrawingTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * Shows, without the cursor, [cells] cells in reverse video, which fill them with the default
     * foreground, on the first row, and [text] on the third. Returns the runs of pixels of the
     * default foreground along a pixel row of the first row and along the pixel row of the third
     * row that has the most of them.
     */
    private fun foregroundRuns(cells: Int, text: String): Pair<List<IntRange>, List<IntRange>> {
        val session = TerminalSession({}, ambiguousWidth = AmbiguousWidth.Wide)
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        val filled = " ".repeat(cells)
        session.feed("\u001b[?25l\u001b[7m$filled\u001b[m\r\n\r\n$text".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val rowHeight = (0 until bitmap.height).first { bitmap.getPixel(0, it) != FOREGROUND }
        val y =
            (2 * rowHeight until 3 * rowHeight).maxBy { y ->
                (0 until bitmap.width).count { bitmap.getPixel(it, y) == FOREGROUND }
            }
        return Pair(runs(bitmap, 0), runs(bitmap, y))
    }

    private fun runs(bitmap: Bitmap, y: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < bitmap.width) {
            if (bitmap.getPixel(x, y) != FOREGROUND) {
                x++
                continue
            }
            val start = x
            while (x < bitmap.width && bitmap.getPixel(x, y) == FOREGROUND) x++
            runs += start until x
        }
        return runs
    }

    @Test
    fun aLineOfWideBoxDrawingCharactersIsUnbroken() {
        val (cells, line) = foregroundRuns(8, "\u2500".repeat(4))

        assertEquals(cells, line)
    }

    @Test
    fun aWideFullBlockFillsBothCells() {
        val (cells, block) = foregroundRuns(2, "\u2588")

        assertEquals(cells, block)
    }

    /**
     * Shows [text] under the focused block cursor, which the default foreground fills, and returns
     * how many pixels of the default foreground are left after the character is drawn over it in
     * the default background.
     */
    private fun cursorPixelsLeftOver(ambiguousWidth: AmbiguousWidth, text: String): Int {
        val session = TerminalSession({}, ambiguousWidth = ambiguousWidth)
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        session.feed("$text\u001b[1;1H".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        return (0 until bitmap.height).sumOf { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == FOREGROUND }
        }
    }

    @Test
    fun aWideFullBlockUnderTheCursorFillsBothCells() {
        assertEquals(0, cursorPixelsLeftOver(AmbiguousWidth.Wide, "\u2588"))
    }

    @Test
    fun aFullBlockUnderTheCursorFillsItsCell() {
        assertEquals(0, cursorPixelsLeftOver(AmbiguousWidth.Narrow, "\u2588"))
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()
    }
}
