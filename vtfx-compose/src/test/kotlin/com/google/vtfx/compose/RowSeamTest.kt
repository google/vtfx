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

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Checks that what fills whole cells joins across rows without seams, although the cell height is
 * not a whole number of pixels: a seam is a row of pixels between two filled rows that has another
 * color, where two antialiased edges of the rows each cover part of the pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], qualifiers = "w400dp-h300dp-xhdpi")
class RowSeamTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})
    private val display = TerminalDisplay(session)

    private fun show(output: String) {
        composeRule.setContent { Terminal(display, Modifier.size(320.dp, 160.dp)) }
        composeRule.waitForIdle()
        session.feed(output.toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
    }

    /**
     * The colors of the pixels at [x], from the first pixel of the color at the top of the first
     * row to its last pixel: only that color if the fill has no seams.
     */
    private fun colorsBetween(x: Int): List<String> {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val column = (0 until bitmap.height).map { bitmap.getPixel(x, it) }
        val color = column[TOP]
        val first = column.indexOf(color)
        val last = column.lastIndexOf(color)
        return column.subList(first, last + 1).map { "%08x".format(it) }.distinct()
    }

    @Test
    fun backgroundsJoinAcrossRows() {
        show("\u001b[44m\u001b[2J")
        assertEquals(listOf("ff0000ee"), colorsBetween(x = 100))
    }

    @Test
    fun selectionJoinsAcrossRows() {
        show("a\r\nb\r\nc\r\nd\r\n")
        display.selection = display.selectionOf(Cell(0, 0), Cell(30, 3))
        composeRule.waitForIdle()
        assertEquals(1, colorsBetween(x = 300).size)
    }

    @Test
    fun blocksJoinAcrossRows() {
        // The cursor ends in the second column, away from the blocks.
        show("\u2588\r\n\u2588\r\n\u2588\r\n\u2588")
        assertEquals(listOf("ffe5e5e5"), colorsBetween(x = 8))
    }

    private companion object {
        /** A pixel row inside the first row of cells. */
        const val TOP = 5
    }
}
