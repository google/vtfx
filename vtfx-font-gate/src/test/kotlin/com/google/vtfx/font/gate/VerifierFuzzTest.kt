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

import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.vtfx.font.FontLimits
import com.google.vtfx.font.InvalidFontException
import com.google.vtfx.font.SanitizedFont

/**
 * Fuzzes the verifier, through [SanitizedFont.fromCanonical], with fonts close to the canonical
 * form (docs/design/testing.md section 7). Almost every change of raw bytes fails the first check
 * of a checksum, so the input is a list of changes to one of the [CanonicalFonts], after which the
 * checksums of the tables and `head.checksumAdjustment` are computed again: the changed fonts reach
 * the checks of every table. Checks that the only exception is [InvalidFontException] of kind
 * Malformed, and that an accepted font keeps its bytes. vtfx-font's FontFuzzTest checks, besides,
 * that the verifier accepts only what sanitizing leaves as it is.
 *
 * In a normal test run, this replays the fixed corpus in the resources directory
 * VerifierFuzzTestInputs/mutations. The `fuzz` Gradle task fuzzes; see scripts/fuzz.sh.
 *
 * Input format, so that seeds can be written by hand:
 * - Byte 0: the font, the index in [CanonicalFonts.names] of byte % their number.
 * - The rest: changes, each 4 bytes and then its value. Byte 0 is the kind, % 4. Byte 1 is the
 *   region, % 11: a table in the order of [TABLE_TAGS], as the directory places it, or 10 for the
 *   whole file (also for a table that the directory places outside the file). Bytes 2 and 3 are the
 *   position in the region, big-endian, % the number of positions the change fits at.
 *     - Kind 0, 1, 2: the next 1, 2, or 4 bytes replace those at the position.
 *     - Kind 3: the next byte, signed, is the number of zeros to insert at the position, or, if
 *       negative, of bytes to remove from it. The tables are laid out again, and the directory gets
 *       their new offsets and lengths. Made only to a table, while the directory still lays the
 *       tables out as the canonical form does.
 *
 *   A change that does not fit in its region, or whose value the input ends before, is not made.
 */
class VerifierFuzzTest {
    @FuzzTest
    fun mutations(input: ByteArray) {
        if (input.isEmpty()) return
        // Never changed in place: each change makes a copy.
        var font = FONTS[(input[0].toInt() and 0xFF) % FONTS.size]
        var at = 1
        while (at + 4 < input.size) {
            val kind = (input[at].toInt() and 0xFF) % 4
            val region = (input[at + 1].toInt() and 0xFF) % (TABLE_TAGS.size + 1)
            val position =
                ((input[at + 2].toInt() and 0xFF) shl 8) or (input[at + 3].toInt() and 0xFF)
            val value = input.copyOfRange(at + 4, minOf(at + 4 + valueSize(kind), input.size))
            at += 4 + valueSize(kind)
            if (value.size < valueSize(kind)) break
            font =
                if (kind == RESIZE) resized(font, region, position, value[0].toInt())
                else replaced(font, region, position, value)
        }
        val changed = withChecksums(font)

        val loaded =
            try {
                SanitizedFont.fromCanonical(changed, LIMITS)
            } catch (e: InvalidFontException) {
                check(e.kind == InvalidFontException.Kind.Malformed) { "rejected as ${e.kind}" }
                return
            }
        check(loaded.toByteArray().contentEquals(changed)) { "loading changed the bytes" }
    }

    private fun valueSize(kind: Int): Int =
        when (kind) {
            0 -> 1
            1 -> 2
            2 -> 4
            else -> 1
        }

    /** [font] with [value] at [position] of [region], if it fits. */
    private fun replaced(font: ByteArray, region: Int, position: Int, value: ByteArray): ByteArray {
        val (start, length) = bounds(font, region)
        if (length < value.size) return font
        val out = font.copyOf()
        value.copyInto(out, start + position % (length - value.size + 1))
        return out
    }

    /** The start and the length of [region] in [font]. */
    private fun bounds(font: ByteArray, region: Int): Pair<Int, Int> {
        if (region == TABLE_TAGS.size) return 0 to font.size
        val offset = u32At(font, 12 + region * 16 + 8)
        val length = u32At(font, 12 + region * 16 + 12)
        return if (length > 0 && offset + length <= font.size) offset.toInt() to length.toInt()
        else 0 to font.size
    }

    /**
     * [font] with [count] zeros inserted at [position] of table [region], or -[count] bytes removed
     * from it, and the tables laid out again; or [font] as it is, if the directory no longer lays
     * out the tables as the canonical form does.
     */
    private fun resized(font: ByteArray, region: Int, position: Int, count: Int): ByteArray {
        if (region == TABLE_TAGS.size) return font
        val tables = canonicalTables(font) ?: return font
        val table = tables[region]
        val at = position % (table.size + 1)
        tables[region] =
            if (count >= 0) {
                table.copyOfRange(0, at) + ByteArray(count) + table.copyOfRange(at, table.size)
            } else {
                table.copyOfRange(0, at) +
                    table.copyOfRange(minOf(at - count, table.size), table.size)
            }
        val out = font.copyOf(DIRECTORY_END + tables.sumOf { padded(it.size) })
        var offset = DIRECTORY_END
        for ((i, data) in tables.withIndex()) {
            putU32(out, 12 + i * 16 + 8, offset)
            putU32(out, 12 + i * 16 + 12, data.size)
            out.fill(0, offset, offset + padded(data.size))
            data.copyInto(out, offset)
            offset += padded(data.size)
        }
        return out
    }

    /**
     * The tables of [font], if its directory lays them out as the canonical form does: in its
     * order, each after the padding of the one before it, and the last at the end of the file.
     */
    private fun canonicalTables(font: ByteArray): MutableList<ByteArray>? {
        val tables = ArrayList<ByteArray>()
        var expected = DIRECTORY_END.toLong()
        for (i in TABLE_TAGS.indices) {
            val offset = u32At(font, 12 + i * 16 + 8)
            val length = u32At(font, 12 + i * 16 + 12)
            if (offset != expected || offset + length > font.size) return null
            tables += font.copyOfRange(offset.toInt(), (offset + length).toInt())
            expected = offset + padded(length.toInt())
        }
        return if (expected == font.size.toLong()) tables else null
    }

    private fun padded(size: Int): Int = (size + 3) and 3.inv()

    private companion object {
        val LIMITS = FontLimits()

        /** The [CanonicalFonts], read once rather than for every input. */
        val FONTS: List<ByteArray> = CanonicalFonts.names.map { CanonicalFonts.read(it) }

        const val RESIZE = 3
    }
}
