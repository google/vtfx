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

/**
 * Checks that the symbols for legacy computing are drawn as shapes that fill their cells (section
 * 7.3 of emulation.md), which no font on the test's system has glyphs for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class LegacyComputingTest {
    @get:Rule val composeRule = createComposeRule()

    private fun capture(session: TerminalSession, output: String): Bitmap {
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        session.feed(output.toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        return composeRule.onRoot().captureToImage().asAndroidBitmap()
    }

    /**
     * Shows, without the cursor, [cells] cells in reverse video, which fill them with the default
     * foreground, on the first row, and [text] on the third. Returns the runs of pixels of the
     * default foreground along a pixel row of the first row and along the pixel row of the third
     * row that has the most of them.
     */
    private fun foregroundRuns(cells: Int, text: String): Pair<List<IntRange>, List<IntRange>> {
        val filled = " ".repeat(cells)
        val bitmap =
            capture(TerminalSession({}), "\u001b[?25l\u001b[7m$filled\u001b[m\r\n\r\n$text")
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
    fun aRowOfSextantsIsUnbroken() {
        // U+1FB0B BLOCK SEXTANT-34: the middle third of the cell.
        val (cells, sextants) = foregroundRuns(4, "\uD83E\uDF0B".repeat(4))

        assertEquals(cells, sextants)
    }

    @Test
    fun aRowOfHeavyHorizontalFillsIsUnbroken() {
        val (cells, fills) = foregroundRuns(4, "\uD83E\uDF97".repeat(4))

        assertEquals(cells, fills)
    }

    /**
     * Shows [text] under the focused block cursor, which the default foreground fills, and returns
     * how many pixels of the default foreground are left after the character is drawn over it in
     * the default background.
     */
    private fun cursorPixelsLeftOver(text: String): Int {
        val session = TerminalSession({})
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
    fun aSextantUnderTheCursorLeavesTheOtherSextants() {
        // U+1FB3B BLOCK SEXTANT-23456 leaves sextant 1, the upper left, of the cursor.
        assertEquals(UPPER_LEFT_SEXTANT_PIXELS, cursorPixelsLeftOver("\uD83E\uDF3B"))
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The pixels of a sixth of a cell at the test's density. */
        const val UPPER_LEFT_SEXTANT_PIXELS = 20
    }
}
