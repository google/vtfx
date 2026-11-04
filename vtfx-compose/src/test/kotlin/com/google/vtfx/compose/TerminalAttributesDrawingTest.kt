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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks the colors that the attributes of cells give what is drawn besides the glyphs of fonts:
 * built-in glyphs and the underlines of links (section 8.2 of emulation.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalAttributesDrawingTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})

    /** Shows [output] in large cells, focused, with links matched, and returns its pixels. */
    private fun pixels(
        output: String,
        style: TerminalStyle = TerminalStyle(fontSize = 40.sp),
    ): IntArray {
        val requester = FocusRequester()
        composeRule.setContent {
            Terminal(
                session,
                Modifier.size(600.dp, 300.dp).focusRequester(requester),
                style = style,
                onLinkClick = { true },
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { requester.requestFocus() }
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    @Test
    fun aFullBlockIsDrawnInTheTextColor() {
        // The check of the test below: the block shows the color where it is not faint.
        assertTrue(RED.toArgb() in pixels("$HIDE_CURSOR$SGR_RED\u2588"))
    }

    @Test
    fun aFaintFullBlockIsDrawnFaint() {
        // A built-in glyph is a character of the text, drawn in its color with faint applied.
        assertFalse(RED.toArgb() in pixels("$HIDE_CURSOR$SGR_RED\u001b[2m\u2588"))
    }

    @Test
    fun theUnderlineOfALinkInTheTextIsDrawnInTheTextColor() {
        assertTrue(RED.toArgb() in pixels("$HIDE_CURSOR$SGR_RED$URL"))
    }

    @Test
    fun theUnderlineOfAFaintLinkInTheTextIsDrawnFaint() {
        // The underline takes the color of the text with faint applied; the faint text has no
        // pixel of the full color either.
        assertFalse(RED.toArgb() in pixels("$HIDE_CURSOR$SGR_RED\u001b[2m$URL"))
    }

    private companion object {
        val RED = Color(0xFFFF0000)
        const val HIDE_CURSOR = "\u001b[?25l"
        const val SGR_RED = "\u001b[38;2;255;0;0m"
        const val URL = "https://example.com/"
    }
}
