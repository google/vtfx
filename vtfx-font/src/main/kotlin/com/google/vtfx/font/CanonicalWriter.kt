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
import com.google.vtfx.font.gate.WidthClass

/**
 * Writes the canonical form of a face (docs/decisions/0040) from its [FaceParts]: every value is
 * either one of those, which [FaceParts.read] checked, or computed here from the outlines. The
 * verifier of vtfx-font-gate (`CanonicalVerifier`) checks the result; this writer does not need to
 * be trusted.
 */
internal object CanonicalWriter {
    /** The range of coordinates, which keeps every difference between two of them in 16 bits. */
    const val MIN_COORDINATE = -16384
    const val MAX_COORDINATE = 16383

    private const val REGULAR = "Regular"

    fun write(parts: FaceParts, limits: FontLimits): ByteArray {
        val numGlyphs = parts.numGlyphs
        val glyf = Sink(limits.maxOutputBytes)
        val loca = IntArray(numGlyphs + 1)
        val glyphs = GlyphStats(numGlyphs)
        for (glyphId in 0 until numGlyphs) {
            if (Thread.interrupted()) throw InterruptedException()
            loca[glyphId] = glyf.size
            val outline = parts.outlines.outline(glyphId)
            if (!outline.isEmpty) {
                val box = boundingBox(outline)
                writeGlyph(outline, box, glyf)
                glyphs.add(glyphId, outline, box, parts.advances[glyphId])
            }
        }
        loca[numGlyphs] = glyf.size

        val widthClass = widthClass(parts)
        val tables =
            listOf(
                Tag.OS2 to os2(parts, glyphs),
                Tag.CMAP to cmap(parts),
                Tag.GLYF to glyf,
                Tag.HEAD to head(parts, glyphs),
                Tag.HHEA to hhea(parts, glyphs),
                Tag.HMTX to hmtx(parts, glyphs),
                Tag.LOCA to Sink(Int.MAX_VALUE).apply { loca.forEach { u32(it) } },
                Tag.MAXP to maxp(numGlyphs, glyphs),
                Tag.NAME to name(parts.macStyle),
                Tag.POST to post(parts, widthClass),
            )
        return assemble(tables, limits)
    }

    /** What the tables other than `glyf` need to know of the outlines. */
    private class GlyphStats(numGlyphs: Int) {
        /** The left side bearing of each glyph: its xMin, or 0 if it is empty. */
        val xMin = IntArray(numGlyphs)
        var fontXMin = 0
        var fontYMin = 0
        var fontXMax = 0
        var fontYMax = 0
        var minRight = 0
        var maxPoints = 0
        var maxContours = 0
        private var any = false

        fun add(glyphId: Int, outline: Outline, box: IntArray, advance: Int) {
            val right = advance - box[2]
            xMin[glyphId] = box[0]
            if (!any) {
                fontXMin = box[0]
                fontYMin = box[1]
                fontXMax = box[2]
                fontYMax = box[3]
                minRight = right
                any = true
            } else {
                fontXMin = minOf(fontXMin, box[0])
                fontYMin = minOf(fontYMin, box[1])
                fontXMax = maxOf(fontXMax, box[2])
                fontYMax = maxOf(fontYMax, box[3])
                minRight = minOf(minRight, right)
            }
            maxPoints = maxOf(maxPoints, outline.pointCount)
            maxContours = maxOf(maxContours, outline.endPoints.size)
        }
    }

    /** xMin, yMin, xMax, yMax of the points. */
    private fun boundingBox(outline: Outline): IntArray {
        val box = intArrayOf(outline.xs[0], outline.ys[0], outline.xs[0], outline.ys[0])
        for (i in 1 until outline.pointCount) {
            box[0] = minOf(box[0], outline.xs[i])
            box[1] = minOf(box[1], outline.ys[i])
            box[2] = maxOf(box[2], outline.xs[i])
            box[3] = maxOf(box[3], outline.ys[i])
        }
        return box
    }

    private fun writeGlyph(outline: Outline, box: IntArray, out: Sink) {
        for (i in 0 until outline.pointCount) {
            if (
                outline.xs[i] !in MIN_COORDINATE..MAX_COORDINATE ||
                    outline.ys[i] !in MIN_COORDINATE..MAX_COORDINATE
            ) {
                unsupported("coordinate out of $MIN_COORDINATE..$MAX_COORDINATE")
            }
        }
        val start = out.size
        out.u16(outline.endPoints.size)
        box.forEach { out.u16(it) }
        outline.endPoints.forEach { out.u16(it) }
        out.u16(0) // no instructions

        val count = outline.pointCount
        val flags = IntArray(count)
        val dxs = IntArray(count)
        val dys = IntArray(count)
        for (i in 0 until count) {
            dxs[i] = outline.xs[i] - (if (i == 0) 0 else outline.xs[i - 1])
            dys[i] = outline.ys[i] - (if (i == 0) 0 else outline.ys[i - 1])
            flags[i] =
                (if (outline.onCurve[i]) ON_CURVE else 0) or
                    deltaFlag(dxs[i], X_SHORT, X_SAME_OR_POSITIVE) or
                    deltaFlag(dys[i], Y_SHORT, Y_SAME_OR_POSITIVE)
        }
        var i = 0
        while (i < count) {
            var run = 1
            while (i + run < count && flags[i + run] == flags[i] && run <= 255) run++
            // A run of three or more is shorter with a repeat count.
            if (run >= 3) {
                out.u8(flags[i] or REPEAT)
                out.u8(run - 1)
            } else {
                run = 1
                out.u8(flags[i])
            }
            i += run
        }
        writeDeltas(dxs, flags, X_SHORT, X_SAME_OR_POSITIVE, out)
        writeDeltas(dys, flags, Y_SHORT, Y_SAME_OR_POSITIVE, out)
        while ((out.size - start) % 4 != 0) out.u8(0)
    }

    private fun deltaFlag(delta: Int, short: Int, sameOrPositive: Int): Int =
        when {
            delta == 0 -> sameOrPositive
            delta in -255..255 -> short or (if (delta > 0) sameOrPositive else 0)
            else -> 0
        }

    private fun writeDeltas(deltas: IntArray, flags: IntArray, short: Int, same: Int, out: Sink) {
        for (i in deltas.indices) {
            when {
                flags[i] and short != 0 -> out.u8(kotlin.math.abs(deltas[i]))
                flags[i] and same != 0 -> Unit
                else -> out.u16(deltas[i])
            }
        }
    }

    /** The width class of the characters that the font maps, which `post` and the API report. */
    private fun widthClass(parts: FaceParts): WidthClass {
        val widths = sortedSetOf<Int>()
        for (glyph in parts.charMap.glyphs) {
            val advance = parts.advances[glyph]
            if (advance != 0) widths += advance
            if (widths.size > 2) return WidthClass.Proportional
        }
        return when {
            widths.size == 1 -> WidthClass.Monospaced
            widths.size == 2 && widths.last() == 2 * widths.first() -> WidthClass.DualWidth
            else -> WidthClass.Proportional
        }
    }

    private fun os2(parts: FaceParts, glyphs: GlyphStats): Sink {
        val em = parts.unitsPerEm
        val nonZero = parts.advances.filter { it != 0 }
        val average =
            if (nonZero.isEmpty()) 0
            else (nonZero.sumOf { it.toLong() } + nonZero.size / 2) / nonZero.size
        val bold = parts.macStyle and FaceParts.BOLD != 0
        val italic = parts.macStyle and FaceParts.ITALIC != 0
        val fsSelection =
            (if (italic) FS_ITALIC else 0) or
                (if (bold) FS_BOLD else 0) or
                (if (!bold && !italic) FS_REGULAR else 0) or
                FS_USE_TYPO_METRICS
        val codePoints = parts.charMap.codePoints
        return Sink(Int.MAX_VALUE).apply {
            u16(4) // version
            u16(average.toInt())
            u16(parts.weightClass)
            u16(parts.widthClass)
            u16(0) // fsType: no restrictions on embedding
            u16(em * 65 / 100) // ySubscriptXSize
            u16(em * 60 / 100) // ySubscriptYSize
            u16(0) // ySubscriptXOffset
            u16(em * 14 / 100) // ySubscriptYOffset
            u16(em * 65 / 100) // ySuperscriptXSize
            u16(em * 60 / 100) // ySuperscriptYSize
            u16(0) // ySuperscriptXOffset
            u16(em * 48 / 100) // ySuperscriptYOffset
            u16(em * 5 / 100) // yStrikeoutSize
            u16(em * 26 / 100) // yStrikeoutPosition
            u16(0) // sFamilyClass
            repeat(10) { u8(0) } // panose
            repeat(4) { u32(0) } // ulUnicodeRange1 to 4
            u32(VENDOR)
            u16(fsSelection)
            u16(minOf(codePoints.first(), 0xFFFF))
            u16(minOf(codePoints.last(), 0xFFFF))
            u16(parts.ascender)
            u16(parts.descender)
            u16(parts.lineGap)
            u16(maxOf(glyphs.fontYMax, 0)) // usWinAscent
            u16(maxOf(-glyphs.fontYMin, 0)) // usWinDescent
            u32(0) // ulCodePageRange1
            u32(0) // ulCodePageRange2
            u16(parts.xHeight)
            u16(parts.capHeight)
            u16(0) // usDefaultChar
            u16(0x20) // usBreakChar
            u16(0) // usMaxContext
        }
    }

    private fun cmap(parts: FaceParts): Sink {
        val variations = parts.variationSelectors
        val format14 = if (variations.isEmpty()) null else format14(variations)
        val format12 = format12(parts.charMap)
        val out = Sink(Int.MAX_VALUE)
        out.u16(0) // version
        if (format14 == null) {
            out.u16(1)
            out.u16(3).u16(10).u32(12)
        } else {
            // Records are sorted by platform and encoding, and the subtables follow in their order.
            out.u16(2)
            out.u16(0).u16(5).u32(20)
            out.u16(3).u16(10).u32(20 + format14.size)
            out.bytes(format14)
        }
        out.bytes(format12)
        return out
    }

    private fun format12(charMap: CharMap): ByteArray {
        val groups = Sink(Int.MAX_VALUE)
        var count = 0
        val codePoints = charMap.codePoints
        val glyphs = charMap.glyphs
        var i = 0
        while (i < codePoints.size) {
            var j = i
            while (
                j + 1 < codePoints.size &&
                    codePoints[j + 1] == codePoints[j] + 1 &&
                    glyphs[j + 1] == glyphs[j] + 1
            ) {
                j++
            }
            groups.u32(codePoints[i]).u32(codePoints[j]).u32(glyphs[i])
            count++
            i = j + 1
        }
        return Sink(Int.MAX_VALUE)
            .u16(12)
            .u16(0)
            .u32(16 + groups.size)
            .u32(0) // language
            .u32(count)
            .bytes(groups.toByteArray())
            .toByteArray()
    }

    private fun format14(selectors: List<VariationSelector>): ByteArray {
        val tables = Sink(Int.MAX_VALUE)
        val records = Sink(Int.MAX_VALUE)
        val headerSize = 10 + 11 * selectors.size
        for (selector in selectors) {
            records.u24(selector.selector)
            if (selector.defaults.isEmpty()) {
                records.u32(0)
            } else {
                records.u32(headerSize + tables.size)
                val ranges = Sink(Int.MAX_VALUE)
                var count = 0
                val defaults = selector.defaults
                var i = 0
                while (i < defaults.size) {
                    var j = i
                    // One range holds at most 256 code points.
                    while (
                        j + 1 < defaults.size &&
                            defaults[j + 1] == defaults[j] + 1 &&
                            j + 1 - i < 256
                    ) {
                        j++
                    }
                    ranges.u24(defaults[i]).u8(j - i)
                    count++
                    i = j + 1
                }
                tables.u32(count).bytes(ranges.toByteArray())
            }
            if (selector.baseCharacters.isEmpty()) {
                records.u32(0)
            } else {
                records.u32(headerSize + tables.size)
                tables.u32(selector.baseCharacters.size)
                for (k in selector.baseCharacters.indices) {
                    tables.u24(selector.baseCharacters[k]).u16(selector.glyphs[k])
                }
            }
        }
        return Sink(Int.MAX_VALUE)
            .u16(14)
            .u32(headerSize + tables.size)
            .u32(selectors.size)
            .bytes(records.toByteArray())
            .bytes(tables.toByteArray())
            .toByteArray()
    }

    private fun head(parts: FaceParts, glyphs: GlyphStats): Sink =
        Sink(Int.MAX_VALUE)
            .u32(0x00010000) // version
            .u32(0x00010000) // fontRevision
            .u32(0) // checksumAdjustment, set by assemble
            .u32(HEAD_MAGIC)
            .u16(HEAD_FLAGS)
            .u16(parts.unitsPerEm)
            .u32(0)
            .u32(0) // created
            .u32(0)
            .u32(0) // modified
            .u16(glyphs.fontXMin)
            .u16(glyphs.fontYMin)
            .u16(glyphs.fontXMax)
            .u16(glyphs.fontYMax)
            .u16(parts.macStyle)
            .u16(8) // lowestRecPPEM
            .u16(2) // fontDirectionHint
            .u16(1) // indexToLocFormat: long offsets
            .u16(0) // glyphDataFormat

    private fun hhea(parts: FaceParts, glyphs: GlyphStats): Sink {
        // Advances reach 65535 and coordinates -16384, so the right side bearings may not fit.
        if (glyphs.minRight > Short.MAX_VALUE) unsupported("right side bearings out of range")
        return Sink(Int.MAX_VALUE)
            .u32(0x00010000) // version
            .u16(parts.ascender)
            .u16(parts.descender)
            .u16(parts.lineGap)
            .u16(parts.advances.max())
            // The left side bearing and the extent of a glyph are its xMin and xMax.
            .u16(glyphs.fontXMin)
            .u16(glyphs.minRight)
            .u16(glyphs.fontXMax)
            .u16(1) // caretSlopeRise
            .u16(0) // caretSlopeRun
            .u16(0) // caretOffset
            .u32(0)
            .u32(0) // reserved
            .u16(0) // metricDataFormat
            .u16(parts.numGlyphs)
    }

    private fun hmtx(parts: FaceParts, glyphs: GlyphStats): Sink {
        val out = Sink(Int.MAX_VALUE)
        for (glyphId in 0 until parts.numGlyphs) {
            out.u16(parts.advances[glyphId])
            out.u16(glyphs.xMin[glyphId])
        }
        return out
    }

    private fun maxp(numGlyphs: Int, glyphs: GlyphStats): Sink =
        Sink(Int.MAX_VALUE)
            .u32(0x00010000) // version
            .u16(numGlyphs)
            .u16(glyphs.maxPoints)
            .u16(glyphs.maxContours)
            .u16(0) // maxCompositePoints
            .u16(0) // maxCompositeContours
            .u16(1) // maxZones
            .u16(0) // maxTwilightPoints
            .u16(0) // maxStorage
            .u16(0) // maxFunctionDefs
            .u16(0) // maxInstructionDefs
            .u16(0) // maxStackElements
            .u16(0) // maxSizeOfInstructions
            .u16(0) // maxComponentElements
            .u16(0) // maxComponentDepth

    private fun name(macStyle: Int): Sink {
        val subfamily =
            when (macStyle) {
                FaceParts.BOLD -> "Bold"
                FaceParts.ITALIC -> "Italic"
                FaceParts.BOLD or FaceParts.ITALIC -> "Bold Italic"
                else -> REGULAR
            }
        val strings =
            listOf(
                1 to FAMILY,
                2 to subfamily,
                4 to "$FAMILY $subfamily",
                6 to "$FAMILY-${subfamily.replace(" ", "")}",
            )
        val out = Sink(Int.MAX_VALUE).u16(0).u16(strings.size).u16(6 + 12 * strings.size)
        var offset = 0
        for ((nameId, string) in strings) {
            out.u16(3).u16(1).u16(0x0409).u16(nameId).u16(string.length * 2).u16(offset)
            offset += string.length * 2
        }
        for ((_, string) in strings) string.forEach { out.u16(it.code) }
        return out
    }

    private fun post(parts: FaceParts, widthClass: WidthClass): Sink =
        Sink(Int.MAX_VALUE)
            .u32(0x00030000) // version 3: no glyph names
            .u32(parts.italicAngle)
            .u16(parts.underlinePosition)
            .u16(parts.underlineThickness)
            .u32(if (widthClass == WidthClass.Monospaced) 1 else 0) // isFixedPitch
            .u32(0)
            .u32(0)
            .u32(0)
            .u32(0) // memory use when downloaded, unknown

    /**
     * The font file of [tables], in this order, with the directory and the checksums. The tables
     * are copied once, into the file, so that a large `glyf` is held twice at most.
     */
    private fun assemble(tables: List<Pair<Int, Sink>>, limits: FontLimits): ByteArray {
        var size = 12L + 16L * tables.size
        for ((_, data) in tables) size += align4(data.size.toLong())
        if (size > limits.maxOutputBytes) tooLarge("sanitized font of $size bytes")
        val out = ByteArray(size.toInt())
        val directory = Sink(Int.MAX_VALUE)
        directory.u32(0x00010000).u16(tables.size)
        // 16 times the largest power of two that is at most the number of tables, and so on.
        var power = 1
        var log = 0
        while (power * 2 <= tables.size) {
            power *= 2
            log++
        }
        directory.u16(power * 16).u16(log).u16(tables.size * 16 - power * 16)
        var offset = 12 + 16 * tables.size
        var headOffset = 0
        for ((tag, data) in tables) {
            data.copyInto(out, offset)
            directory.u32(tag).u32(checksum(out, offset, data.size)).u32(offset).u32(data.size)
            if (tag == Tag.HEAD) headOffset = offset
            offset += align4(data.size.toLong()).toInt()
        }
        directory.copyInto(out, 0)
        val adjustment = CHECKSUM_MAGIC - checksum(out, 0, out.size)
        out[headOffset + 8] = (adjustment ushr 24).toByte()
        out[headOffset + 9] = (adjustment ushr 16).toByte()
        out[headOffset + 10] = (adjustment ushr 8).toByte()
        out[headOffset + 11] = adjustment.toByte()
        return out
    }

    /**
     * The sum of the big-endian 32-bit words of the [length] bytes of [data] from [offset], padded
     * with zeros.
     */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        var sum = 0
        var i = offset
        while (i < end) {
            var word = 0
            for (j in 0 until 4) {
                word = (word shl 8) or (if (i + j < end) data[i + j].toInt() and 0xFF else 0)
            }
            sum += word
            i += 4
        }
        return sum
    }

    private fun align4(size: Long): Long = (size + 3) and 3L.inv()

    /**
     * Big-endian numbers written to a list of chunks, refusing more than [limit] bytes. Chunks are
     * never copied to grow, so writing a `glyf` of tens of megabytes holds it only once.
     */
    private class Sink(private val limit: Int) {
        private val full = ArrayList<ByteArray>()
        private var chunk = ByteArray(256)
        private var position = 0
        var size = 0
            private set

        private fun ensure(more: Int) {
            if (more > limit - size) tooLarge("sanitized font of more than $limit bytes")
        }

        private fun put(value: Int) {
            if (position == chunk.size) {
                full += chunk
                chunk = ByteArray(minOf(chunk.size * 2, MAX_CHUNK))
                position = 0
            }
            chunk[position++] = value.toByte()
            size++
        }

        fun u8(value: Int) = apply {
            ensure(1)
            put(value)
        }

        fun u16(value: Int) = apply {
            ensure(2)
            put(value ushr 8)
            put(value)
        }

        fun u24(value: Int) = apply {
            ensure(3)
            put(value ushr 16)
            put(value ushr 8)
            put(value)
        }

        fun u32(value: Int) = apply {
            ensure(4)
            put(value ushr 24)
            put(value ushr 16)
            put(value ushr 8)
            put(value)
        }

        fun bytes(value: ByteArray) = apply {
            ensure(value.size)
            for (byte in value) put(byte.toInt())
        }

        fun copyInto(destination: ByteArray, offset: Int) {
            var at = offset
            for (bytes in full) {
                bytes.copyInto(destination, at)
                at += bytes.size
            }
            chunk.copyInto(destination, at, 0, position)
        }

        fun toByteArray(): ByteArray = ByteArray(size).also { copyInto(it, 0) }

        private companion object {
            const val MAX_CHUNK = 1 shl 20
        }
    }

    private const val FAMILY = "vtfx"
    private const val VENDOR = 0x76746678 // 'vtfx'
    private const val HEAD_MAGIC = 0x5F0F3CF5

    /** Baseline at y = 0, left side bearing at x = 0, integer scaling. */
    private const val HEAD_FLAGS = 0x000B
    private const val CHECKSUM_MAGIC = -0x4E4F5046 // 0xB1B0AFBA

    private const val FS_ITALIC = 0x0001
    private const val FS_BOLD = 0x0020
    private const val FS_REGULAR = 0x0040
    private const val FS_USE_TYPO_METRICS = 0x0080

    private const val ON_CURVE = 0x01
    private const val X_SHORT = 0x02
    private const val Y_SHORT = 0x04
    private const val REPEAT = 0x08
    private const val X_SAME_OR_POSITIVE = 0x10
    private const val Y_SAME_OR_POSITIVE = 0x20
}
