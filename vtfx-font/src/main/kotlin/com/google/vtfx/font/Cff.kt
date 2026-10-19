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

package com.google.vtfx.font

import kotlin.math.abs

/**
 * An INDEX of a CFF table (Adobe Technical Note #5176, section 5): [count] items of data, located
 * by offsets.
 *
 * Only the first and the last offset are checked here; each item's offsets are checked when the
 * item is read. So an INDEX costs nothing until it is used, however many Font DICTs refer to it.
 */
internal class CffIndex(table: Reader, at: Int) {
    val count: Int = table.u16(at)

    /** The offset in the table of the first byte after the INDEX. */
    val end: Int

    private val offsets: Reader?
    private val offSize: Int
    private val data: Reader?
    private val dataLength: Int

    init {
        if (count == 0) {
            end = at + 2
            offsets = null
            offSize = 0
            data = null
            dataLength = 0
        } else {
            offSize = table.u8(at + 2)
            if (offSize !in 1..4) malformed("INDEX offset size $offSize")
            offsets = table.sub(at + 3, (count + 1) * offSize)
            if (offset(0) != 1) malformed("INDEX not starting at offset 1")
            dataLength = offset(count) - 1
            // Offsets count from the byte before the data.
            val dataStart = at + 3 + (count + 1) * offSize
            data = table.sub(dataStart, dataLength)
            end = dataStart + dataLength
        }
    }

    /** The data of item [index], in `0 until count`. */
    fun item(index: Int): Reader {
        check(index in 0 until count) { "item $index of $count" }
        val start = offset(index)
        val next = offset(index + 1)
        if (start < 1 || next < start || next - 1 > dataLength)
            malformed("INDEX offsets not in order")
        return data!!.sub(start - 1, next - start)
    }

    private fun offset(index: Int): Int {
        val at = index * offSize
        val value =
            when (offSize) {
                1 -> offsets!!.u8(at).toLong()
                2 -> offsets!!.u16(at).toLong()
                3 -> offsets!!.u24(at).toLong()
                else -> offsets!!.u32(at)
            }
        if (value > Int.MAX_VALUE) malformed("INDEX offset $value out of range")
        return value.toInt()
    }
}

/**
 * The operands of the operators of a DICT of a CFF table (section 4 of the specification). An
 * operator given twice keeps its last operands.
 */
internal class CffDict(data: Reader) {
    private val entries = HashMap<Int, DoubleArray>()

    init {
        val operands = DoubleArray(MAX_OPERANDS)
        var count = 0
        var at = 0
        while (at < data.length) {
            val b0 = data.u8(at++)
            if (b0 <= 21) {
                val operator = if (b0 == ESCAPE) ESCAPED or data.u8(at++) else b0
                entries[operator] = operands.copyOf(count)
                count = 0
                continue
            }
            if (count == MAX_OPERANDS) malformed("DICT of more than $MAX_OPERANDS operands")
            operands[count++] =
                when (b0) {
                    in 32..246 -> (b0 - 139).toDouble()
                    in 247..250 -> ((b0 - 247) * 256 + data.u8(at++) + 108).toDouble()
                    in 251..254 -> (-(b0 - 251) * 256 - data.u8(at++) - 108).toDouble()
                    28 -> data.s16(at).toDouble().also { at += 2 }
                    29 -> data.s32(at).toDouble().also { at += 4 }
                    30 -> {
                        val (value, next) = real(data, at)
                        at = next
                        value
                    }
                    else -> malformed("DICT byte $b0")
                }
        }
        if (count != 0) malformed("DICT operands without an operator")
    }

    fun has(operator: Int): Boolean = operator in entries

    /** The operands of [operator], which must be [count] if it is present. */
    fun operands(operator: Int, count: Int): DoubleArray? {
        val operands = entries[operator] ?: return null
        if (operands.size != count)
            malformed("DICT operator $operator of ${operands.size} operands")
        return operands
    }

    /** The one integer operand of [operator], if present. */
    fun int(operator: Int): Int? = operands(operator, 1)?.let { toInt(it[0]) }

    companion object {
        const val ESCAPE = 12
        const val ESCAPED = ESCAPE shl 8

        const val CHAR_STRINGS = 17
        const val PRIVATE = 18
        const val SUBRS = 19
        const val CHARSTRING_TYPE = ESCAPED or 6
        const val FONT_MATRIX = ESCAPED or 7
        const val ROS = ESCAPED or 30
        const val FD_ARRAY = ESCAPED or 36
        const val FD_SELECT = ESCAPED or 37

        /** The most operands of one operator, as the specification limits them (appendix B). */
        private const val MAX_OPERANDS = 48

        /** The most nibbles of a real number; real fonts write a dozen at most. */
        private const val MAX_REAL_NIBBLES = 64

        fun toInt(value: Double): Int {
            if (value != Math.floor(value) || abs(value) > Int.MAX_VALUE) {
                malformed("DICT operand $value is not an integer")
            }
            return value.toInt()
        }

        /** The real number whose nibbles start at [start], and the offset after it. */
        private fun real(data: Reader, start: Int): Pair<Double, Int> {
            val text = StringBuilder()
            var at = start
            var nibbles = 0
            while (true) {
                val byte = data.u8(at++)
                for (nibble in intArrayOf(byte ushr 4, byte and 0x0F)) {
                    if (nibble == 0xF) {
                        val value = text.toString().toDoubleOrNull() ?: malformed("real $text")
                        return value to at
                    }
                    if (++nibbles > MAX_REAL_NIBBLES) malformed("real of too many digits")
                    when (nibble) {
                        in 0..9 -> text.append('0' + nibble)
                        0xA -> text.append('.')
                        0xB -> text.append('E')
                        0xC -> text.append("E-")
                        0xE -> text.append('-')
                        else -> malformed("real nibble $nibble")
                    }
                }
            }
        }
    }
}

/**
 * The parts of a `CFF ` table that drawing the glyphs needs: the charstrings and the subroutines,
 * for a name-keyed or a CID-keyed font.
 */
internal class CffFont
private constructor(
    val charStrings: CffIndex,
    val globalSubrs: CffIndex,
    /** The local subroutines of each Font DICT; one for a name-keyed font. */
    private val localSubrs: List<CffIndex?>,
    /** The Font DICT of each glyph, or null for a name-keyed font. */
    private val fdSelect: ByteArray?,
) {
    /** The local subroutines of glyph [glyphId], or null if it has none. */
    fun localSubrs(glyphId: Int): CffIndex? =
        localSubrs[if (fdSelect == null) 0 else fdSelect[glyphId].toInt() and 0xFF]

    companion object {
        /** The most Font DICTs that FDSelect, of one byte per index, can choose. */
        private const val MAX_FONT_DICTS = 256

        /** The largest Private DICT; real ones hold a few hundred bytes of hinting values. */
        private const val MAX_PRIVATE_DICT_BYTES = 65536

        fun read(table: Reader, numGlyphs: Int, unitsPerEm: Int): CffFont {
            val major = table.u8(0)
            if (major != 1) unsupported("CFF version $major")
            val headerSize = table.u8(2)
            if (headerSize < 4) malformed("CFF header of $headerSize bytes")
            val names = CffIndex(table, headerSize)
            if (names.count != 1) unsupported("CFF of ${names.count} fonts")
            val topDicts = CffIndex(table, names.end)
            if (topDicts.count != 1) malformed("CFF of ${topDicts.count} Top DICTs")
            val strings = CffIndex(table, topDicts.end)
            val globalSubrs = CffIndex(table, strings.end)
            val top = CffDict(topDicts.item(0))

            val charstringType = top.int(CffDict.CHARSTRING_TYPE) ?: 2
            if (charstringType != 2) unsupported("charstring type $charstringType")
            checkFontMatrix(top, unitsPerEm)
            val charStringsOffset = top.int(CffDict.CHAR_STRINGS) ?: malformed("no CharStrings")
            val charStrings = CffIndex(table, charStringsOffset)
            if (charStrings.count != numGlyphs) {
                malformed("${charStrings.count} charstrings for $numGlyphs glyphs")
            }

            if (!top.has(CffDict.ROS)) {
                return CffFont(charStrings, globalSubrs, listOf(localSubrs(table, top)), null)
            }
            val fdArrayOffset = top.int(CffDict.FD_ARRAY) ?: malformed("CID font without FDArray")
            val fdSelectOffset =
                top.int(CffDict.FD_SELECT) ?: malformed("CID font without FDSelect")
            val fontDicts = CffIndex(table, fdArrayOffset)
            if (fontDicts.count == 0) malformed("FDArray without Font DICTs")
            if (fontDicts.count > MAX_FONT_DICTS) unsupported("${fontDicts.count} Font DICTs")
            val localSubrs =
                List(fontDicts.count) {
                    val fontDict = CffDict(fontDicts.item(it))
                    checkFontMatrix(fontDict, unitsPerEm)
                    localSubrs(table, fontDict)
                }
            val fdSelect = fdSelect(table.from(fdSelectOffset), numGlyphs, fontDicts.count)
            return CffFont(charStrings, globalSubrs, localSubrs, fdSelect)
        }

        /** The local subroutines of the Private DICT that [dict] points to. */
        private fun localSubrs(table: Reader, dict: CffDict): CffIndex? {
            val private = dict.operands(CffDict.PRIVATE, 2) ?: malformed("no Private DICT")
            val size = CffDict.toInt(private[0])
            val offset = CffDict.toInt(private[1])
            // Each Font DICT may point to the same large range, so the size is bounded to bound
            // the time of reading them all.
            if (size > MAX_PRIVATE_DICT_BYTES) unsupported("Private DICT of $size bytes")
            val subrs = CffDict(table.sub(offset, size)).int(CffDict.SUBRS) ?: return null
            // The offset of Subrs counts from the start of the Private DICT.
            if (subrs < 0 || subrs > table.length - offset) malformed("Subrs out of range")
            return CffIndex(table, offset + subrs)
        }

        /** The Font DICT of each glyph, from FDSelect format 0 or 3. */
        private fun fdSelect(data: Reader, numGlyphs: Int, fontDicts: Int): ByteArray {
            val fds = ByteArray(numGlyphs)
            when (val format = data.u8(0)) {
                0 -> {
                    for (glyph in 0 until numGlyphs) {
                        val fd = data.u8(1 + glyph)
                        if (fd >= fontDicts) malformed("Font DICT $fd of $fontDicts")
                        fds[glyph] = fd.toByte()
                    }
                }
                3 -> {
                    val ranges = data.u16(1)
                    if (ranges == 0) malformed("FDSelect without ranges")
                    if (data.u16(3) != 0) malformed("FDSelect not starting at glyph 0")
                    for (i in 0 until ranges) {
                        val first = data.u16(3 + i * 3)
                        val fd = data.u8(5 + i * 3)
                        // The first glyph of the next range, or the sentinel after the last one.
                        val next = data.u16(6 + i * 3)
                        if (next <= first) malformed("FDSelect ranges not in order")
                        if (fd >= fontDicts) malformed("Font DICT $fd of $fontDicts")
                        if (i == ranges - 1 && next != numGlyphs) {
                            malformed("FDSelect sentinel $next for $numGlyphs glyphs")
                        }
                        if (next > numGlyphs) malformed("FDSelect beyond the glyphs")
                        fds.fill(fd.toByte(), first, next)
                    }
                }
                else -> malformed("FDSelect format $format")
            }
            return fds
        }

        /**
         * Rejects a FontMatrix that does not map charstring units to font units one to one. The
         * charstrings are taken in font units of `head`, so only the matrices that real fonts use
         * are accepted: a scale of 1/unitsPerEm (the default for 1000 units), or the identity,
         * which CID-keyed fonts may give in either the Top DICT or the Font DICTs, the other one
         * holding the scale. Skewed, offset, or otherwise scaled outlines are not converted.
         */
        private fun checkFontMatrix(dict: CffDict, unitsPerEm: Int) {
            val matrix = dict.operands(CffDict.FONT_MATRIX, 6) ?: return
            val scale = matrix[0]
            val ok =
                matrix[1] == 0.0 &&
                    matrix[2] == 0.0 &&
                    matrix[3] == scale &&
                    matrix[4] == 0.0 &&
                    matrix[5] == 0.0 &&
                    (abs(scale * unitsPerEm - 1) < MATRIX_TOLERANCE ||
                        abs(scale - 1) < MATRIX_TOLERANCE)
            if (!ok) unsupported("FontMatrix ${matrix.joinToString(" ")}")
        }

        /** How far a FontMatrix's scale may be from the expected one, relatively. */
        private const val MATRIX_TOLERANCE = 1e-4
    }
}
