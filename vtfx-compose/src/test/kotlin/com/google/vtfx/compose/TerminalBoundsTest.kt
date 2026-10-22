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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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

/** Checks that what the host writes is drawn only within the bounds of the [Terminal]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalBoundsTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})

    /** The pixels of the app's white box within the given bounds that are no longer white. */
    private fun pixelsDrawnOver(left: Int, top: Int, right: Int, bottom: Int): Int {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        return (top until minOf(bottom, bitmap.height)).sumOf { y ->
            (left until minOf(right, bitmap.width)).count { x ->
                bitmap.getPixel(x, y) != Color.White.toArgb()
            }
        }
    }

    private fun feed(text: String) {
        // Hides the cursor, which is not what is checked.
        session.feed("\u001b[?25l$text".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
    }

    @Test
    fun stackedCombiningMarksDoNotDrawAboveTheTerminal() {
        composeRule.setContent {
            Column {
                Box(Modifier.size(300.dp, 100.dp).background(Color.White))
                Terminal(session, Modifier.size(300.dp, 200.dp))
            }
        }
        composeRule.waitForIdle()
        feed(("a" + "\u0346".repeat(31)).repeat(20))

        val box = with(composeRule.density) { 100.dp.roundToPx() }
        val width = with(composeRule.density) { 300.dp.roundToPx() }
        assertEquals(0, pixelsDrawnOver(0, 0, width, box))
    }

    @Test
    fun aWideGlyphInTheLastColumnDoesNotDrawRightOfTheTerminal() {
        composeRule.setContent {
            Box {
                // Drawn first, as a toolbar above the terminal in a Column is.
                Box(Modifier.padding(start = 200.dp).size(200.dp, 100.dp).background(Color.White))
                Terminal(session, Modifier.size(200.dp, 100.dp))
            }
        }
        composeRule.waitForIdle()
        val columns = session.snapshot().columns
        // U+FDFD is one cell wide, but its glyph is several cells wide.
        feed("\u001b[1;${columns}H\uFDFD")

        val left = with(composeRule.density) { 200.dp.roundToPx() }
        val right = with(composeRule.density) { 400.dp.roundToPx() }
        val bottom = with(composeRule.density) { 100.dp.roundToPx() }
        assertEquals(0, pixelsDrawnOver(left, 0, right, bottom))
    }
}
