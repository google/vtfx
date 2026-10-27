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

import androidx.compose.ui.graphics.Color
import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.HostColors
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalColor

/**
 * The colors that a display draws with: those of the [style], with the colors that the host set
 * ([host]) in their place (docs/decisions/0096). The selection, search matches, and the text under
 * the cursor keep the style's colors, which the host cannot set.
 */
internal class DisplayColors(private val style: TerminalColors, val host: HostColors) {
    /** The default color of text. */
    val foreground: Color = color(ColorTarget.Foreground, style.foreground)

    /** The default color behind text. */
    val background: Color = color(ColorTarget.Background, style.background)

    /** The color of the cursor. */
    val cursor: Color = color(ColorTarget.Cursor, style.cursor)

    val cursorText: Color
        get() = style.cursorText

    val selection: Color
        get() = style.selection

    val searchMatch: Color
        get() = style.searchMatch

    val searchCurrentMatch: Color
        get() = style.searchCurrentMatch

    /**
     * The 256 indexed colors, made once, so that resolving the color of a cell allocates nothing.
     */
    private val indexed: List<Color> =
        List(INDEXED_COLORS) { color(ColorTarget.Palette(it), style.indexed(it)) }

    /** Resolves [color] to a display color, using [default] for the default color. */
    fun resolve(color: TerminalColor, default: Color): Color =
        when {
            color.isDefault -> default
            color.isIndexed -> indexed[color.index]
            else -> opaque(color.rgb)
        }

    private fun color(target: ColorTarget, styleColor: Color): Color {
        val rgb = host.rgb(target)
        return if (rgb != null) opaque(rgb) else styleColor
    }

    private companion object {
        const val INDEXED_COLORS = 256

        fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)
    }
}
