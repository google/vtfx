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

package com.google.vtfx.core.graphics

import com.google.vtfx.core.CellStyle
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Line
import com.google.vtfx.core.TerminalColor

/**
 * Reads the Unicode placeholders of the kitty graphics protocol (docs/decisions/0038): cells of
 * U+10EEEE whose foreground color gives an image id, whose underline color gives a placement id,
 * and whose diacritics give the row and column of the image to show there, and the high byte of the
 * image id. Missing diacritics are taken from the placeholder to the left, by the rules of the
 * protocol.
 */
internal object Placeholders {
    /** The placeholder character. */
    const val CODE_POINT: Int = 0x10EEEE

    /** A placeholder cell: which part of which image it shows. */
    class Cell(
        val column: Int,
        val imageId: Long,
        val placementId: Long,
        val row: Int,
        val imageColumn: Int,
        val highByte: Int,
        /** The colors, to compare with the next cell. */
        val foreground: Long,
        val underline: Int,
    )

    /** Whether [line] has a placeholder. */
    fun hasPlaceholders(line: Line): Boolean {
        for (code in line.codes) if (code == CODE_POINT) return true
        return false
    }

    /** Returns the placeholder cells of [line] that name an image, left to right. */
    fun resolve(line: Line): List<Cell> {
        if (!hasPlaceholders(line)) return emptyList()
        val cells = ArrayList<Cell>()
        var previous: Cell? = null
        for (column in 0 until line.columns) {
            if (line.codes[column] != CODE_POINT) {
                previous = null
                continue
            }
            val style = CellStyle(line.styles[column])
            val foreground = style.foreground
            val underlineBits = line.underlineColorAt(column)
            val text = line.textAt(column) ?: ""
            // The diacritics after the placeholder: row, column, and high byte, -1 if absent.
            val values = IntArray(3) { -1 }
            var count = 0
            var i = Character.charCount(CODE_POINT)
            while (i < text.length && count < 3) {
                val codePoint = text.codePointAt(i)
                values[count++] =
                    PlaceholderDiacritics.CODE_POINTS.binarySearch(codePoint).coerceAtLeast(-1)
                i += Character.charCount(codePoint)
            }
            val left = previous?.takeIf {
                it.foreground == foreground.bitsAsLong() &&
                    it.underline == underlineBits &&
                    it.column == column - 1
            }
            var row = values[0]
            var imageColumn = values[1]
            var highByte = values[2]
            when {
                count == 0 && left != null -> {
                    row = left.row
                    imageColumn = left.imageColumn + 1
                    highByte = left.highByte
                }
                count == 1 && left != null && left.row == row -> {
                    imageColumn = left.imageColumn + 1
                    highByte = left.highByte
                }
                count == 2 &&
                    left != null &&
                    left.row == row &&
                    left.imageColumn == imageColumn - 1 -> highByte = left.highByte
            }
            val low = imageIdBits(foreground)
            if (low < 0) {
                previous = null
                continue
            }
            val cell =
                Cell(
                    column = column,
                    imageId = (maxOf(highByte, 0).toLong() shl 24) or low,
                    placementId = maxOf(0L, imageIdBits(TerminalColor(underlineBits))),
                    row = maxOf(row, 0),
                    imageColumn = maxOf(imageColumn, 0),
                    highByte = maxOf(highByte, 0),
                    foreground = foreground.bitsAsLong(),
                    underline = underlineBits,
                )
            cells += cell
            previous = cell
        }
        return cells
    }

    /** The id that [color] gives: 8 bits of an indexed color, 24 of a direct one, or -1. */
    private fun imageIdBits(color: TerminalColor): Long =
        when {
            color.isDefault -> -1L
            color.isIndexed -> color.index.toLong()
            else -> color.rgb.toLong() and 0xFFFFFF
        }

    private fun TerminalColor.bitsAsLong(): Long = bits.toLong()
}
