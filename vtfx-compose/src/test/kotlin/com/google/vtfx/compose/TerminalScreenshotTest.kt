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

@file:OptIn(InternalVtfxApi::class)

package com.google.vtfx.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.session.TerminalSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests against the provisional goldens in src/test/screenshots (docs/design/testing.md
 * section 5). Record new goldens with `./gradlew :vtfx-compose:recordRoborazziDebug`, and explain
 * what changed and why in the commit message.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], qualifiers = "w400dp-h300dp-xhdpi")
class TerminalScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})
    private val display = TerminalDisplay(session)

    private fun show(
        style: TerminalStyle = TerminalStyle(),
        output: String,
        linkPatterns: List<LinkPattern> = listOf(LinkPattern.Urls),
        onLinkClick: ((TerminalLinkClick) -> Unit)? = null,
    ) {
        composeRule.setContent {
            Terminal(
                display,
                Modifier.size(320.dp, 160.dp),
                style = style,
                linkPatterns = linkPatterns,
                onLinkClick = onLinkClick,
            )
        }
        composeRule.waitForIdle()
        session.feed(output.toByteArray(Charsets.UTF_8))
        composeRule.waitForIdle()
    }

    /**
     * The padding takes the default background around the grid, which the host painted blue (the
     * erase takes the current background), with more at the start than at the end.
     */
    @Test
    fun padding() {
        show(
            style =
                TerminalStyle(
                    padding = PaddingValues(start = 24.dp, top = 8.dp, end = 4.dp, bottom = 16.dp)
                ),
            output = "\u001b[44m\u001b[2Jtop left\r\n\$ ",
        )
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun attributes() {
        show(
            output =
                "plain \u001b[1mbold\u001b[0m \u001b[3mitalic\u001b[0m \u001b[4munder\u001b[0m " +
                    "\u001b[9mstrike\u001b[0m \u001b[2mfaint\u001b[0m\r\n" +
                    "\u001b[7minverse\u001b[0m \u001b[8mhidden\u001b[0m end\r\n" +
                    (0..7).joinToString("") { "\u001b[${30 + it}m#" } +
                    (0..7).joinToString("") { "\u001b[${90 + it}m#" } +
                    "\u001b[0m " +
                    (0..7).joinToString("") { "\u001b[${40 + it}m " } +
                    "\u001b[0m\r\n" +
                    "\u001b[38;5;208m256\u001b[0m \u001b[38;2;255;128;0;48;2;0;64;128mtruecolor\u001b[0m\r\n"
        )
        composeRule.onRoot().captureRoboImage()
    }

    /**
     * Each underline style, overline, and underline colors (SGR 58), over whole cells, spaces too.
     * An OSC 8 hyperlink with an underline of its own keeps its style.
     */
    @Test
    fun underlinesAndOverline() {
        show(
            output =
                "\u001b[4msingle\u001b[0m \u001b[4:2mdouble\u001b[0m \u001b[4:3mcurly\u001b[0m\r\n" +
                    "\u001b[4:4mdotted\u001b[0m \u001b[4:5mdashed\u001b[0m \u001b[53moverline\u001b[0m\r\n" +
                    "\u001b[4:3;58;5;9mred curly\u001b[0m \u001b[4;58;2;0;160;255mblue" +
                    "\u001b[59m text\u001b[0m\r\n" +
                    "\u001b[4m   spaces   \u001b[0m| \u001b[2;4:2;53mfaint\u001b[0m " +
                    "\u001b[7;4mreverse\u001b[0m\r\n" +
                    "\u001b]8;;https://example.com\u001b\\link\u001b]8;;\u001b\\ " +
                    "\u001b]8;;https://example.com\u001b\\\u001b[4:3;58;5;11mcurly link\u001b[0m" +
                    "\u001b]8;;\u001b\\ 日本\u001b[4:3m語\u001b[0m\r\n"
        )
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun wideCharacters() {
        show(output = "日本語のテキスト abc\r\n한국어 混在 mixed\r\n\u001b[44m全角の背景\u001b[0m|\r\n")
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun composingTextAtTheCursor() {
        show(output = "$ echo ")
        session.edit { it.setComposingText("にほんご") }
        display.notifyEditorChanged()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun lineDrawingAndHyperlinks() {
        show(
            output =
                "\u001b(0lqqqqwqqqqk\r\nx    x    x\r\nmqqqqvqqqqj\u001b(B\r\n" +
                    "see \u001b]8;;https://example.com\u001b\\example.com\u001b]8;;\u001b\\ here\r\n"
        )
        composeRule.onRoot().captureRoboImage()
    }

    /**
     * URLs and an app's pattern are underlined, across autowrapped rows too, in the text's color.
     */
    @Test
    fun linksInTheText() {
        show(
            output =
                "see https://example.com/a. and \u001b[32mcrbug/123\u001b[0m\r\n" +
                    "wrapped https://example.com/a/long/path/that/autowraps/to/the/next/row/" +
                    "\r\nnot a link: https:// crbug/x\r\n",
            linkPatterns =
                listOf(
                    LinkPattern.Urls,
                    LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}"),
                ),
            onLinkClick = {},
        )
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun boxDrawingAndBlocks() {
        val box = (0x2500..0x257F).joinToString("") { String(Character.toChars(it)) }
        val blocks = (0x2580..0x259F).joinToString("") { String(Character.toChars(it)) }
        show(
            output =
                box.chunked(32).joinToString("\r\n") +
                    "\r\n" +
                    blocks +
                    "\r\n" +
                    "╭──┬──╮ ┏━━┳━━┓ ╔══╦══╗\r\n│  │  │ ┃  ┃  ┃ ║  ║  ║\r\n├──┼──┤ ┣━━╋━━┫ ╠══╬══╣\r\n╰──┴──╯ ┗━━┻━━┛ ╚══╩══╝\r\n"
        )
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun selection() {
        show(output = "first line\r\nsecond 日本語 line\r\nthird\r\n")
        display.selection = TerminalSelection(Cell(6, 0), Cell(10, 1))
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun focusedBarCursor() {
        show(output = "\u001b[5 q$ ")
        composeRule.onRoot().performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    /** The text and the underline under a focused block cursor take `cursorText`. */
    @Test
    fun cursorTextColor() {
        show(
            TerminalStyle(
                colors =
                    TerminalColors.Dark.copy(cursor = Color(0xFFFFCC00), cursorText = Color.Blue)
            ),
            output = "$ \u001b[4:3mtyped\u001b[0m\u001b[3D",
        )
        composeRule.onRoot().performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun lightColors() {
        show(
            TerminalStyle(colors = TerminalColors.Light),
            output = "light \u001b[31mred\u001b[0m \u001b[7minverse\u001b[0m\r\n",
        )
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun reverseVideo() {
        show(output = "\u001b[?5hreverse video \u001b[7minverse\u001b[0m\r\n")
        composeRule.onRoot().captureRoboImage()
    }

    /**
     * Images of the kitty graphics protocol (docs/decisions/0038): scaled to cells; natural size;
     * cut from a source rectangle with an offset in its cell; under the text with a negative
     * z-index; and translucent over the text. Each image is 4 by 4 pixels in four colors: red,
     * green, blue, and translucent white.
     */
    @Test
    fun images() {
        val pixels = ByteArray(4 * 4 * 4)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val rgba =
                    when {
                        y < 2 && x < 2 -> intArrayOf(255, 0, 0, 255)
                        y < 2 -> intArrayOf(0, 160, 0, 255)
                        x < 2 -> intArrayOf(0, 0, 255, 255)
                        else -> intArrayOf(255, 255, 255, 128)
                    }
                for (c in 0 until 4) pixels[(y * 4 + x) * 4 + c] = rgba[c].toByte()
            }
        }
        val data = java.util.Base64.getEncoder().encodeToString(pixels)
        fun image(keys: String) = "\u001b_Ga=T,f=32,s=4,v=4,q=2,$keys;$data\u001b\\"
        show(
            output =
                image("c=6,r=3") +
                    " scaled\r\n\r\n\r\n" +
                    image("") +
                    " natural " +
                    image("x=2,y=2,X=4,Y=6,c=2,r=1") +
                    " cut\r\n" +
                    "text \u001b[1mover\u001b[0m an image " +
                    "\u001b[s\u001b[7;6H" +
                    image("c=4,r=1,z=-1,C=1") +
                    "\u001b[u\r\n" +
                    "under a translucent one" +
                    "\u001b[8;1H" +
                    image("x=2,c=16,r=1,C=1")
        )
        composeRule.onRoot().captureRoboImage()
    }

    /**
     * Unicode placeholders (docs/decisions/0038): a 4 by 4 image of four colors in a virtual
     * placement of 8 by 2 cells, fitted and centered, written as placeholders with only the row
     * diacritic on the first cell of each row, as the protocol's example does; then the same
     * placeholders with their columns in reverse, which show the image in vertical strips swapped.
     */
    @Test
    fun imagePlaceholders() {
        val pixels = ByteArray(4 * 4 * 4)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val rgb =
                    when {
                        y < 2 && x < 2 -> intArrayOf(255, 0, 0)
                        y < 2 -> intArrayOf(0, 160, 0)
                        x < 2 -> intArrayOf(0, 0, 255)
                        else -> intArrayOf(255, 255, 255)
                    }
                for (c in 0 until 3) pixels[(y * 4 + x) * 4 + c] = rgb[c].toByte()
                pixels[(y * 4 + x) * 4 + 3] = 255.toByte()
            }
        }
        val data = java.util.Base64.getEncoder().encodeToString(pixels)
        // The diacritics for 0 to 7, from the protocol's list.
        val diacritics = intArrayOf(0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F)
        fun cell(vararg values: Int) = buildString {
            appendCodePoint(0x10EEEE)
            for (value in values) appendCodePoint(diacritics[value])
        }
        show(
            output =
                "\u001b_Ga=T,i=42,U=1,c=8,r=2,f=32,s=4,v=4,q=2;$data\u001b\\" +
                    "\u001b[38;5;42m" +
                    cell(0) +
                    cell().repeat(7) +
                    "\u001b[39m fitted\r\n\u001b[38;5;42m" +
                    cell(1) +
                    cell().repeat(7) +
                    "\u001b[39m\r\n\u001b[38;5;42m" +
                    (7 downTo 0).joinToString("") { cell(0, it) } +
                    "\u001b[39m reversed\r\n\u001b[38;5;42m" +
                    (7 downTo 0).joinToString("") { cell(1, it) } +
                    "\u001b[39m"
        )
        composeRule.onRoot().captureRoboImage()
    }
}
