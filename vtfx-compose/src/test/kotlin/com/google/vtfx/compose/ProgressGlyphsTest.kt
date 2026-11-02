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
 * Checks that the progress bar and spinner characters (U+EE00 to U+EE0B) are drawn as shapes, which
 * the default font has no glyphs for (section 7.3 of emulation.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class ProgressGlyphsTest {
    @get:Rule val composeRule = createComposeRule()

    /** The size of a cell in pixels, and the screen after the host wrote to it. */
    private class Screen(val bitmap: Bitmap, val cellWidth: Int, val cellHeight: Int)

    /**
     * Shows [output] at the start of the third row, with the block cursor on its first cell if
     * [focused] and no cursor otherwise. The first row is one cell in reverse video, which gives
     * the size of a cell.
     */
    private fun show(output: String, focused: Boolean = false): Screen {
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
        return Screen(bitmap, cellWidth, cellHeight)
    }

    /**
     * The colors down the middle of the cell in [column] of the third row, top to bottom, with each
     * run of one color as one entry. The rows of pixels at the top and bottom edges of the cell are
     * left out, since the cell's edges need not fall on whole pixels.
     */
    private fun Screen.colorsDown(column: Int): List<Int> {
        val x = column * cellWidth + cellWidth / 2
        val colors = (2 * cellHeight + 1 until 3 * cellHeight - 1).map { bitmap.getPixel(x, it) }
        return colors.filterIndexed { i, color -> i == 0 || color != colors[i - 1] }
    }

    /**
     * The runs of pixels of the default foreground along the top row of pixels of the third row
     * that has any, which is the top of a bar's outline.
     */
    private fun Screen.outlineTopRuns(): List<IntRange> {
        val y =
            (2 * cellHeight until 3 * cellHeight).first { y ->
                (0 until bitmap.width).any { bitmap.getPixel(it, y) == FOREGROUND }
            }
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

    /** Whether any pixel down the middle of the cell in [column] of the third row is not blank. */
    private fun Screen.inkDown(column: Int, rows: IntRange): Boolean {
        val x = column * cellWidth + cellWidth / 2
        return rows.any { bitmap.getPixel(x, 2 * cellHeight + it) != BACKGROUND }
    }

    @Test
    fun theOutlineOfABarRunsUnbrokenAcrossItsSegments() {
        val screen = show("\uEE00\uEE01\uEE01\uEE02")

        assertEquals(1, screen.outlineTopRuns().size)
    }

    @Test
    fun theFillOfAFilledSegmentIsInsetInsideTheOutline() {
        val screen = show("\uEE04")

        assertEquals(
            listOf(
                BACKGROUND,
                FOREGROUND,
                BACKGROUND,
                FOREGROUND,
                BACKGROUND,
                FOREGROUND,
                BACKGROUND,
            ),
            screen.colorsDown(0),
        )
    }

    @Test
    fun anEmptySegmentIsOnlyTheOutline() {
        val screen = show("\uEE01")

        assertEquals(
            listOf(BACKGROUND, FOREGROUND, BACKGROUND, FOREGROUND, BACKGROUND),
            screen.colorsDown(0),
        )
    }

    @Test
    fun aSegmentUnderTheBlockCursorIsDrawnInTheBackground() {
        val screen = show("\uEE04", focused = true)

        assertEquals(
            listOf(
                FOREGROUND,
                BACKGROUND,
                FOREGROUND,
                BACKGROUND,
                FOREGROUND,
                BACKGROUND,
                FOREGROUND,
            ),
            screen.colorsDown(0),
        )
    }

    @Test
    fun theHalfCircleSpinnerFrameIsTheLowerHalf() {
        val screen = show("\uEE09")
        val half = screen.cellHeight / 2

        assertEquals(
            listOf(false, true),
            listOf(
                screen.inkDown(0, 0 until half - 1),
                screen.inkDown(0, half until screen.cellHeight),
            ),
        )
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The default background of the default style. */
        const val BACKGROUND = 0xFF000000.toInt()
    }
}
