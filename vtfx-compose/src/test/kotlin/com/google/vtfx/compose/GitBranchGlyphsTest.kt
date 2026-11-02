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
 * Checks that the branch drawing characters (U+F5D0 to U+F60D) are drawn as shapes that join box
 * drawing characters, which the default font has no glyphs for (section 7.3 of emulation.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class GitBranchGlyphsTest {
    @get:Rule val composeRule = createComposeRule()

    /** The size of a cell in pixels, and the screen after the host wrote to it. */
    private class Screen(val bitmap: Bitmap, val cellWidth: Int, val cellHeight: Int)

    /**
     * Shows [output] at the start of the third row, with the block cursor on its first cell if
     * [focused] and no cursor otherwise. The first row is [cells] cells in reverse video, which
     * fill them with the default foreground and give the size of a cell.
     */
    private fun show(output: String, focused: Boolean = false, cells: Int = 1): Screen {
        val session = TerminalSession({})
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        if (focused) composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        val cursor = if (focused) "\u001b[3;1H" else "\u001b[?25l"
        val filled = " ".repeat(cells)
        session.feed("\u001b[7m$filled\u001b[m\r\n\r\n$output$cursor".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val cellWidth = (0 until bitmap.width).first { bitmap.getPixel(it, 0) != FOREGROUND }
        val cellHeight = (0 until bitmap.height).first { bitmap.getPixel(0, it) != FOREGROUND }
        return Screen(bitmap, cellWidth / cells, cellHeight)
    }

    private fun Bitmap.runs(y: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < width) {
            if (getPixel(x, y) != FOREGROUND) {
                x++
                continue
            }
            val start = x
            while (x < width && getPixel(x, y) == FOREGROUND) x++
            runs += start until x
        }
        return runs
    }

    /**
     * The runs of pixels of the default foreground along a pixel row of the first row, and along
     * the pixel row of the third row that has the most of them.
     */
    private fun Screen.rowRuns(): Pair<List<IntRange>, List<IntRange>> {
        val y =
            (2 * cellHeight until 3 * cellHeight).maxBy { y ->
                (0 until bitmap.width).count { bitmap.getPixel(it, y) == FOREGROUND }
            }
        return Pair(bitmap.runs(0), bitmap.runs(y))
    }

    /**
     * Whether the default foreground reaches the top, right, bottom, and left edges of the first
     * cell of the third row. The rows and columns of pixels next to the edges are read, since the
     * edges need not fall on whole pixels.
     */
    private fun Screen.edgesReached(): List<Boolean> {
        val top = 2 * cellHeight + 1
        val bottom = 3 * cellHeight - 2
        val left = 1
        val right = cellWidth - 2
        fun rowReached(y: Int) = (left..right).any { bitmap.getPixel(it, y) == FOREGROUND }
        fun columnReached(x: Int) = (top..bottom).any { bitmap.getPixel(x, it) == FOREGROUND }
        return listOf(
            rowReached(top),
            columnReached(right),
            rowReached(bottom),
            columnReached(left),
        )
    }

    /** The color at the center of the first cell of the third row. */
    private fun Screen.centerColor(): Int =
        bitmap.getPixel(cellWidth / 2, 2 * cellHeight + cellHeight / 2)

    @Test
    fun aBranchLineJoinsBoxDrawingLinesWithoutAGap() {
        val (cells, line) = show("\u2500\uF5D0\u2500", cells = 3).rowRuns()

        assertEquals(cells, line)
    }

    @Test
    fun aCommitWithLinesRightAndDownReachesOnlyThoseEdges() {
        val edges = show("\uF5FC").edgesReached()

        assertEquals(listOf(false, true, true, false), edges)
    }

    @Test
    fun aLineWithABranchDownReachesTheLeftRightAndBottomEdges() {
        val edges = show("\uF5E0").edgesReached()

        assertEquals(listOf(false, true, true, true), edges)
    }

    @Test
    fun aFilledCommitFillsItsCenter() {
        val center = show("\uF5EE").centerColor()

        assertEquals(FOREGROUND, center)
    }

    @Test
    fun aHollowCommitLeavesItsCenterEmpty() {
        val center = show("\uF5EF").centerColor()

        assertEquals(BACKGROUND, center)
    }

    @Test
    fun aCommitUnderTheBlockCursorIsDrawnInTheBackground() {
        val center = show("\uF5EE", focused = true).centerColor()

        assertEquals(BACKGROUND, center)
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The default background of the default style. */
        const val BACKGROUND = 0xFF000000.toInt()
    }
}
