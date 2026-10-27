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

package com.google.vtfx.core

import com.google.vtfx.core.parser.Params

/**
 * The rendition that SGR sets. The underline color is kept apart from [style], whose 64 bits have
 * no room for it, and is written to the cells' side storage only while the style underlines
 * (docs/decisions/0079).
 */
internal data class Rendition(val style: CellStyle, val underlineColor: TerminalColor) {
    /**
     * The underline color written to cells, as [TerminalColor.bits]: 0 (the default) unless the
     * style underlines, so that a color left set after the underline ends takes no memory. Computed
     * once, since every character printed reads it.
     */
    val cellUnderlineColor: Int =
        if (style.underline == UnderlineStyle.None) 0 else underlineColor.bits

    companion object {
        val Default = Rendition(CellStyle.Default, TerminalColor.Default)
    }
}

/**
 * Applies SGR (Select Graphic Rendition) parameters.
 *
 * Extended colors accept both the ITU T.416 form with ':' (38:2::R:G:B, 38:2:R:G:B, 38:5:N) and the
 * common xterm form with ';' (38;2;R;G;B, 38;5;N), for the foreground (38), the background (48),
 * and the underline (58). Unknown parameters are ignored.
 */
internal object Sgr {
    /** Returns the rendition that [params] make of [rendition]. */
    fun apply(rendition: Rendition, params: Params): Rendition {
        if (params.size == 0) return Rendition.Default
        var result = rendition.style
        var underlineColor = rendition.underlineColor
        var i = 0
        while (i < params.size) {
            val p = params[i]
            // The number of sub-parameters that follow p.
            var subCount = 0
            while (params.isSubParameter(i + 1 + subCount)) subCount++
            when (p) {
                0 -> {
                    result = CellStyle.Default
                    underlineColor = TerminalColor.Default
                }
                1 -> result = result.withFlag(CellStyle.BOLD, true)
                2 -> result = result.withFlag(CellStyle.FAINT, true)
                3 -> result = result.withFlag(CellStyle.ITALIC, true)
                4 ->
                    result =
                        result.withUnderline(underlineStyle(if (subCount > 0) params[i + 1] else 1))
                5,
                6 -> result = result.withFlag(CellStyle.BLINK, true)
                7 -> result = result.withFlag(CellStyle.INVERSE, true)
                8 -> result = result.withFlag(CellStyle.INVISIBLE, true)
                9 -> result = result.withFlag(CellStyle.STRIKETHROUGH, true)
                21 -> result = result.withUnderline(UnderlineStyle.Double)
                22 ->
                    result = result.withFlag(CellStyle.BOLD, false).withFlag(CellStyle.FAINT, false)
                23 -> result = result.withFlag(CellStyle.ITALIC, false)
                24 -> result = result.withUnderline(UnderlineStyle.None)
                25 -> result = result.withFlag(CellStyle.BLINK, false)
                27 -> result = result.withFlag(CellStyle.INVERSE, false)
                28 -> result = result.withFlag(CellStyle.INVISIBLE, false)
                29 -> result = result.withFlag(CellStyle.STRIKETHROUGH, false)
                in 30..37 -> result = result.withForeground(TerminalColor.indexed(p - 30))
                38 -> {
                    val (color, consumed) = extendedColor(params, i, subCount)
                    if (color != null) result = result.withForeground(color)
                    i += consumed
                }
                39 -> result = result.withForeground(TerminalColor.Default)
                in 40..47 -> result = result.withBackground(TerminalColor.indexed(p - 40))
                48 -> {
                    val (color, consumed) = extendedColor(params, i, subCount)
                    if (color != null) result = result.withBackground(color)
                    i += consumed
                }
                49 -> result = result.withBackground(TerminalColor.Default)
                53 -> result = result.withFlag(CellStyle.OVERLINE, true)
                55 -> result = result.withFlag(CellStyle.OVERLINE, false)
                58 -> {
                    val (color, consumed) = extendedColor(params, i, subCount)
                    if (color != null) underlineColor = color
                    i += consumed
                }
                59 -> underlineColor = TerminalColor.Default
                in 90..97 -> result = result.withForeground(TerminalColor.indexed(p - 90 + 8))
                in 100..107 -> result = result.withBackground(TerminalColor.indexed(p - 100 + 8))
            }
            i += 1 + subCount
        }
        return Rendition(result, underlineColor)
    }

    /**
     * The style of SGR 4:[value]. SGR numbers the styles in the order of [UnderlineStyle]; an
     * unknown value underlines once.
     */
    private fun underlineStyle(value: Int): UnderlineStyle =
        UnderlineStyle.entries.getOrElse(value) { UnderlineStyle.Single }

    /**
     * Parses the color after 38, 48, or 58 at [i]. Returns the color, or null if it is malformed,
     * and the number of ';'-separated parameters consumed beyond those counted in [subCount].
     */
    private fun extendedColor(params: Params, i: Int, subCount: Int): Pair<TerminalColor?, Int> {
        if (subCount > 0) {
            val color =
                when (params[i + 1]) {
                    // 38:5:N
                    5 -> if (subCount >= 2) indexed(params[i + 2]) else null
                    // 38:2:CS:R:G:B, with an optional color space id, or 38:2:R:G:B.
                    2 ->
                        when {
                            subCount >= 5 -> rgb(params[i + 3], params[i + 4], params[i + 5])
                            subCount == 4 -> rgb(params[i + 2], params[i + 3], params[i + 4])
                            else -> null
                        }
                    else -> null
                }
            return Pair(color, 0)
        }
        return when (params[i + 1]) {
            5 ->
                if (i + 2 < params.size) Pair(indexed(params[i + 2]), 2)
                else Pair(null, params.size - i - 1)
            2 ->
                if (i + 4 < params.size) {
                    Pair(rgb(params[i + 2], params[i + 3], params[i + 4]), 4)
                } else {
                    Pair(null, params.size - i - 1)
                }
            else -> Pair(null, if (i + 1 < params.size) 1 else 0)
        }
    }

    private fun indexed(value: Int): TerminalColor? =
        if (value in 0..255) TerminalColor.indexed(value) else null

    private fun rgb(red: Int, green: Int, blue: Int): TerminalColor? =
        if (red in 0..255 && green in 0..255 && blue in 0..255) TerminalColor.rgb(red, green, blue)
        else null
}
