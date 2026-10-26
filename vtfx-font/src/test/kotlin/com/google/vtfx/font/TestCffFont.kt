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

import kotlin.math.roundToInt

/**
 * Assembles a Type 2 charstring from text: numbers, operator names, and raw bytes as `#XX` (for
 * hint masks and reserved operators), separated by spaces. For example, `"100 0 rmoveto 300 0
 * rlineto endchar"`. Integers take the shortest encoding; other numbers take the 16.16 fixed-point
 * one.
 */
fun charString(text: String): ByteArray {
    val out = TestBytes()
    for (token in text.split(' ').filter { it.isNotEmpty() }) {
        val operator = OPERATORS[token]
        val number = token.toDoubleOrNull()
        when {
            operator != null -> operator.forEach { out.u8(it) }
            token.startsWith("#") -> out.u8(token.substring(1).toInt(16))
            number == null -> error("unknown token $token")
            number != Math.floor(number) -> {
                out.u8(255)
                out.u32((number * 65536).roundToInt())
            }
            else -> {
                val value = number.toInt()
                when (value) {
                    in -107..107 -> out.u8(value + 139)
                    in 108..1131 -> out.u8((value - 108) / 256 + 247, (value - 108) % 256)
                    in -1131..-108 -> out.u8((-value - 108) / 256 + 251, (-value - 108) % 256)
                    else -> {
                        out.u8(28)
                        out.u16(value)
                    }
                }
            }
        }
    }
    return out.toByteArray()
}

private val OPERATORS: Map<String, List<Int>> =
    mapOf(
        "hstem" to listOf(1),
        "vstem" to listOf(3),
        "vmoveto" to listOf(4),
        "rlineto" to listOf(5),
        "hlineto" to listOf(6),
        "vlineto" to listOf(7),
        "rrcurveto" to listOf(8),
        "callsubr" to listOf(10),
        "return" to listOf(11),
        "endchar" to listOf(14),
        "hstemhm" to listOf(18),
        "hintmask" to listOf(19),
        "cntrmask" to listOf(20),
        "rmoveto" to listOf(21),
        "hmoveto" to listOf(22),
        "vstemhm" to listOf(23),
        "rcurveline" to listOf(24),
        "rlinecurve" to listOf(25),
        "vvcurveto" to listOf(26),
        "hhcurveto" to listOf(27),
        "callgsubr" to listOf(29),
        "vhcurveto" to listOf(30),
        "hvcurveto" to listOf(31),
        "dotsection" to listOf(12, 0),
        "add" to listOf(12, 10),
        "dup" to listOf(12, 27),
        "hflex" to listOf(12, 34),
        "flex" to listOf(12, 35),
        "hflex1" to listOf(12, 36),
        "flex1" to listOf(12, 37),
    )

/**
 * The CID-keyed parts of a test CFF font: the local subroutines of each Font DICT, the Font DICT of
 * each glyph as ranges of FDSelect format 3, each a first glyph and a Font DICT, and the FontMatrix
 * of each Font DICT, null for none.
 */
class TestCid(
    val fontDictSubrs: List<List<String>>,
    val fdSelectRanges: List<Pair<Int, Int>>,
    val fdSelectFormat: Int = 3,
    val fontMatrices: List<List<String>?> = fontDictSubrs.map { null },
) {
    init {
        require(fontMatrices.size == fontDictSubrs.size)
    }
}

/**
 * Builds OpenType fonts with CFF outlines for tests, from charstrings written as text (see
 * [charString]), with the tables that [FaceParts] reads. Each property can be changed to make the
 * font of a test.
 */
class TestCffFont(
    var glyphs: List<String> =
        listOf(
            "endchar",
            "100 0 rmoveto 300 0 rlineto 0 300 rlineto -300 0 rlineto endchar",
        ),
    var characters: Map<Int, Int> = mapOf('A'.code to 1),
    /** The names of the Name INDEX, one for each font of the table. */
    var fontNames: List<String> = listOf("Test"),
    var unitsPerEm: Int = 1000,
    var globalSubrs: List<String> = emptyList(),
    /** The local subroutines of a name-keyed font. */
    var localSubrs: List<String> = emptyList(),
    /** Makes the font CID-keyed, with an FDArray and FDSelect. */
    var cid: TestCid? = null,
    var fontMatrix: List<String>? = null,
    var charstringType: Int? = null,
    /** `maxp.numGlyphs`, if not the number of [glyphs]. */
    var numGlyphs: Int? = null,
    /** The size of the offsets of every INDEX. */
    var offSize: Int = 4,
) {
    fun build(): ByteArray = TestSfnt.font(tables(), Tag.OPEN_TYPE_CFF)

    /** The tables of the font, with [cff] as its `CFF ` table. */
    fun tables(cff: ByteArray = cffTable()): Map<String, ByteArray> {
        val glyphCount = numGlyphs ?: glyphs.size
        val trueType =
            TestTrueTypeFont(
                glyphs = List(glyphs.size) { TestGlyph.Empty() },
                characters = characters,
                unitsPerEm = unitsPerEm,
            )
        return trueType.tables() - "glyf" - "loca" +
            ("maxp" to
                bytes {
                    u32(0x00005000)
                    u16(glyphCount)
                }) +
            ("CFF " to cff)
    }

    /**
     * The `CFF ` table: the header, the Name, Top DICT, String, and Global Subr INDEXes, the
     * CharStrings INDEX, then the Private DICTs with their Subrs, and for a CID-keyed font the
     * FDArray and FDSelect.
     */
    fun cffTable(): ByteArray {
        // The Top DICT writes offsets in five bytes, so its size does not depend on them.
        val placeholder = topDict(0, 0, 0, 0, 0)
        val head = TestBytes()
        head.u8(1, 0, 4, 4)
        head.bytes(index(fontNames.map { it.toByteArray() }))
        head.bytes(index(listOf(placeholder)))
        val strings = index(listOf("Adobe".toByteArray(), "Identity".toByteArray()))
        val gsubrs = index(globalSubrs.map { charString(it) })
        val charStrings = index(glyphs.map { charString(it) })
        val charStringsOffset = head.size + strings.size + gsubrs.size

        val tail = TestBytes()
        var offset = charStringsOffset + charStrings.size
        fun privateDict(subrs: List<String>): Pair<Int, Int> {
            // A Private DICT of only Subrs, in five bytes, with the INDEX right after it.
            val dict = bytes {
                u8(29)
                u32(6)
                u8(19)
            }
            val start = offset
            tail.bytes(dict)
            tail.bytes(index(subrs.map { charString(it) }))
            offset = head.size + strings.size + gsubrs.size + charStrings.size + tail.size
            return dict.size to start
        }
        val cid = cid
        val top: ByteArray
        if (cid == null) {
            val (size, privateOffset) = privateDict(localSubrs)
            top = topDict(charStringsOffset, size, privateOffset, 0, 0)
        } else {
            val fontDicts =
                cid.fontDictSubrs.zip(cid.fontMatrices) { subrs, matrix ->
                    val (size, privateOffset) = privateDict(subrs)
                    bytes {
                        writeFontMatrix(matrix)
                        u8(29)
                        u32(size)
                        u8(29)
                        u32(privateOffset)
                        u8(18)
                    }
                }
            val fdArray = index(fontDicts)
            val fdArrayOffset = offset
            tail.bytes(fdArray)
            val fdSelectOffset = fdArrayOffset + fdArray.size
            tail.bytes(fdSelect(cid))
            top = topDict(charStringsOffset, 0, 0, fdArrayOffset, fdSelectOffset)
        }
        check(top.size == placeholder.size)
        return bytes {
            u8(1, 0, 4, 4)
            bytes(index(fontNames.map { it.toByteArray() }))
            bytes(index(listOf(top)))
            bytes(strings)
            bytes(gsubrs)
            bytes(charStrings)
            bytes(tail.toByteArray())
        }
    }

    private fun topDict(
        charStrings: Int,
        privateSize: Int,
        privateOffset: Int,
        fdArray: Int,
        fdSelect: Int,
    ): ByteArray = bytes {
        charstringType?.let {
            u8(29)
            u32(it)
            u8(12, 6)
        }
        writeFontMatrix(fontMatrix)
        u8(29)
        u32(charStrings)
        u8(17)
        if (cid == null) {
            u8(29)
            u32(privateSize)
            u8(29)
            u32(privateOffset)
            u8(18)
        } else {
            // Registry "Adobe" and ordering "Identity", from the String INDEX, supplement 0.
            u8(29)
            u32(391)
            u8(29)
            u32(392)
            u8(29)
            u32(0)
            u8(12, 30)
            u8(29)
            u32(fdArray)
            u8(12, 36)
            u8(29)
            u32(fdSelect)
            u8(12, 37)
        }
    }

    /** Writes the FontMatrix operator with [matrix], if not null. */
    private fun TestBytes.writeFontMatrix(matrix: List<String>?) {
        if (matrix == null) return
        matrix.forEach { bytes(real(it)) }
        u8(12, 7)
    }

    private fun fdSelect(cid: TestCid): ByteArray = bytes {
        val ranges = cid.fdSelectRanges
        when (cid.fdSelectFormat) {
            0 -> {
                u8(0)
                for (glyph in glyphs.indices) u8(ranges.last { it.first <= glyph }.second)
            }
            else -> {
                u8(3)
                u16(ranges.size)
                ranges.forEach { (first, fd) ->
                    u16(first)
                    u8(fd)
                }
                u16(glyphs.size)
            }
        }
    }

    /** An INDEX of [items], with offsets of [offSize] bytes. */
    fun index(items: List<ByteArray>): ByteArray = bytes {
        u16(items.size)
        if (items.isEmpty()) return@bytes
        u8(offSize)
        var offset = 1
        for (i in 0..items.size) {
            for (shift in (offSize - 1) downTo 0) u8(offset ushr (shift * 8))
            if (i < items.size) offset += items[i].size
        }
        items.forEach { bytes(it) }
    }

    /** A real number of a DICT, from its text, with its leading byte 30. */
    private fun real(text: String): ByteArray {
        val nibbles =
            text.replace("E-", "e").map {
                when (it) {
                    in '0'..'9' -> it - '0'
                    '.' -> 0xA
                    'E' -> 0xB
                    'e' -> 0xC
                    '-' -> 0xE
                    else -> error("unknown character in real $text")
                }
            } + 0xF
        val padded = if (nibbles.size % 2 == 0) nibbles else nibbles + 0xF
        return byteArrayOf(30) +
            ByteArray(padded.size / 2) { (padded[it * 2] shl 4 or padded[it * 2 + 1]).toByte() }
    }
}
