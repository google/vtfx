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

/**
 * The characters of a face: [codePoints], sorted and distinct, map to [glyphs]. Only glyphs other
 * than 0 (`.notdef`) are mapped.
 */
internal class CharMap(val codePoints: IntArray, val glyphs: IntArray)

/**
 * The glyphs of the variation sequences with one variation [selector]: [defaults] are the base
 * characters drawn with their usual glyph, and [baseCharacters] are drawn with [glyphs]. Both lists
 * of characters are sorted and distinct.
 */
internal class VariationSelector(
    val selector: Int,
    val defaults: IntArray,
    val baseCharacters: IntArray,
    val glyphs: IntArray,
)

/**
 * Reads the Unicode mapping of a `cmap` table (format 12, or format 4 without one) and its
 * variation sequences (format 14).
 */
internal class CmapReader(private val cmap: Reader, private val numGlyphs: Int) {
    private class Subtable(val platform: Int, val encoding: Int, val data: Reader) {
        val format: Int = data.u16(0)
    }

    private val subtables: List<Subtable> =
        List(cmap.u16(2)) {
            val record = 4 + it * 8
            Subtable(cmap.u16(record), cmap.u16(record + 2), cmap.from(cmap.index32(record + 4)))
        }

    fun charMap(): CharMap {
        val best =
            subtables
                .mapNotNull { subtable -> rank(subtable)?.let { subtable to it } }
                .minByOrNull { it.second }
                ?.first ?: unsupported("no Unicode cmap")
        val entries = MappingCollector(MAX_CODE_POINTS, keepNotdef = false)
        when (best.format) {
            12 -> readFormat12(best.data, entries)
            4 -> readFormat4(best.data, entries)
            else -> error("format ${best.format} has no rank")
        }
        val sorted = entries.sorted()
        val codePoints = IntArray(sorted.size) { (sorted[it] ushr 32).toInt() }
        val glyphs = IntArray(sorted.size) { sorted[it].toInt() }
        for (i in 1 until codePoints.size) {
            if (codePoints[i] == codePoints[i - 1]) {
                malformed("U+%X mapped twice".format(codePoints[i]))
            }
        }
        if (codePoints.isEmpty()) unsupported("no characters")
        return CharMap(codePoints, glyphs)
    }

    /** The preference of a Unicode subtable; lower is better, null for others. */
    private fun rank(subtable: Subtable): Int? {
        val unicode =
            (subtable.platform == PLATFORM_WINDOWS && subtable.encoding in setOf(1, 10)) ||
                (subtable.platform == PLATFORM_UNICODE && subtable.encoding in 0..4)
        if (!unicode) return null
        val windows = if (subtable.platform == PLATFORM_WINDOWS) 0 else 1
        return when (subtable.format) {
            12 -> windows
            4 -> 2 + windows
            else -> null
        }
    }

    private fun readFormat12(data: Reader, entries: MappingCollector) {
        val groups = data.u32(12)
        if (groups > data.length / 12) malformed("cmap groups beyond the table")
        for (i in 0 until groups.toInt()) {
            val group = 16 + i * 12
            val start = data.u32(group)
            val end = data.u32(group + 4)
            val startGlyph = data.u32(group + 8)
            if (start > end || end > MAX_CODE_POINT) malformed("cmap group $start..$end")
            for (c in start..end) {
                val glyph = startGlyph + (c - start)
                if (glyph >= numGlyphs) malformed("U+%X mapped to glyph $glyph".format(c))
                entries.add(c.toInt(), glyph.toInt())
            }
        }
    }

    private fun readFormat4(data: Reader, entries: MappingCollector) {
        val segments = data.u16(6) / 2
        val endCodes = 14
        val startCodes = endCodes + segments * 2 + 2
        val idDeltas = startCodes + segments * 2
        val idRangeOffsets = idDeltas + segments * 2
        for (s in 0 until segments) {
            val start = data.u16(startCodes + s * 2)
            val end = data.u16(endCodes + s * 2)
            if (start > end) malformed("cmap segment $start..$end")
            val delta = data.u16(idDeltas + s * 2)
            val rangeOffset = data.u16(idRangeOffsets + s * 2)
            for (c in start..end) {
                // The segment that ends the table maps U+FFFF, a noncharacter, to nothing.
                if (c == 0xFFFF) continue
                val glyph =
                    if (rangeOffset == 0) {
                        (c + delta) and 0xFFFF
                    } else {
                        val index = data.u16(idRangeOffsets + s * 2 + rangeOffset + (c - start) * 2)
                        if (index == 0) 0 else (index + delta) and 0xFFFF
                    }
                if (glyph >= numGlyphs) malformed("U+%X mapped to glyph $glyph".format(c))
                entries.add(c, glyph)
            }
        }
    }

    fun variationSelectors(): List<VariationSelector> {
        val subtable =
            subtables.firstOrNull { it.platform == PLATFORM_UNICODE && it.encoding == 5 }
                ?: return emptyList()
        val data = subtable.data
        if (subtable.format != 14) malformed("variation sequences in format ${subtable.format}")
        val count = data.u32(6)
        if (count > MAX_SELECTORS) malformed("$count variation selectors")
        var budget = MAX_VARIATION_SEQUENCES
        var previous = -1
        val selectors = ArrayList<VariationSelector>()
        for (i in 0 until count.toInt()) {
            val record = 10 + i * 11
            val selector = data.u24(record)
            if (!isVariationSelector(selector))
                malformed("variation selector U+%X".format(selector))
            if (selector <= previous) malformed("variation selectors not in order")
            previous = selector
            val defaultOffset = data.index32(record + 3)
            val nonDefaultOffset = data.index32(record + 7)

            val defaults = MappingCollector(budget, keepNotdef = true)
            if (defaultOffset != 0) {
                val table = data.from(defaultOffset)
                val ranges = table.u32(0)
                if (ranges > table.length / 4) malformed("variation ranges beyond the table")
                for (r in 0 until ranges.toInt()) {
                    val start = table.u24(4 + r * 4)
                    val end = start + table.u8(4 + r * 4 + 3)
                    if (end > MAX_CODE_POINT) malformed("variation range beyond Unicode")
                    for (c in start..end) defaults.add(c, 0)
                }
            }
            budget -= defaults.visited

            val nonDefaults = MappingCollector(budget, keepNotdef = false)
            if (nonDefaultOffset != 0) {
                val table = data.from(nonDefaultOffset)
                val mappings = table.u32(0)
                if (mappings > table.length / 5) malformed("variation mappings beyond the table")
                for (m in 0 until mappings.toInt()) {
                    val c = table.u24(4 + m * 5)
                    val glyph = table.u16(4 + m * 5 + 3)
                    if (c > MAX_CODE_POINT) malformed("variation sequence beyond Unicode")
                    if (glyph >= numGlyphs) malformed("variation sequence to glyph $glyph")
                    nonDefaults.add(c, glyph)
                }
            }
            budget -= nonDefaults.visited

            val defaultList = distinct(defaults.sorted())
            val nonDefaultList = distinct(nonDefaults.sorted())
            if (defaultList.isEmpty() && nonDefaultList.isEmpty()) continue
            selectors +=
                VariationSelector(
                    selector,
                    IntArray(defaultList.size) { (defaultList[it] ushr 32).toInt() },
                    IntArray(nonDefaultList.size) { (nonDefaultList[it] ushr 32).toInt() },
                    IntArray(nonDefaultList.size) { nonDefaultList[it].toInt() },
                )
        }
        return selectors
    }

    /** [sorted] without repeated code points; the same code point with two glyphs is malformed. */
    private fun distinct(sorted: LongArray): LongArray {
        if (sorted.isEmpty()) return sorted
        val out = LongArray(sorted.size)
        var n = 0
        for (entry in sorted) {
            if (n > 0 && entry ushr 32 == out[n - 1] ushr 32) {
                if (entry != out[n - 1]) malformed("variation sequence with two glyphs")
                continue
            }
            out[n++] = entry
        }
        return out.copyOf(n)
    }

    /**
     * Collects mappings of code points to glyphs as `codePoint shl 32 or glyph`, refusing to visit
     * more than [limit], so that overlapping ranges cannot make the work grow beyond the code
     * points of Unicode. Surrogates are dropped: text never has them as code points, so nothing
     * could use them. Glyph 0, `.notdef`, draws nothing that an unmapped code point would not, so
     * it is dropped too, unless [keepNotdef]: the defaults of variation sequences carry no glyph.
     */
    private class MappingCollector(private val limit: Int, private val keepNotdef: Boolean) {
        private var entries = LongArray(256)
        private var size = 0
        var visited = 0
            private set

        fun add(codePoint: Int, glyph: Int) {
            if (visited == limit) tooComplex("more than $limit mapped code points")
            visited++
            if (codePoint in 0xD800..0xDFFF || (glyph == 0 && !keepNotdef)) return
            if (size == entries.size) entries = entries.copyOf(size * 2)
            entries[size++] = (codePoint.toLong() shl 32) or glyph.toLong()
        }

        fun sorted(): LongArray = entries.copyOf(size).also { it.sort() }
    }

    companion object {
        private const val PLATFORM_UNICODE = 0
        private const val PLATFORM_WINDOWS = 3

        const val MAX_CODE_POINT = 0x10FFFF

        /** Every code point once: more means that the mappings overlap. */
        private const val MAX_CODE_POINTS = MAX_CODE_POINT + 1

        /** The number of variation selectors that Unicode has. */
        private const val MAX_SELECTORS = 3 + 1 + 16 + 240

        /**
         * The most variation sequences of all selectors together. Fonts with the Ideographic
         * Variation Database have tens of thousands.
         */
        private const val MAX_VARIATION_SEQUENCES = 1 shl 20

        fun isVariationSelector(c: Int): Boolean =
            c in 0x180B..0x180D || c == 0x180F || c in 0xFE00..0xFE0F || c in 0xE0100..0xE01EF
    }
}
