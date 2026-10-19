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

import com.google.vtfx.font.InvalidFontException.Kind
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/**
 * The verifier of vtfx-font-gate, through [SanitizedFont.fromCanonical], which runs only it. The
 * tests are here rather than in vtfx-font-gate, since the fonts that they check are written by this
 * module.
 */
class CanonicalVerifierTest {
    private val limits = FontLimits()

    /** A canonical font with composite glyphs, variation sequences, and an empty glyph. */
    private val canonical: ByteArray =
        FontFile.sanitize(
                TestTrueTypeFont(
                        glyphs =
                            listOf(
                                TestGlyph.Simple(listOf(square(0, 0, 500))),
                                TestGlyph.Simple(
                                    listOf(
                                        listOf(P(0, 0), P(300, 700, on = false), P(600, 0)),
                                        square(1000, 1000, 1),
                                    )
                                ),
                                TestGlyph.Composite(
                                    listOf(TestComponent(1, 0, 0), TestComponent(0, 10, 300))
                                ),
                                TestGlyph.Empty(),
                            ),
                        characters = mapOf(0x41 to 1, 0x42 to 2, 0x43 to 3, 0x845B to 1),
                        variations = mapOf(0xE0100 to Pair(listOf(0x845B), mapOf(0x845B to 2))),
                    )
                    .build(),
                0,
                limits,
            )
            .toByteArray()

    private fun verifyFailure(font: ByteArray): Kind =
        assertFailsWith<InvalidFontException> { SanitizedFont.fromCanonical(font, limits) }.kind

    /** The offset of table [name] in [font]. */
    private fun tableOffset(font: ByteArray, name: String): Int {
        val reader = Reader(font)
        val index = (0 until 10).first { reader.s32(12 + it * 16) == tag(name) }
        return reader.index32(12 + index * 16 + 8)
    }

    /** [font] with the checksums made right again after a change. */
    private fun withChecksums(font: ByteArray): ByteArray {
        val out = font.copyOf()
        val reader = Reader(out)
        // head is the fourth table; its offset may have been changed to point anywhere.
        val head = reader.u32(12 + 3 * 16 + 8).toInt()
        if (head < 0 || head > out.size - 12) return out
        out.fill(0, head + 8, head + 12)
        for (i in 0 until 10) {
            val offset = reader.u32(12 + i * 16 + 8)
            val length = reader.u32(12 + i * 16 + 12)
            // A change to the directory can point outside the file, which the verifier rejects.
            if (offset + length > out.size) continue
            putU32(
                out,
                12 + i * 16 + 4,
                TestSfnt.checksum(out.copyOfRange(offset.toInt(), (offset + length).toInt())),
            )
        }
        putU32(out, head + 8, -0x4E4F5046 - TestSfnt.checksum(out))
        return out
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) bytes[at + i] = (value ushr (24 - i * 8)).toByte()
    }

    @Test
    fun sanitizedFontPasses() {
        assertEquals(
            WidthClass.Monospaced,
            SanitizedFont.fromCanonical(canonical, limits).widthClass,
        )
    }

    @Test
    fun wrongTableChecksumIsMalformed() {
        val font = canonical.copyOf()
        font[12 + 4] = (font[12 + 4] + 1).toByte()

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun wrongChecksumAdjustmentIsMalformed() {
        val font = canonical.copyOf()
        val head = tableOffset(font, "head")
        font[head + 11] = (font[head + 11] + 1).toByte()

        assertEquals(Kind.Malformed, verifyFailure(font))
    }

    @Test
    fun byteAfterTheTablesIsMalformed() {
        assertEquals(Kind.Malformed, verifyFailure(canonical + 0))
    }

    @Test
    fun truncatedFontIsMalformed() {
        assertEquals(Kind.Malformed, verifyFailure(canonical.copyOf(canonical.size - 4)))
    }

    @Test
    fun fontLargerThanTheOutputLimitIsTooLarge() {
        val kind =
            assertFailsWith<InvalidFontException> {
                    SanitizedFont.fromCanonical(canonical, FontLimits(maxOutputBytes = 100))
                }
                .kind

        assertEquals(Kind.TooLarge, kind)
    }

    @Test
    fun glyphWithMorePointsThanTheLimitIsMalformed() {
        val kind =
            assertFailsWith<InvalidFontException> {
                    SanitizedFont.fromCanonical(canonical, FontLimits(maxGlyphPoints = 4))
                }
                .kind

        assertEquals(Kind.Malformed, kind)
    }

    @Test
    fun overlapFlagOfAGlyphIsMalformed() {
        val font = canonical.copyOf()
        // The first flag of glyph 0, a square of one contour: after the header, one end point, and
        // the length of the instructions.
        val flag = tableOffset(font, "glyf") + 10 + 2 + 2
        font[flag] = (font[flag].toInt() or 0x40).toByte()

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun compositeGlyphIsMalformed() {
        val font = canonical.copyOf()
        val glyf = tableOffset(font, "glyf")
        font[glyf] = 0xFF.toByte()
        font[glyf + 1] = 0xFF.toByte()

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun instructionsAreMalformed() {
        val font = canonical.copyOf()
        val instructionLength = tableOffset(font, "glyf") + 10 + 2
        font[instructionLength + 1] = 1

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    @Test
    fun fontRevisionOtherThanOneIsMalformed() {
        val font = canonical.copyOf()
        font[tableOffset(font, "head") + 7] = 1

        assertEquals(Kind.Malformed, verifyFailure(withChecksums(font)))
    }

    /**
     * Every byte is checked: a change of any one byte, with the checksums made right again, is
     * either rejected or gives another canonical font, which sanitizing leaves as it is.
     */
    @Test
    fun everyChangeOfOneByteIsRejectedOrCanonical() {
        val accepted = ArrayList<Pair<Int, Int>>()
        val notFixedPoints = ArrayList<Pair<Int, Int>>()

        for (at in canonical.indices) {
            for (delta in intArrayOf(1, -1, 0x40, 0x80)) {
                val font = canonical.copyOf()
                font[at] = (font[at] + delta).toByte()
                val fixed = withChecksums(font)
                if (fixed.contentEquals(canonical)) continue
                try {
                    SanitizedFont.fromCanonical(fixed, limits)
                } catch (_: InvalidFontException) {
                    continue
                }
                accepted += at to delta
                val again = FontFile.sanitize(fixed, 0, limits).toByteArray()
                if (!again.contentEquals(fixed)) notFixedPoints += at to delta
            }
        }

        assertEquals(emptyList(), notFixedPoints, "accepted: $accepted")
    }

    @Test
    fun canonicalFontIsAFixedPointOfSanitizing() {
        val again = FontFile.sanitize(canonical, 0, limits).toByteArray()

        assertContentEquals(canonical, again)
    }
}
