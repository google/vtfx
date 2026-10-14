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

/**
 * A 94-character set that can be designated to G0 to G3 (ctlseqs, "Controls beginning with ESC").
 */
internal enum class CharacterSet {
    Ascii,

    /** DEC Special Graphics: line drawing (ESC ( 0). */
    DecSpecialGraphics,

    /** United Kingdom: '#' is '£' (ESC ( A). */
    UnitedKingdom;

    /** Maps a printable ASCII [codePoint] through this set. Other code points are unchanged. */
    fun map(codePoint: Int): Int =
        when (this) {
            Ascii -> codePoint
            UnitedKingdom -> if (codePoint == '#'.code) 0x00A3 else codePoint
            DecSpecialGraphics ->
                if (codePoint in 0x5F..0x7E) DEC_SPECIAL_GRAPHICS[codePoint - 0x5F].code
                else codePoint
        }

    companion object {
        /** Returns the set for the final byte of a designation, or null if not supported. */
        fun forFinal(final: Int): CharacterSet? =
            when (final.toChar()) {
                'B' -> Ascii
                '0' -> DecSpecialGraphics
                'A' -> UnitedKingdom
                else -> null
            }

        /** 0x5F to 0x7E in DEC Special Graphics, as xterm maps them to Unicode. */
        private const val DEC_SPECIAL_GRAPHICS = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼" + "⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}

/** The designated character sets and which of G0 and G1 is invoked into GL (SI and SO). */
internal data class CharsetState(
    val g: List<CharacterSet> = List(4) { CharacterSet.Ascii },
    val shiftOut: Boolean = false,
) {
    fun map(codePoint: Int): Int = g[if (shiftOut) 1 else 0].map(codePoint)

    /** Whether the set invoked into GL is ASCII, so that printing needs no mapping. */
    val isAscii: Boolean = g[if (shiftOut) 1 else 0] == CharacterSet.Ascii

    fun designate(index: Int, set: CharacterSet): CharsetState =
        copy(g = g.toMutableList().also { it[index] = set })
}
