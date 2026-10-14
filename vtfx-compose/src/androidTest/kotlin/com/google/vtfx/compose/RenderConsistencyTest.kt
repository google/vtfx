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
        val paints =
            TextPaints(
                style,
                density,
                TextTypefaces.resolve(style.font.toFontFamily(), fontFamilyResolver),
            )
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

    private companion object {
        const val SETTLE_MILLIS = 120L
        const val INSET = 1
        const val TOLERANCE = 40
        const val MIN_INK_PIXELS = 3
        const val SCROLLBACK_LINES = 30
    }
}
