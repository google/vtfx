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

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.session.TerminalSession
import java.util.Base64
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks on a device that what the display shows matches the terminal state (docs/design/testing.md
 * section 8). The display draws with hardware acceleration, which caches the drawing of lines
 * (docs/decisions/0014); the reference draws the same snapshot on a software canvas, which does
 * not. Each cell must have ink in both or in neither. Exact pixels are not compared, since the two
 * pipelines antialias text differently.
 *
 * Each step feeds host output and lets the display draw at least one frame, since the cache keeps
 * state across frames.
 */
@RunWith(AndroidJUnit4::class)
class RenderConsistencyTest {
    @get:Rule val composeRule = createComposeRule()

    // A small scrollback, so that the tests also reach its capacity.
    private val session =
        TerminalSession({}, limits = TerminalLimits(scrollbackLines = SCROLLBACK_LINES))
    private val display = TerminalDisplay(session)
    private val style = TerminalStyle()
    private var width by mutableStateOf(320.dp)
    private lateinit var density: Density
    private lateinit var fontFamilyResolver: FontFamily.Resolver

    private fun show() {
        composeRule.setContent {
            density = LocalDensity.current
            fontFamilyResolver = LocalFontFamilyResolver.current
            Terminal(display, Modifier.size(width, 200.dp), style = style)
        }
        settle()
    }

    /** Feeds [output] and waits until the display has drawn it. */
    private fun step(output: String) {
        session.feed(output.toByteArray())
        settle()
    }

    private fun settle() {
        composeRule.waitForIdle()
        // A few frames, so that the renderer has synced what the UI thread recorded.
        SystemClock.sleep(SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    /** Fails if a cell has ink on the display but not in the reference, or the other way round. */
    private fun assertDisplayMatchesState() {
        val shown = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val reference = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888)
        val paints = textPaints()
        val metrics = CellMetrics.measure(paints)
        val renderer = TerminalRenderer(paints, style, metrics, density)
        val snapshot = display.displaySnapshot()
        composeRule.runOnIdle {
            CanvasDrawScope().draw(
                density,
                LayoutDirection.Ltr,
                Canvas(android.graphics.Canvas(reference)),
                Size(shown.width.toFloat(), shown.height.toFloat()),
            ) {
                with(renderer) {
                    draw(
                        snapshot,
                        ImageBitmaps(),
                        links = emptyList(),
                        session.edit { it.composingText },
                        session.ambiguousWidth,
                        focused = false,
                        selection = null,
                    )
                }
            }
        }
        val background = style.colors.background.toArgb()
        val mismatches = mutableListOf<String>()
        val map = StringBuilder()
        for (row in 0 until snapshot.rows) {
            for (column in 0 until snapshot.columns) {
                val left = (column * metrics.width).toInt() + INSET
                val top = (row * metrics.height).toInt() + INSET
                val right = ((column + 1) * metrics.width).toInt() - INSET
                val bottom = ((row + 1) * metrics.height).toInt() - INSET
                val inkShown = ink(shown, left, top, right, bottom, background)
                val inkReference = ink(reference, left, top, right, bottom, background)
                map.append(
                    when {
                        inkShown == inkReference -> if (inkShown) '#' else '.'
                        inkShown -> '+'
                        else -> '-'
                    }
                )
                if (inkShown != inkReference)
                    mismatches += "($column,$row) ${if (inkShown) "extra" else "missing"}"
            }
            map.append('\n')
        }
        if (mismatches.isNotEmpty()) {
            fail(
                "The display differs from the terminal state at ${mismatches.joinToString()}:\n" +
                    "(# ink in both, . in neither, + only on the display, - only in the reference)\n$map"
            )
        }
    }

    private fun textPaints(): TextPaints =
        TextPaints(
            style,
            density,
            resolveTextTypefaces(style.font, fontFamilyResolver),
        )

    /**
     * Fails unless the middle of each cell in [cells] (column to row) shows [rgb] on the display,
     * so that a test does not pass when neither the display nor the reference draws an image.
     */
    private fun assertShows(rgb: Int, vararg cells: Pair<Int, Int>) {
        val shown = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val metrics = CellMetrics.measure(textPaints())
        for ((column, row) in cells) {
            val x = ((column + 0.5f) * metrics.width).toInt()
            val y = ((row + 0.5f) * metrics.height).toInt()
            val pixel = shown.getPixel(x, y)
            if (differs(pixel, rgb)) {
                fail(
                    "The cell ($column,$row) shows #%06X instead of #%06X"
                        .format(pixel and 0xFFFFFF, rgb and 0xFFFFFF)
                )
            }
        }
    }

    /**
     * Returns a graphics command that transmits an image of 4 by 4 pixels of the color [rgb], with
     * the control data [keys].
     */
    private fun image(keys: String, rgb: Int): String {
        val pixels = ByteArray(4 * 4 * 4)
        for (i in 0 until 4 * 4) {
            pixels[i * 4] = (rgb shr 16).toByte()
            pixels[i * 4 + 1] = (rgb shr 8).toByte()
            pixels[i * 4 + 2] = rgb.toByte()
            pixels[i * 4 + 3] = 0xFF.toByte()
        }
        val data = Base64.getEncoder().encodeToString(pixels)
        return "\u001b_G$keys,f=32,s=4,v=4,q=2;$data\u001b\\"
    }

    private fun ink(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        background: Int,
    ): Boolean {
        var count = 0
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in left until minOf(right, bitmap.width)) {
                if (differs(bitmap.getPixel(x, y), background)) count++
            }
        }
        return count >= MIN_INK_PIXELS
    }

    private fun differs(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > TOLERANCE ||
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > TOLERANCE ||
            abs((a and 0xFF) - (b and 0xFF)) > TOLERANCE

    @Test
    fun promptAfterACommand() {
        // Found on a device: the line "$ " came back after one frame without it and was not shown.
        show()
        step("\u001b[32m$\u001b[0m ")
        step("a")
        step("\r\na\r\n\u001b[32m$\u001b[0m ")
        assertDisplayMatchesState()
        step("b")
        step("\b \b")
        assertDisplayMatchesState()
    }

    @Test
    fun scrolling() {
        show()
        for (i in 1..40) step("line $i\r\n")
        assertDisplayMatchesState()
    }

    @Test
    fun outputWhileViewingTheScrollback() {
        show()
        for (i in 1..SCROLLBACK_LINES + 10) step("line $i\r\n")
        composeRule.runOnIdle { display.scrollBy(5) }
        settle()
        assertDisplayMatchesState()
        val top = display.displaySnapshot().line(0).text()
        // The lines shown stay while output arrives, and while the full scrollback drops lines.
        for (i in 1..3) step("more $i\r\n")
        assertEquals(top, display.displaySnapshot().line(0).text())
        assertDisplayMatchesState()
    }

    @Test
    fun theSameContentAfterClearing() {
        show()
        val page = (1..5).joinToString("\r\n") { "row $it" }
        step(page)
        step("\u001b[2J\u001b[H")
        assertDisplayMatchesState()
        step(page)
        assertDisplayMatchesState()
    }

    @Test
    fun leavingTheAlternateScreen() {
        show()
        step("main 1\r\nmain 2\r\n$ ")
        step("\u001b[?1049h\u001b[H\u001b[2Jalternate")
        step("\u001b[2;1Hmore")
        step("\u001b[?1049l")
        assertDisplayMatchesState()
    }

    @Test
    fun colorsAndReverseVideo() {
        show()
        step("\u001b[41m red \u001b[0m \u001b[7m inverse \u001b[0m\r\n$ ")
        step("\u001b[?5h")
        step("\u001b[?5l")
        assertDisplayMatchesState()
    }

    @Test
    fun resizing() {
        show()
        step("a long line that wraps when the terminal is narrow\r\n$ ")
        width = 200.dp
        settle()
        settle()
        assertDisplayMatchesState()
        width = 320.dp
        settle()
        settle()
        assertDisplayMatchesState()
    }

    @Test
    fun imageAfterAPrompt() {
        // As kitten icat shows an image: put at the cursor, which then goes past it.
        show()
        step("\u001b[32m$\u001b[0m icat\r\n")
        step(image("a=T,c=4,r=2", RED))
        step("\r\n\u001b[32m$\u001b[0m ")
        assertDisplayMatchesState()
        assertShows(RED, 0 to 1, 3 to 1, 0 to 2, 3 to 2)
    }

    @Test
    fun imagesScrollWithTheText() {
        // The line cache must not keep an image where its line was, nor drop it from the
        // scrollback.
        show()
        step("before\r\n")
        step(image("a=T,c=3,r=1", BLUE))
        step("\r\n")
        for (i in 1..20) step("line $i\r\n")
        assertDisplayMatchesState()
        assertEquals(emptyList<Any>(), display.displaySnapshot().imagePlacements)
        composeRule.runOnIdle { display.scrollBy(SCROLLBACK_LINES) }
        settle()
        assertDisplayMatchesState()
        val placement = display.displaySnapshot().imagePlacements.single()
        assertShows(BLUE, placement.column to placement.row, 2 to placement.row)
    }

    @Test
    fun imageUnderText() {
        // A negative z-index draws under the text, and under the backgrounds of cells.
        show()
        step(image("a=T,c=6,r=2,z=-1,C=1", GREEN))
        step("\u001b[1mbold text\u001b[0m")
        assertDisplayMatchesState()
        assertShows(GREEN, 2 to 1, 5 to 1)
    }

    @Test
    fun deletedImagesGo() {
        show()
        step(image("a=T,i=7,c=4,r=2", RED))
        step("\r\n$ ")
        assertShows(RED, 1 to 0)
        step("\u001b_Ga=d,d=I,i=7,q=2\u001b\\")
        assertEquals(emptyList<Any>(), display.displaySnapshot().imagePlacements)
        assertDisplayMatchesState()
    }

    @Test
    fun unicodePlaceholders() {
        // As tmux passes images through: a virtual placement and placeholder cells colored with
        // the image id, the first of each row with its row diacritic.
        show()
        step(image("a=T,i=42,U=1,c=4,r=2", BLUE))
        val placeholder = String(Character.toChars(0x10EEEE))
        val rows = listOf(0x0305, 0x030D).map { String(Character.toChars(it)) }
        step(
            "\u001b[38;5;42m" +
                rows.joinToString("\u001b[39m\r\n\u001b[38;5;42m") {
                    placeholder + it + placeholder.repeat(3)
                } +
                "\u001b[39m\r\n$ "
        )
        assertDisplayMatchesState()
        assertShows(BLUE, 0 to 0, 3 to 0, 0 to 1, 3 to 1)
    }

    @Test
    fun imagesOfTheMainScreenAfterTheAlternateScreen() {
        show()
        step(image("a=T,c=4,r=1", RED))
        step("\r\n$ ")
        step("\u001b[?1049h\u001b[H\u001b[2J")
        step(image("a=T,c=4,r=1", GREEN))
        assertShows(GREEN, 1 to 0)
        step("\u001b[?1049l")
        assertDisplayMatchesState()
        assertShows(RED, 1 to 0)
    }

    private companion object {
        const val SETTLE_MILLIS = 120L
        const val INSET = 1
        const val TOLERANCE = 40
        const val MIN_INK_PIXELS = 3
        const val SCROLLBACK_LINES = 30
        const val RED = 0xFFE00000.toInt()
        const val GREEN = 0xFF00C000.toInt()
        const val BLUE = 0xFF0000E0.toInt()
    }
}
