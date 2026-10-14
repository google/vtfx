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

package com.google.vtfx.gate

import com.google.vtfx.core.CellStyle
import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalColor
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalQuery
import com.google.vtfx.core.UnderlineStyle

/**
 * The colors that the display uses, which replies to color queries report (OSC 10, 11, 12, 4).
 * Called on the thread that handles the query, which may be any thread.
 *
 * @suppress
 */
@InternalVtfxApi
public fun interface ReportedColors {
    /** Returns [target] as 0xRRGGBB, or null to not reply. */
    public fun rgb(target: ColorTarget): Int?
}

/**
 * Builds the replies to queries from the host (docs/decisions/0006 and 0009).
 *
 * Every reply is built from constants and numbers from the terminal state. No text from the host is
 * ever part of a reply, which rules out command injection through replies (requirement 6.1).
 */
internal object Replies {
    /** DA1: a VT220 with ANSI color. */
    private const val PRIMARY_DEVICE_ATTRIBUTES = "\u001b[?62;22c"

    /** DA2: terminal type 0, version 10, no ROM cartridge, as Windows Terminal reports. */
    private const val SECONDARY_DEVICE_ATTRIBUTES = "\u001b[>0;10;1c"

    /** The name and version reported by XTVERSION. */
    const val VERSION: String = "vtfx(0.1)"

    private const val DCS = "\u001bP"
    private const val OSC = "\u001b]"
    private const val ST = "\u001b\\"

    /** Returns the reply to [query], or null if there is none (a color without [colors]). */
    fun encode(query: TerminalQuery, colors: ReportedColors?): ByteArray? {
        val reply =
            when (query) {
                TerminalQuery.PrimaryDeviceAttributes -> PRIMARY_DEVICE_ATTRIBUTES
                TerminalQuery.SecondaryDeviceAttributes -> SECONDARY_DEVICE_ATTRIBUTES
                TerminalQuery.OperatingStatus -> "\u001b[0n"
                is TerminalQuery.CursorPosition -> {
                    checkPosition(query.row)
                    checkPosition(query.column)
                    "\u001b[${if (query.isDecPrivate) "?" else ""}${query.row};${query.column}R"
                }
                is TerminalQuery.ModeStatus -> {
                    require(query.mode in 0..MAX_PARAMETER) { "mode out of range: ${query.mode}" }
                    "\u001b[${if (query.isDecPrivate) "?" else ""}${query.mode};${query.state.code}\$y"
                }
                is TerminalQuery.SgrStatus ->
                    "${DCS}1\$r${sgr(query.style, query.underlineColor)}m$ST"
                is TerminalQuery.ScrollingRegionStatus -> {
                    checkPosition(query.top)
                    checkPosition(query.bottom)
                    "${DCS}1\$r${query.top};${query.bottom}r$ST"
                }
                is TerminalQuery.CursorStyleStatus ->
                    "${DCS}1\$r${cursorStyle(query.shape, query.isBlinking)} q$ST"
                TerminalQuery.UnsupportedStatus -> "${DCS}0\$r$ST"
                TerminalQuery.TerminalVersion -> "$DCS>|$VERSION$ST"
                is TerminalQuery.TextAreaSize -> {
                    checkPosition(query.rows)
                    checkPosition(query.columns)
                    "\u001b[8;${query.rows};${query.columns}t"
                }
                is TerminalQuery.Color -> color(query.target, colors) ?: return null
            }
        return reply.toByteArray(Charsets.US_ASCII)
    }

    private fun color(target: ColorTarget, colors: ReportedColors?): String? {
        val rgb = colors?.rgb(target) ?: return null
        val spec =
            "rgb:%04x/%04x/%04x"
                .format(
                    (rgb shr 16 and 0xFF) * 0x101,
                    (rgb shr 8 and 0xFF) * 0x101,
                    (rgb and 0xFF) * 0x101,
                )
        return when (target) {
            ColorTarget.Foreground -> "${OSC}10;$spec$ST"
            ColorTarget.Background -> "${OSC}11;$spec$ST"
            ColorTarget.Cursor -> "${OSC}12;$spec$ST"
            is ColorTarget.Palette -> {
                require(target.index in 0..255) { "index out of range: ${target.index}" }
                "${OSC}4;${target.index};$spec$ST"
            }
        }
    }

    /** The SGR parameters that recreate [style] and [underlineColor], starting with 0. */
    private fun sgr(style: CellStyle, underlineColor: TerminalColor): String = buildList {
        add("0")
        if (style.isBold) add("1")
        if (style.isFaint) add("2")
        if (style.isItalic) add("3")
        when (style.underline) {
            UnderlineStyle.None -> {}
            UnderlineStyle.Single -> add("4")
            else -> add("4:${style.underline.ordinal}")
        }
        if (style.isBlinking) add("5")
        if (style.isInverse) add("7")
        if (style.isInvisible) add("8")
        if (style.isStrikethrough) add("9")
        if (style.isOverlined) add("53")
        color(style.foreground, 30, 90, 38)?.let { add(it) }
        color(style.background, 40, 100, 48)?.let { add(it) }
        extendedColor(underlineColor, 58)?.let { add(it) }
    }
        .joinToString(";")

    private fun color(color: TerminalColor, base: Int, brightBase: Int, extended: Int): String? =
        when {
            color.isIndexed && color.index < 8 -> "${base + color.index}"
            color.isIndexed && color.index < 16 -> "${brightBase + color.index - 8}"
            else -> extendedColor(color, extended)
        }

    /** [color] in the form that starts with [extended] (38, 48, or 58), or null if default. */
    private fun extendedColor(color: TerminalColor, extended: Int): String? =
        when {
            color.isDefault -> null
            color.isIndexed -> "$extended;5;${color.index}"
            else ->
                "$extended;2;${color.rgb shr 16 and 0xFF};${color.rgb shr 8 and 0xFF};${color.rgb and 0xFF}"
        }

    private fun cursorStyle(shape: CursorShape, blinking: Boolean): Int {
        val steady =
            when (shape) {
                CursorShape.Block -> 2
                CursorShape.Underline -> 4
                CursorShape.Bar -> 6
            }
        return if (blinking) steady - 1 else steady
    }

    private fun checkPosition(value: Int) {
        require(value in 1..TerminalLimits.MAX_DIMENSION) { "position out of range: $value" }
    }

    private const val MAX_PARAMETER = 65535
}
