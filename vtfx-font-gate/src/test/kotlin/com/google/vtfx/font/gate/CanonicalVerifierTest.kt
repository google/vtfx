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

package com.google.vtfx.font.gate

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.vtfx.font.gate.InvalidFontException.Kind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CanonicalVerifier], through [SanitizedFont.fromCanonical], which runs only it, with the
 * [CanonicalFonts] that vtfx-font writes and changes to them. vtfx-font's VerifierCrossCheckTest
 * checks, besides, that the fonts that the verifier accepts are those that sanitizing leaves as
 * they are.
 */
@RunWith(TestParameterInjector::class)
class CanonicalVerifierTest {
    private val limits = FontLimits()

    /**
     * Composite glyphs, made simple, and variation sequences: 4 glyphs, of 4, 7, 11, and no points.
     */
    private val variations = CanonicalFonts.read("variations.ttf")

    private fun verifyFailure(font: ByteArray, limits: FontLimits = this.limits): Kind =
        assertFailsWith<InvalidFontException> { SanitizedFont.fromCanonical(font, limits) }.kind

    @Test
    fun canonicalFontLoadsWithItsBytes(
        @TestParameter("variations.ttf", "dual-width.ttf", "cff.ttf", "fewer-points.ttf")
        name: String
    ) {
        val font = CanonicalFonts.read(name)

        val loaded = SanitizedFont.fromCanonical(font, limits)

        assertContentEquals(font, loaded.toByteArray())
    }

    @Test
    fun fontWithOneAdvanceWidthIsMonospaced() {
        assertEquals(
            WidthClass.Monospaced,
            SanitizedFont.fromCanonical(variations, limits).widthClass,
        )
    }

    @Test
    fun fontWithWidthsOfOneAndTwiceThatIsDualWidth() {
        val font = CanonicalFonts.read("dual-width.ttf")

        assertEquals(WidthClass.DualWidth, SanitizedFont.fromCanonical(font, limits).widthClass)
    }

    @Test
    fun fontWithOtherWidthsIsProportional() {
        val font = CanonicalFonts.read("fewer-points.ttf")

        assertEquals(WidthClass.Proportional, SanitizedFont.fromCanonical(font, limits).widthClass)
    }

    @Test
    fun glyphWithFewerPointsThanTheGlyphBeforeItPasses() {
        // Glyphs share the arrays of points, so the bounding box of glyph 1, of 4 points, would
        // take in the points of glyph 0, of 8, at (0, 0) if the verifier read past its own.
        val font = CanonicalFonts.read("fewer-points.ttf")

        val loaded = SanitizedFont.fromCanonical(font, limits)

        assertContentEquals(font, loaded.toByteArray())
    }

    @Test
    fun wrongTableChecksumIsMalformed() {
        val font = variations.copyOf()
        font[12 + 4] = (font[12 + 4] + 1).toByte()

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun wrongChecksumAdjustmentIsMalformed() {
        val font = variations.copyOf()
        val head = tableOffset(font, "head")
        font[head + 11] = (font[head + 11] + 1).toByte()

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun byteAfterTheTablesIsMalformed() {
        assertEquals(Kind.Malformed, verifyFailure(variations + 0))
    }

    @Test
    fun truncatedFontIsMalformed() {
        assertEquals(Kind.Malformed, verifyFailure(variations.copyOf(variations.size - 4)))
    }

    @Test
    fun fontLargerThanTheOutputLimitIsTooLarge() {
        val limits = FontLimits(maxOutputBytes = variations.size - 1)

        assertEquals(Kind.TooLarge, verifyFailure(variations, limits))
    }

    @Test
    fun fontTooLargeToCopyIsTooLargeRatherThanOutOfMemory() {
        // Larger than half the heap: one more copy of it does not fit.
        val size = minOf(Runtime.getRuntime().maxMemory() * 3 / 5, Int.MAX_VALUE - 8L).toInt()
        val bytes = ByteArray(size)

        assertEquals(Kind.TooLarge, verifyFailure(bytes, limits))
    }

    @Test
    fun fontAsLargeAsTheOutputLimitPasses() {
        val limits = FontLimits(maxOutputBytes = variations.size)

        val loaded = SanitizedFont.fromCanonical(variations, limits)

        assertContentEquals(variations, loaded.toByteArray())
    }

    @Test
    fun glyphWithMorePointsThanTheLimitIsMalformed() {
        val limits = FontLimits(maxGlyphPoints = 10)

        assertEquals(Kind.Malformed, verifyFailure(variations, limits))
    }

    @Test
    fun glyphWithAsManyPointsAsTheLimitPasses() {
        val limits = FontLimits(maxGlyphPoints = 11)

        val loaded = SanitizedFont.fromCanonical(variations, limits)

        assertContentEquals(variations, loaded.toByteArray())
    }

    @Test
    fun overlapFlagOfAGlyphIsMalformed() {
        val font = variations.copyOf()
        // The first flag of glyph 0, a square of one contour: after the header, one end point, and
        // the length of the instructions.
        val flag = tableOffset(font, "glyf") + 10 + 2 + 2
        font[flag] = (font[flag].toInt() or 0x40).toByte()

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun compositeGlyphIsMalformed() {
        val font = variations.copyOf()
        putU16(font, tableOffset(font, "glyf"), 0xFFFF)

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun instructionsAreMalformed() {
        val font = variations.copyOf()
        val instructionLength = tableOffset(font, "glyf") + 10 + 2
        putU16(font, instructionLength, 1)

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun fontRevisionOtherThanOneIsMalformed() {
        val font = variations.copyOf()
        putU32(font, tableOffset(font, "head") + 4, 0x00010001)

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun cmapGroupOfAGlyphOutsideTheFontIsMalformed() {
        val font = variations.copyOf()
        // The glyph of the first group of format 12, which follows format 14 at 58: after the
        // header of 16 bytes, the first and last characters.
        putU32(font, tableOffset(font, "cmap") + 58 + 16 + 8, 4)

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun variationSequencesOfAllSelectorsAsManyAsTheLimitPass() {
        // 2^19 default sequences, and 2^19 - 256 default and 256 non-default ones: 2^20.
        val font =
            withVariationSequences(
                Selector(0xFE00, defaultRanges = 0 until 2048),
                Selector(0xFE01, defaultRanges = 2048 until 4095, nonDefaults = 0x41 until 0x141),
            )

        val loaded = SanitizedFont.fromCanonical(font, limits)

        assertContentEquals(font, loaded.toByteArray())
    }

    @Test
    fun defaultVariationSequencesOfOneSelectorBeyondTheLimitAreMalformed() {
        val font = withVariationSequences(Selector(0xFE00, defaultRanges = 0 until 4097))

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun nonDefaultVariationSequenceBeyondTheLimitIsMalformed() {
        val font =
            withVariationSequences(
                Selector(0xFE00, defaultRanges = 0 until 4096, nonDefaults = 0x41..0x41)
            )

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun variationSequencesOfSeveralSelectorsBeyondTheLimitAreMalformed() {
        val font =
            withVariationSequences(
                Selector(0xFE00, defaultRanges = 0 until 2048),
                Selector(0xFE01, defaultRanges = 2048 until 4096),
                Selector(0xFE02, nonDefaults = 0x41..0x41),
            )

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    /**
     * Every byte of the header, the directory, and the tables with no number left free (hhea, loca,
     * maxp, name) is fixed or computed from other parts of the font: a change of any one of them,
     * with the checksums made right again, is rejected. Bytes elsewhere can be numbers within a
     * range, such as the weight class or the coordinates of a point inside the bounding box.
     */
    @Test
    fun everyChangeOfOneFixedByteIsMalformed(
        @TestParameter("variations.ttf", "dual-width.ttf", "cff.ttf", "fewer-points.ttf")
        name: String
    ) {
        val font = CanonicalFonts.read(name)
        val fixed =
            listOf(0 until DIRECTORY_END) +
                listOf("hhea", "loca", "maxp", "name").map { tag ->
                    tableOffset(font, tag) until tableOffset(font, tag) + tableLength(font, tag)
                }
        val accepted = ArrayList<Pair<Int, Int>>()

        for (at in fixed.flatten()) {
            for (delta in intArrayOf(1, -1, 0x40, 0x80)) {
                val changed = font.copyOf()
                changed[at] = (changed[at] + delta).toByte()
                val fixedUp = withChecksums(changed)
                // A change to a checksum is undone by computing the checksums again.
                if (fixedUp.contentEquals(font)) continue
                try {
                    SanitizedFont.fromCanonical(fixedUp, limits)
                } catch (_: InvalidFontException) {
                    continue
                }
                accepted += at to delta
            }
        }

        assertEquals(emptyList(), accepted)
    }

    /**
     * The variation sequences of a selector of format 14: the default ones of the ranges of 256
     * code points from U+E000 numbered by [defaultRanges], and non-default ones of [nonDefaults] to
     * glyph 1.
     */
    private class Selector(
        val selector: Int,
        val defaultRanges: IntRange = IntRange.EMPTY,
        val nonDefaults: IntRange = IntRange.EMPTY,
    )

    /** [variations] with a format 14 subtable of [selectors] instead of its own. */
    private fun withVariationSequences(vararg selectors: Selector): ByteArray {
        val recordBytes = ByteArrayOutputStream()
        val records = DataOutputStream(recordBytes)
        val tables = ByteArrayOutputStream()
        val tablesOut = DataOutputStream(tables)
        val recordsEnd = 10 + 11 * selectors.size
        for (s in selectors) {
            putU24(records, s.selector)
            if (s.defaultRanges.isEmpty()) {
                records.writeInt(0)
            } else {
                records.writeInt(recordsEnd + tables.size())
                tablesOut.writeInt(s.defaultRanges.count())
                for (r in s.defaultRanges) {
                    putU24(tablesOut, 0xE000 + r * 256)
                    tablesOut.writeByte(255)
                }
            }
            if (s.nonDefaults.isEmpty()) {
                records.writeInt(0)
            } else {
                records.writeInt(recordsEnd + tables.size())
                tablesOut.writeInt(s.nonDefaults.count())
                for (c in s.nonDefaults) {
                    putU24(tablesOut, c)
                    tablesOut.writeShort(1)
                }
            }
        }
        val format14Length = recordsEnd + tables.size()
        val cmap = tableOffset(variations, "cmap")
        val format12 = u32At(variations, cmap + 16).toInt()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            // The records (0, 5) and (3, 10), as variations.ttf has them, then format 14.
            writeShort(0)
            writeShort(2)
            writeShort(0)
            writeShort(5)
            writeInt(20)
            writeShort(3)
            writeShort(10)
            writeInt(20 + format14Length)
            writeShort(14)
            writeInt(format14Length)
            writeInt(selectors.size)
            write(recordBytes.toByteArray())
            write(tables.toByteArray())
            write(variations, cmap + format12, tableLength(variations, "cmap") - format12)
        }
        return withTables(variations, mapOf("cmap" to bytes.toByteArray()))
    }

    private fun putU24(out: DataOutputStream, value: Int) {
        out.writeByte(value ushr 16)
        out.writeShort(value)
    }

    /**
     * [font] with the tables of [tables] instead of its own, laid out again in the order of the
     * canonical form, with its checksums made right again.
     */
    private fun withTables(font: ByteArray, tables: Map<String, ByteArray>): ByteArray {
        val contents = TABLE_TAGS.map { tag ->
            tables[tag]
                ?: font.copyOfRange(
                    tableOffset(font, tag),
                    tableOffset(font, tag) + tableLength(font, tag),
                )
        }
        val out = ByteArray(DIRECTORY_END + contents.sumOf { (it.size + 3) and 3.inv() })
        font.copyInto(out, 0, 0, DIRECTORY_END)
        var offset = DIRECTORY_END
        for ((i, table) in contents.withIndex()) {
            putU32(out, 12 + i * 16 + 8, offset)
            putU32(out, 12 + i * 16 + 12, table.size)
            table.copyInto(out, offset)
            offset += (table.size + 3) and 3.inv()
        }
        return withChecksums(out)
    }
}
