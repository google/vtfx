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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.gate.ReportedColors

/**
 * The colors of a terminal. [palette][TerminalColors.palette] has the 16 ANSI colors; colors 16 to
 * 255 are the xterm color cube and gray ramp. Immutable, and equal to colors with the same values.
 */
@Immutable
public class TerminalColors(
    /** The default color of text. */
    public val foreground: Color,
    /** The default color behind text. */
    public val background: Color,
    /**
     * The color of the cursor. Text under a block cursor is drawn in
     * [cursorText][TerminalColors.cursorText].
     */
    public val cursor: Color,
    palette: List<Color>,
    /** Drawn over selected text. Translucent, so that the text shows through. */
    public val selection: Color,
    /**
     * Drawn over the matches of a search ([TerminalState.search]), under the selection.
     * Translucent, so that the text shows through.
     */
    public val searchMatch: Color,
    /**
     * Drawn over the current match of a search instead of
     * [searchMatch][TerminalColors.searchMatch]. Translucent, so that the text shows through.
     */
    public val searchCurrentMatch: Color,
    /**
     * The color of the text, and its underline, under a focused block cursor. [Color.Unspecified],
     * the default, draws it in [background][TerminalColors.background], so that the cell looks
     * inverted. In reverse video (DECSCNM), the cursor and its text take the default colors of the
     * screen instead of [cursor][TerminalColors.cursor] and this.
     */
    public val cursorText: Color = Color.Unspecified,
) {
    /** The 16 ANSI colors, 0 to 15. A copy of the list given. */
    public val palette: List<Color> = palette.toList()

    init {
        require(this.palette.size == ANSI_COLORS) { "palette must have $ANSI_COLORS colors" }
    }

    /** These colors as the replies to the host's color queries report them. */
    internal val reportedColors: ReportedColors = ReportedColors { target ->
        val color =
            when (target) {
                ColorTarget.Foreground -> foreground
                ColorTarget.Background -> background
                ColorTarget.Cursor -> cursor
                is ColorTarget.Palette -> indexed(target.index)
            }
        color.toArgb() and 0xFFFFFF
    }

    /** Returns these colors with the given ones replaced. */
    public fun copy(
        foreground: Color = this.foreground,
        background: Color = this.background,
        cursor: Color = this.cursor,
        palette: List<Color> = this.palette,
        selection: Color = this.selection,
        searchMatch: Color = this.searchMatch,
        searchCurrentMatch: Color = this.searchCurrentMatch,
        cursorText: Color = this.cursorText,
    ): TerminalColors =
        TerminalColors(
            foreground,
            background,
            cursor,
            palette,
            selection,
            searchMatch,
            searchCurrentMatch,
            cursorText,
        )

    override fun equals(other: Any?): Boolean =
        other is TerminalColors &&
            other.foreground == foreground &&
            other.background == background &&
            other.cursor == cursor &&
            other.palette == palette &&
            other.selection == selection &&
            other.searchMatch == searchMatch &&
            other.searchCurrentMatch == searchCurrentMatch &&
            other.cursorText == cursorText

    override fun hashCode(): Int =
        listOf(
                foreground,
                background,
                cursor,
                palette,
                selection,
                searchMatch,
                searchCurrentMatch,
                cursorText,
            )
            .hashCode()

    override fun toString(): String =
        "TerminalColors(foreground=$foreground, background=$background, cursor=$cursor, " +
            "palette=$palette, selection=$selection, searchMatch=$searchMatch, " +
            "searchCurrentMatch=$searchCurrentMatch, cursorText=$cursorText)"

    /** Returns the color of [index] of the 256 colors: the palette, then xterm's colors. */
    internal fun indexed(index: Int): Color =
        if (index < ANSI_COLORS) palette[index] else XTERM_COLORS[index - ANSI_COLORS]

    public companion object {
        private const val ANSI_COLORS = 16
        private const val INDEXED_COLORS = 256

        /** xterm's colors 16 to 255, which do not depend on the palette. */
        private val XTERM_COLORS: List<Color> =
            List(INDEXED_COLORS - ANSI_COLORS) { xtermColor(it + ANSI_COLORS) }

        /**
         * Translucent blue, which shows on both dark and light backgrounds. Declared before [Dark]
         * and [Light], which read it when the companion is initialized.
         */
        private val SELECTION = Color(0x664D90FE)

        /** Translucent yellow and orange, which show on both dark and light backgrounds. */
        private val SEARCH_MATCH = Color(0x66FFD600)
        private val SEARCH_CURRENT_MATCH = Color(0xAAFF8F00)

        /** Light text on a dark background, with xterm's default palette. */
        public val Dark: TerminalColors =
            TerminalColors(
                foreground = Color(0xFFE5E5E5),
                background = Color(0xFF000000),
                cursor = Color(0xFFE5E5E5),
                palette = XTERM_PALETTE,
                selection = SELECTION,
                searchMatch = SEARCH_MATCH,
                searchCurrentMatch = SEARCH_CURRENT_MATCH,
            )

        /** Dark text on a light background, with xterm's default palette. */
        public val Light: TerminalColors =
            TerminalColors(
                foreground = Color(0xFF000000),
                background = Color(0xFFFFFFFF),
                cursor = Color(0xFF000000),
                palette = XTERM_PALETTE,
                selection = SELECTION,
                searchMatch = SEARCH_MATCH,
                searchCurrentMatch = SEARCH_CURRENT_MATCH,
            )

        private val XTERM_PALETTE: List<Color>
            get() =
                listOf(
                        0x000000,
                        0xCD0000,
                        0x00CD00,
                        0xCDCD00,
                        0x0000EE,
                        0xCD00CD,
                        0x00CDCD,
                        0xE5E5E5,
                        0x7F7F7F,
                        0xFF0000,
                        0x00FF00,
                        0xFFFF00,
                        0x5C5CFF,
                        0xFF00FF,
                        0x00FFFF,
                        0xFFFFFF,
                    )
                    .map { Color(0xFF000000.toInt() or it) }

        /** The color cube (16-231) and the gray ramp (232-255) of xterm's 256 colors. */
        private fun xtermColor(index: Int): Color {
            if (index >= 232) {
                val level = 8 + (index - 232) * 10
                return Color(level, level, level)
            }
            val i = index - 16
            fun component(value: Int) = if (value == 0) 0 else 55 + value * 40
            return Color(component(i / 36), component(i / 6 % 6), component(i % 6))
        }
    }
}

/** The appearance of a terminal. Immutable, and equal to a style with the same values. */
@Immutable
public class TerminalStyle(
    /** The colors of text, background, and cursor. */
    public val colors: TerminalColors = TerminalColors.Dark,
    /** The font, which should be monospaced: the cell size is measured from it. */
    public val font: TerminalFont = TerminalFont.System(),
    /** The font size, which sets the cell size and thus the number of columns and rows. */
    public val fontSize: TextUnit = 14.sp,
    /**
     * The locale that selects the glyphs of Han characters, which differ between Japanese, Chinese,
     * and Korean, or null or an empty list for the system's locale. Set it to `LocaleList("ja")` to
     * show Japanese glyphs on a device in another language.
     */
    public val locale: LocaleList? = null,
    /**
     * The shape of the cursor until the host sets one with DECSCUSR. The host's choice wins, and
     * DECSCUSR 0 or a reset returns to this.
     */
    public val cursorShape: CursorShape = CursorShape.Block,
    /** Whether the cursor blinks until the host sets the cursor style (DECSCUSR, mode 12). */
    public val cursorBlinking: Boolean = false,
    /**
     * The space between the edges of the terminal and its grid, drawn in the grid's default
     * background color. Start and end follow the layout direction. The columns and rows fit in the
     * space inside it.
     */
    public val padding: PaddingValues = PaddingValues(0.dp),
) {
    /** Returns this style with the given values replaced. */
    public fun copy(
        colors: TerminalColors = this.colors,
        font: TerminalFont = this.font,
        fontSize: TextUnit = this.fontSize,
        locale: LocaleList? = this.locale,
        cursorShape: CursorShape = this.cursorShape,
        cursorBlinking: Boolean = this.cursorBlinking,
        padding: PaddingValues = this.padding,
    ): TerminalStyle =
        TerminalStyle(colors, font, fontSize, locale, cursorShape, cursorBlinking, padding)

    override fun equals(other: Any?): Boolean =
        other is TerminalStyle &&
            other.colors == colors &&
            other.font == font &&
            other.fontSize == fontSize &&
            other.locale == locale &&
            other.cursorShape == cursorShape &&
            other.cursorBlinking == cursorBlinking &&
            other.padding == padding

    override fun hashCode(): Int =
        listOf(colors, font, fontSize, locale, cursorShape, cursorBlinking, padding).hashCode()

    override fun toString(): String =
        "TerminalStyle(colors=$colors, font=$font, fontSize=$fontSize, locale=$locale, " +
            "cursorShape=$cursorShape, cursorBlinking=$cursorBlinking, padding=$padding)"
}
