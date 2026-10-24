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
import com.google.vtfx.font.gate.SanitizedFont
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Cross-checks of the verifier of vtfx-font-gate, which [SanitizedFont.fromCanonical] runs, with
 * the readers and the writer of this module: the fonts that the verifier accepts are those that
 * sanitizing leaves as they are. The verifier's own tests are in vtfx-font-gate.
 */
class VerifierCrossCheckTest {
    private val limits = FontLimits()

    /** A canonical font with composite glyphs, variation sequences, and an empty glyph. */
    private val canonical: ByteArray =
        FontFile.sanitize(
                variationsTestFont().build(),
                0,
                limits,
            )
            .toByteArray()

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
                val verified =
                    try {
                        SanitizedFont.fromCanonical(fixed, limits)
                    } catch (_: InvalidFontException) {
                        null
                    }
                if (verified == null) continue
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

    /**
     * The verifier allows as many variation sequences of all selectors together as sanitizing does:
     * it accepts the font of the most that sanitizing writes.
     */
    @Test
    fun verifierAcceptsAsManyVariationSequencesAsSanitizingWrites() {
        // 2^19 default sequences, and 2^19 - 1 default and 1 non-default one: 2^20.
        val font =
            TestTrueTypeFont(
                variations =
                    mapOf(
                        0xFE00 to Pair((0x10000 until 0x90000).toList(), emptyMap()),
                        0xFE01 to Pair((0x90001 until 0x110000).toList(), mapOf(0x41 to 1)),
                    )
            )
        val sanitized = FontFile.sanitize(font.build(), 0, limits).toByteArray()

        val loaded = SanitizedFont.fromCanonical(sanitized, limits)

        assertContentEquals(sanitized, loaded.toByteArray())
    }

    /**
     * The verifier allows as many points of all the glyphs together as sanitizing does: it accepts
     * the font of the most that sanitizing writes.
     */
    @Test
    fun verifierAcceptsAsManyPointsAsSanitizingWrites() {
        // 2048 glyphs of 16384 points: 2^25.
        val sanitized =
            FontFile.sanitize(fontOfGlyphsAtTheOrigin(glyphs = 2048).build(), 0, limits)
                .toByteArray()

        val loaded = SanitizedFont.fromCanonical(sanitized, limits)

        assertContentEquals(sanitized, loaded.toByteArray())
    }
}
