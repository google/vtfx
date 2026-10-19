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

import android.content.Context
import android.graphics.Typeface
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.core.CursorShape
import com.google.vtfx.font.FontLimits
import com.google.vtfx.font.SanitizedFamily
import com.google.vtfx.font.SanitizedFont
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalStyleTest {
    @get:Rule val composeRule = createComposeRule()

    private val columns = mutableListOf<Int>()
    private val session =
        TerminalSession(
            {},
            listener =
                object : TerminalSessionListener {
                    override fun onResize(columns: Int, rows: Int) {
                        this@TerminalStyleTest.columns += columns
                    }
                },
        )

    private fun cursorStyle() =
        session.snapshot().modes.let { Pair(it.cursorShape, it.cursorBlinking) }

    @Test
    fun stylesWithTheSameValuesAreEqual() {
        // Terminal keys its paints and cell size by the style, so that a style made again in each
        // composition must not make them again.
        assertEquals(TerminalStyle(), TerminalStyle())
        assertEquals(TerminalStyle().hashCode(), TerminalStyle().hashCode())
        assertNotEquals(TerminalStyle(), TerminalStyle(fontSize = 16.sp))
        assertEquals(
            TerminalStyle(fontSize = 16.sp, cursorBlinking = true),
            TerminalStyle(cursorBlinking = true).copy(fontSize = 16.sp),
        )
        assertEquals(
            TerminalStyle(padding = PaddingValues(start = 4.dp, top = 2.dp)),
            TerminalStyle().copy(padding = PaddingValues(start = 4.dp, top = 2.dp)),
        )
        assertNotEquals(TerminalStyle(), TerminalStyle(padding = PaddingValues(1.dp)))
    }

    @Test
    fun colorsWithTheSameValuesAreEqualAndKeepTheirPalette() {
        val palette = TerminalColors.Dark.palette.toMutableList()
        val colors = TerminalColors(Color.White, Color.Black, Color.White, palette, Color.Blue)
        assertEquals(
            TerminalColors(Color.White, Color.Black, Color.White, palette.toList(), Color.Blue),
            colors,
        )
        assertNotEquals(TerminalColors.Dark, TerminalColors.Light)
        assertEquals(
            TerminalColors.Dark,
            TerminalColors.Light.copy(
                foreground = TerminalColors.Dark.foreground,
                background = TerminalColors.Dark.background,
                cursor = TerminalColors.Dark.cursor,
            ),
        )
        // The colors are immutable, also when the palette given was not.
        palette[0] = Color.Red
        assertNotEquals(Color.Red, colors.palette[0])
        // The cursor's text color is part of the value too.
        val withCursorText = TerminalColors.Dark.copy(cursorText = Color.Red)
        assertNotEquals(TerminalColors.Dark, withCursorText)
        assertEquals(TerminalColors.Dark.copy(cursorText = Color.Red), withCursorText)
        assertEquals(
            TerminalColors.Dark.copy(cursorText = Color.Red).hashCode(),
            withCursorText.hashCode(),
        )
        assertEquals(Color.Unspecified, TerminalColors.Dark.cursorText)
    }

    @Test
    fun cursorStyleIsTheAppsUntilTheHostSetsOne() {
        var style by
            mutableStateOf(TerminalStyle(cursorShape = CursorShape.Bar, cursorBlinking = true))
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp), style = style) }
        composeRule.waitForIdle()
        assertEquals(Pair(CursorShape.Bar, true), cursorStyle())

        session.feed("\u001b[4 q".toByteArray())
        style = TerminalStyle(cursorShape = CursorShape.Block)
        composeRule.waitForIdle()
        assertEquals(Pair(CursorShape.Underline, false), cursorStyle())

        session.feed("\u001b[0 q".toByteArray())
        composeRule.waitForIdle()
        assertEquals(Pair(CursorShape.Block, false), cursorStyle())
    }

    @Test
    fun colorQueriesReportTheColorsOfTheStyle() {
        val sent = StringBuilder()
        val replying = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
        composeRule.setContent {
            Terminal(
                replying,
                Modifier.size(300.dp, 200.dp),
                style = TerminalStyle(colors = TerminalColors.Light),
            )
        }
        composeRule.waitForIdle()
        replying.feed("\u001b]11;?\u0007\u001b]4;9;?\u0007\u001b]4;196;?\u0007".toByteArray())
        assertEquals(
            "\u001b]11;rgb:ffff/ffff/ffff\u001b\\\u001b]4;9;rgb:ffff/0000/0000\u001b\\" +
                "\u001b]4;196;rgb:ffff/0000/0000\u001b\\",
            sent.toString(),
        )
    }

    /** A font that loads asynchronously, as downloadable fonts do, when the test completes it. */
    private class SlowFont(val loaded: CompletableDeferred<Typeface>) :
        AndroidFont(FontLoadingStrategy.Async, Loader, FontVariation.Settings()) {
        override val weight: FontWeight = FontWeight.Normal
        override val style: FontStyle = FontStyle.Normal

        object Loader : TypefaceLoader {
            override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
                error("loads only asynchronously")

            override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
                (font as SlowFont).loaded.await()
        }
    }

    /** Lets the composition, the font loading, and the debounced resize run. */
    private fun settle() {
        repeat(5) {
            composeRule.mainClock.advanceTimeBy(100)
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        }
    }

    @Test
    fun systemFontsAreUsedByName() {
        // The cell size follows the font, so the columns tell which font is used.
        var style by mutableStateOf(TerminalStyle(font = TerminalFont.System("monospace")))
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp), style = style) }
        settle()
        val monospace = columns.last()
        style = TerminalStyle(font = TerminalFont.System("sans-serif"))
        settle()
        val sansSerif = columns.last()
        assertNotEquals(monospace, sansSerif)
        style = TerminalStyle()
        settle()
        assertEquals(monospace, columns.last())
        // An unknown family falls back to the system's default font.
        style = TerminalStyle(font = TerminalFont.System("no-such-family"))
        settle()
        assertEquals(sansSerif, columns.last())
    }

    @Test
    fun resourceFontsMapToTheirWeightsAndStyles() {
        val family =
            TerminalFont.Resources(1, bold = 2, boldItalic = 4).toFontFamily() as FontListFontFamily
        assertEquals(
            listOf(
                Triple(1, FontWeight.Normal, FontStyle.Normal),
                Triple(2, FontWeight.Bold, FontStyle.Normal),
                Triple(4, FontWeight.Bold, FontStyle.Italic),
            ),
            family.fonts.map { Triple((it as ResourceFont).resId, it.weight, it.style) },
        )
    }

    @Test
    fun typefacesFollowAFontThatLoadsAsynchronously() {
        // Terminal makes its paints and cell size from these typefaces, keyed by them, so it
        // switches to the font once it has loaded. Apps cannot give such a font
        // (docs/decisions/0020), but a font family may still resolve asynchronously.
        val loaded = CompletableDeferred<Typeface>()
        val family = FontFamily(SlowFont(loaded))
        var typefaces: TextTypefaces? = null
        composeRule.setContent {
            typefaces = TextTypefaces.rememberTextTypefaces(family, LocalFontFamilyResolver.current)
        }
        settle()
        assertNotSame(Typeface.MONOSPACE, typefaces!!.regular)
        loaded.complete(Typeface.MONOSPACE)
        settle()
        assertSame(Typeface.MONOSPACE, typefaces.regular)
    }

    /** A family of one font from the test resources, sanitized by vtfx-font's test builder. */
    private fun sanitizedFamily(name: String): SanitizedFamily {
        val bytes = checkNotNull(javaClass.getResourceAsStream("sanitized-$name.ttf")).readBytes()
        return SanitizedFamily(SanitizedFont.fromCanonical(bytes, FontLimits()))
    }

    @Test
    fun sanitizedFontsSetTheCellSize() {
        // The two fonts are the same but for their advance widths: 1000 and 500 units of 1000.
        var style by
            mutableStateOf(
                TerminalStyle(font = TerminalFont.Sanitized(listOf(sanitizedFamily("narrow"))))
            )
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp), style = style) }
        settle()
        val narrow = columns.last()

        style = TerminalStyle(font = TerminalFont.Sanitized(listOf(sanitizedFamily("wide"))))
        settle()

        assertEquals(narrow / 2, columns.last())
    }

    @Test
    fun firstSanitizedFamilySetsTheCellSize() {
        var style by
            mutableStateOf(
                TerminalStyle(font = TerminalFont.Sanitized(listOf(sanitizedFamily("wide"))))
            )
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp), style = style) }
        settle()
        val wide = columns.last()

        style =
            TerminalStyle(
                font =
                    TerminalFont.Sanitized(
                        listOf(sanitizedFamily("wide"), sanitizedFamily("narrow"))
                    )
            )
        settle()

        assertEquals(listOf(wide), columns.distinct())
    }

    @Test
    fun sanitizedFontsWithTheSameFamiliesAreEqual() {
        val first = TerminalFont.Sanitized(listOf(sanitizedFamily("wide")))
        val second = TerminalFont.Sanitized(listOf(sanitizedFamily("wide")))

        assertEquals(first, second)
    }

    @Test
    fun sanitizedFontWithoutFamiliesIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { TerminalFont.Sanitized(emptyList()) }
    }
}
