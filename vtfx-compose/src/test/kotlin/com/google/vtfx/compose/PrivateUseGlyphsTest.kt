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
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.SanitizedFamily
import com.google.vtfx.font.gate.SanitizedFont
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks that the glyphs of private use characters are fitted into the cells they may take (section
 * 7.3 of emulation.md), with a font whose U+E000 is an icon 1.8 cells wide that advances one cell,
 * as in a Nerd Font that is not "Mono".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class PrivateUseGlyphsTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * The font of sanitized-icons.ttf: units of 1000 to the em, an ascender of 800 and a descender
     * of 200, "M" a square 400 units wide that advances 500, U+E000 a rectangle from 0 to 900 wide
     * and from 0 to 700 high that advances 500, and U+E001 the same square as "M".
     */
    private val font =
        TerminalFont.Sanitized(
            listOf(
                SanitizedFamily(
                    SanitizedFont.fromCanonical(
                        checkNotNull(javaClass.getResourceAsStream("sanitized-icons.ttf"))
                            .readBytes(),
                        FontLimits(),
                    )
                )
            )
        )

    /**
     * The color, after the host writes [output] at the start of the third row, of the pixel in the
     * middle of the second cell, 600 units above the baseline: a pixel that U+E000 covers when it
     * takes two cells, and that neither U+E001 (400 units high) nor U+E000 fitted into one cell
     * (scaled about the middle of its ink to 389 units high) covers. The first row is one cell in
     * reverse video, which gives the size of a cell.
     */
    private fun pixelAfterTheIcon(output: String): Int {
        val session = TerminalSession({})
        composeRule.setContent {
            Terminal(session, Modifier.size(300.dp, 200.dp), style = TerminalStyle(font = font))
        }
        composeRule.waitForIdle()
        session.feed("\u001b[?25l\u001b[7m \u001b[m\r\n\r\n$output".toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val cellWidth = (0 until bitmap.width).first { bitmap.getPixel(it, 0) != FOREGROUND }
        val cellHeight = (0 until bitmap.height).first { bitmap.getPixel(0, it) != FOREGROUND }
        return bitmap.getPixel((cellWidth * 1.5f).toInt(), (cellHeight * 2.2f).toInt())
    }

    @Test
    fun anIconBeforeABlankCellTakesBothCells() {
        assertEquals(FOREGROUND, pixelAfterTheIcon("\uE000 "))
    }

    @Test
    fun anIconBeforeACharacterIsFittedIntoItsCell() {
        assertEquals(BACKGROUND, pixelAfterTheIcon("\uE000\uE001"))
    }

    private companion object {
        /** The default foreground of the default style. */
        const val FOREGROUND = 0xFFE5E5E5.toInt()

        /** The default background of the default style. */
        const val BACKGROUND = 0xFF000000.toInt()
    }
}
