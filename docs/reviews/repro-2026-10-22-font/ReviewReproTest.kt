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

import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.InvalidFontException.Kind
import com.google.vtfx.font.gate.SanitizedFont
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Reproduction tests for the review of vtfx-font, vtfx-font-gate, vtfx-font-gate-android, and
 * TerminalFont.Sanitized at b649be2a7d94a8b56c2921c235bdcef9c606f018.
 */
class ReviewReproTest {
    private val limits = FontLimits()

    /**
     * R1a: `CanonicalVerifier` in `vtfx-font-gate` does not enforce the `2^20` (1,048,576) limit
     * on total variation sequences across all selectors (section 9 of docs/design/fonts.md).
     *
     * A 17 KB canonical font with 4,097 default UVS ranges of 256 code points each (1,048,832
     * variation sequences > 2^20) is accepted by `SanitizedFont.fromCanonical`.
     */
    @Test
    fun r1a_canonicalVerifierRejectsMoreThanMaxVariationSequences() {
        val canonical = canonicalFontWithDefaultVariationRanges(4097)

        assertFailsWith<InvalidFontException> {
            SanitizedFont.fromCanonical(canonical, limits)
        }
    }

    /**
     * R1b: Because `SanitizedFont.fromCanonical` accepts a canonical font with > 2^20 variation
     * sequences that `FontFile.sanitize` rejects as `TooComplex`, the fixed-point invariant of
     * section 6 and section 10 of docs/design/fonts.md is violated, and `FontFuzzer` crashes at
     * line 59 when re-sanitizing the font that `fromCanonical` accepted.
     */
    @Test
    fun r1b_canonicalFontAcceptedByVerifierIsAcceptedBySanitize() {
        val canonical = canonicalFontWithDefaultVariationRanges(4097)
        val loaded = SanitizedFont.fromCanonical(canonical, limits)

        val resanitized = FontFile.sanitize(loaded.toByteArray(), 0, limits)

        assertEquals(loaded, resanitized)
    }

    /**
     * R2a: Neither `GlyfOutlines` nor `CanonicalWriter` bounds the total glyph points across all
     * glyphs of a face (section 9 of docs/design/fonts.md). Because 256 repeated `(0, 0)` delta
     * flags encode into 2 bytes in `glyf`, a 164 KB TrueType font with 1 simple glyph of 16,384
     * `(0, 0)` points (144 bytes) and 8,192 1-component composite glyphs referencing it visits
     * only 8,192 components (<< 2^22) and writes only a 1.2 MB canonical font (<< 64 MiB), while
     * forcing `FontFile.sanitize` to process 134,234,112 (> 2^27) points across the face (and up
     * to 1,073,709,056 points for 65,534 glyphs in 9.7 MB).
     */
    @Test
    fun r2a_pointsAcrossAllGlyphsOfAFaceAreBoundedInSanitize() {
        val font = fontWithRepeatedPointComposites(compositeGlyphCount = 8192)

        val e = assertFailsWith<InvalidFontException> {
            FontFile.sanitize(font, 0, limits)
        }
        assertEquals(Kind.TooComplex, e.kind)
    }

    /**
     * R2b: `CanonicalVerifier` in `vtfx-font-gate` also does not bound the total glyph points
     * across all glyphs of a face: a 1.2 MB canonical font with 8,193 glyphs of 16,384 `(0, 0)`
     * points each (134,234,112 points, and up to 1,073,725,440 points in 9.7 MB) is accepted by
     * `SanitizedFont.fromCanonical`.
     */
    @Test
    fun r2b_pointsAcrossAllGlyphsOfAFaceAreBoundedInCanonicalVerifier() {
        val canonical = FontFile.sanitize(
            fontWithRepeatedPointComposites(compositeGlyphCount = 8192),
            0,
            limits,
        ).toByteArray()

        assertFailsWith<InvalidFontException> {
            SanitizedFont.fromCanonical(canonical, limits)
        }
    }

    /**
     * R3a: `CffFont.checkFontMatrix` accepts the identity `FontMatrix` (`[1, 0, 0, 1, 0, 0]`) in
     * a name-keyed CFF font, even though there is no Font DICT to supply the `1/unitsPerEm` scale
     * (section 4 of docs/design/fonts.md).
     */
    @Test
    fun r3a_nameKeyedCffWithIdentityFontMatrixIsUnsupported() {
        val font =
            TestCffFont(
                unitsPerEm = 1000,
                fontMatrix = listOf("1", "0", "0", "1", "0", "0"),
            ).build()

        val e = assertFailsWith<InvalidFontException> {
            FontFile.sanitize(font, 0, limits)
        }
        assertEquals(Kind.Unsupported, e.kind)
    }

    /**
     * R3b: `CffFont.checkFontMatrix` returns immediately when `FontMatrix` is omitted from a DICT,
     * ignoring that CFF's default `FontMatrix` is `0.001` (`1/1000`). A CFF font with
     * `unitsPerEm = 2048` and no `FontMatrix` is accepted instead of being rejected as
     * `Unsupported` (section 4 of docs/design/fonts.md).
     */
    @Test
    fun r3b_cffWithUnitsPerEmOtherThan1000AndDefaultFontMatrixIsUnsupported() {
        val font = TestCffFont(unitsPerEm = 2048, fontMatrix = null).build()

        val e = assertFailsWith<InvalidFontException> {
            FontFile.sanitize(font, 0, limits)
        }
        assertEquals(Kind.Unsupported, e.kind)
    }

    /**
     * R3c: In a CID-keyed CFF font, `CffFont.checkFontMatrix` checks the Top DICT and each Font
     * DICT in isolation rather than requiring one to hold `1/unitsPerEm` and the other the
     * identity (section 4 of docs/design/fonts.md). A CID-keyed font whose Top DICT has the
     * identity matrix and whose Font DICT has no `FontMatrix` with `unitsPerEm = 2048` (so neither
     * holds `1/2048`) is accepted.
     */
    @Test
    fun r3c_cidKeyedCffWithoutEmScaleInEitherDictIsUnsupported() {
        val font =
            TestCffFont(
                unitsPerEm = 2048,
                fontMatrix = listOf("1", "0", "0", "1", "0", "0"),
                cid = TestCid(fontDictSubrs = listOf(emptyList()), fdSelectRanges = listOf(0 to 0)),
            ).build()

        val e = assertFailsWith<InvalidFontException> {
            FontFile.sanitize(font, 0, limits)
        }
        assertEquals(Kind.Unsupported, e.kind)
    }

    /**
     * Builds a canonical font whose `cmap` table has [rangeCount] canonical Format 14 default UVS
     * ranges of 256 code points each (`0xE000 + r * 256 .. 0xE000 + r * 256 + 255`) under
     * `U+FE00`.
     */
    private fun canonicalFontWithDefaultVariationRanges(rangeCount: Int): ByteArray {
        val base = FontFile.sanitize(TestTrueTypeFont().build(), 0, limits).toByteArray()
        val format14Length = 10 + 11 + 4 + rangeCount * 4
        val format12Length = 16 + 12
        val cmapBytes = ByteArrayOutputStream()
        DataOutputStream(cmapBytes).use { out ->
            out.writeShort(0) // version
            out.writeShort(2) // numTables
            // Record 0: (0, 5) Format 14 at offset 20
            out.writeShort(0)
            out.writeShort(5)
            out.writeInt(20)
            // Record 1: (3, 10) Format 12 at offset 20 + format14Length
            out.writeShort(3)
            out.writeShort(10)
            out.writeInt(20 + format14Length)

            // Format 14 subtable
            out.writeShort(14)
            out.writeInt(format14Length)
            out.writeInt(1) // 1 selector record
            // Selector U+FE00, defaultUVSOffset = 21, nonDefaultUVSOffset = 0
            out.writeByte(0x00)
            out.writeShort(0xFE00)
            out.writeInt(21)
            out.writeInt(0)
            // Default UVS table
            out.writeInt(rangeCount)
            for (r in 0 until rangeCount) {
                val start = 0xE000 + r * 256
                out.writeByte(start ushr 16)
                out.writeShort(start and 0xFFFF)
                out.writeByte(255)
            }

            // Format 12 subtable mapping 'A' (0x41) -> glyph 1
            out.writeShort(12)
            out.writeShort(0)
            out.writeInt(format12Length)
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(0x41)
            out.writeInt(0x41)
            out.writeInt(1)
        }
        return replaceTableInCanonical(base, tableIndex = 1, newTable = cmapBytes.toByteArray())
    }

    private fun replaceTableInCanonical(
        canonical: ByteArray,
        tableIndex: Int,
        newTable: ByteArray,
    ): ByteArray {
        val reader = Reader(canonical)
        val tables = Array(10) { i ->
            if (i == tableIndex) {
                newTable
            } else {
                val offset = reader.u32(12 + i * 16 + 8).toInt()
                val length = reader.u32(12 + i * 16 + 12).toInt()
                canonical.copyOfRange(offset, offset + length)
            }
        }
        val directoryEnd = 12 + 10 * 16
        var totalSize = directoryEnd
        for (table in tables) totalSize += (table.size + 3) and 3.inv()
        val out = ByteArray(totalSize)
        canonical.copyInto(out, 0, 0, directoryEnd)
        var offset = directoryEnd
        var headOffset = 0
        for (i in 0 until 10) {
            val table = tables[i]
            if (i == 3) {
                headOffset = offset
                table.fill(0, 8, 12)
            }
            table.copyInto(out, offset)
            val record = 12 + i * 16
            putU32(out, record + 4, TestSfnt.checksum(table))
            putU32(out, record + 8, offset)
            putU32(out, record + 12, table.size)
            offset += (table.size + 3) and 3.inv()
        }
        putU32(out, headOffset + 8, -0x4E4F5046 - TestSfnt.checksum(out))
        return out
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) bytes[at + i] = (value ushr (24 - i * 8)).toByte()
    }

    /**
     * Builds a TrueType font where glyph 1 is a 144-byte simple glyph of 16,384 `(0, 0)` points
     * encoded with 64 repeat-flag pairs (`[0x39, 0xFF]`), and `compositeGlyphCount` glyphs each
     * reference glyph 1 as a single component.
     */
    private fun fontWithRepeatedPointComposites(compositeGlyphCount: Int): ByteArray {
        val base = TestTrueTypeFont(
            glyphs = listOf(TestGlyph.Empty(), TestGlyph.Simple(listOf(square(0, 0, 100)))),
            characters = mapOf('A'.code to 1),
        ).tables().toMutableMap()
        val numGlyphs = 2 + compositeGlyphCount

        val simpleGlyph = ByteArrayOutputStream().apply {
            DataOutputStream(this).let { out ->
                out.writeShort(1) // 1 contour
                out.writeShort(0) // xMin
                out.writeShort(0) // yMin
                out.writeShort(0) // xMax
                out.writeShort(0) // yMax
                out.writeShort(16383) // endPtOfContours[0] = 16383 -> 16384 points
                out.writeShort(0) // instructionLength = 0
                // 64 * 256 = 16384 points, each with ON_CURVE | REPEAT | X_SAME | Y_SAME (0x39)
                // repeated 255 additional times.
                repeat(64) {
                    out.writeByte(0x39)
                    out.writeByte(255)
                }
                out.writeShort(0) // 2 bytes padding to 144 bytes
            }
        }.toByteArray()

        val compositeGlyph = ByteArrayOutputStream().apply {
            DataOutputStream(this).let { out ->
                out.writeShort(-1) // composite
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(0x0002) // ARGS_ARE_XY_VALUES (byte offsets)
                out.writeShort(1) // component glyphId = 1
                out.writeByte(0) // dx = 0
                out.writeByte(0) // dy = 0
            }
        }.toByteArray()

        val glyf = ByteArrayOutputStream()
        val loca = ByteArrayOutputStream()
        val locaOut = DataOutputStream(loca)
        val hmtx = ByteArrayOutputStream()
        val hmtxOut = DataOutputStream(hmtx)

        // Glyph 0: empty
        locaOut.writeInt(0)
        hmtxOut.writeShort(500)
        hmtxOut.writeShort(0)

        // Glyph 1: 16,384-point simple glyph
        locaOut.writeInt(glyf.size())
        glyf.write(simpleGlyph)
        hmtxOut.writeShort(500)
        hmtxOut.writeShort(0)

        // Glyphs 2..numGlyphs-1: 1-component composite glyphs referencing glyph 1
        repeat(compositeGlyphCount) {
            locaOut.writeInt(glyf.size())
            glyf.write(compositeGlyph)
            hmtxOut.writeShort(500)
            hmtxOut.writeShort(0)
        }
        locaOut.writeInt(glyf.size())

        base["glyf"] = glyf.toByteArray()
        base["loca"] = loca.toByteArray()
        base["hmtx"] = hmtx.toByteArray()
        base["maxp"] = bytes { u32(0x00010000).u16(numGlyphs).bytes(ByteArray(26)) }
        base["hhea"] = bytes {
            u32(0x00010000).u16(800).u16(-200).u16(0)
            bytes(ByteArray(24))
            u16(numGlyphs)
        }
        return TestSfnt.font(base)
    }
}
