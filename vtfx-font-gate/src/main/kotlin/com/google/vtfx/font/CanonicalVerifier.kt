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

/** What [CanonicalVerifier] learned of a font that it accepted. */
internal class Verified(val widthClass: WidthClass)

/**
 * Checks that bytes are a font in the canonical form of docs/decisions/0040, exactly: every byte of
 * the file is either checked against a fixed value, checked against a value computed from other
 * parts of the file, or a number within a checked range. Only bytes that pass reach the platform's
 * native font code, so this module is under security review; the readers and the writer, in
 * vtfx-font, which this shares no code with, are not.
 *
 * Everything that it rejects is [InvalidFontException.Kind.Malformed], except a file larger than
 * [FontLimits.maxOutputBytes], which is [InvalidFontException.Kind.TooLarge]. Every read is checked
 * against the bounds of the file first, so no input ends in an exception of the JVM.
 */
internal object CanonicalVerifier {
    fun verify(font: ByteArray, limits: FontLimits): Verified {
        if (font.size > limits.maxOutputBytes) tooLarge("font of ${font.size} bytes")
        return FontCheck(font, limits).run()
    }
}

/** The tags of the tables of the canonical form, in the order of their tags. */
private val TAGS =
    intArrayOf(
        0x4F532F32, // OS/2
        0x636D6170, // cmap
        0x676C7966, // glyf
        0x68656164, // head
        0x68686561, // hhea
        0x686D7478, // hmtx
        0x6C6F6361, // loca
        0x6D617870, // maxp
        0x6E616D65, // name
        0x706F7374, // post
    )

private const val OS2 = 0
private const val CMAP = 1
private const val GLYF = 2
private const val HEAD = 3
private const val HHEA = 4
private const val HMTX = 5
private const val LOCA = 6
private const val MAXP = 7
private const val NAME = 8
private const val POST = 9

private const val DIRECTORY_END = 12 + 16 * 10
private const val CHECKSUM_MAGIC = -0x4E4F5046 // 0xB1B0AFBA
private const val MIN_COORDINATE = -16384
private const val MAX_COORDINATE = 16383
private const val MAX_CODE_POINT = 0x10FFFF
private const val MAX_ITALIC_ANGLE = 60 shl 16

private fun malformed(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.Malformed, message)

private fun tooLarge(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.TooLarge, message)

private class FontCheck(private val font: ByteArray, private val limits: FontLimits) {
    private val offsets = IntArray(TAGS.size)
    private val lengths = IntArray(TAGS.size)

    private var unitsPerEm = 0
    private var macStyle = 0
    private var numGlyphs = 0
    private var ascender = 0
    private var descender = 0
    private var lineGap = 0

    // Computed from glyf.
    private lateinit var nonEmpty: BooleanArray
    private lateinit var glyphXMin: IntArray
    private lateinit var glyphXMax: IntArray
    private lateinit var advances: IntArray
    private var anyOutline = false
    private var xMin = 0
    private var yMin = 0
    private var xMax = 0
    private var yMax = 0
    private var maxPoints = 0
    private var maxContours = 0

    // Computed from cmap.
    private var firstCodePoint = 0
    private var lastCodePoint = 0
    private var widthClass = WidthClass.Proportional

    fun run(): Verified {
        checkDirectory()
        checkHead()
        checkMaxp()
        checkGlyf()
        checkHhea()
        checkHmtx()
        checkCmap()
        checkOs2()
        checkName()
        checkPost()
        return Verified(widthClass)
    }

    private fun fail(message: String): Nothing = malformed("not canonical: $message")

    /**
     * Fails with [message] unless [condition] holds. The message is built only on failure: checks
     * run for every byte and point of a font, and building their messages each time made checking a
     * CJK font take several times as long, and allocate gigabytes.
     */
    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) fail(message())
    }

    private fun u8(at: Int): Int {
        expect(at >= 0 && at < font.size) { "read at $at" }
        return font[at].toInt() and 0xFF
    }

    private fun u16(at: Int): Int {
        expect(at >= 0 && at <= font.size - 2) { "read at $at" }
        return ((font[at].toInt() and 0xFF) shl 8) or (font[at + 1].toInt() and 0xFF)
    }

    private fun s16(at: Int): Int = u16(at).toShort().toInt()

    private fun u24(at: Int): Int = (u8(at) shl 16) or u16(at + 1)

    private fun s32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)

    private fun field(table: Int, at: Int, expected: Int, name: String) {
        expect(u16(offsets[table] + at) == expected) { name }
    }

    private fun field32(table: Int, at: Int, expected: Int, name: String) {
        expect(s32(offsets[table] + at) == expected) { name }
    }

    /** The header, the directory, the layout of the tables, and every checksum. */
    private fun checkDirectory() {
        expect(font.size >= DIRECTORY_END) { "file too short" }
        expect(s32(0) == 0x00010000) { "sfnt version" }
        expect(u16(4) == TAGS.size) { "number of tables" }
        expect(u16(6) == 128 && u16(8) == 3 && u16(10) == 32) { "search fields" }
        var expectedOffset = DIRECTORY_END
        for (i in TAGS.indices) {
            val record = 12 + i * 16
            expect(s32(record) == TAGS[i]) { "table $i tag" }
            val offset = s32(record + 8)
            val length = s32(record + 12)
            expect(offset == expectedOffset) { "table $i offset" }
            // offset is at most the file size here, so the subtraction cannot overflow.
            expect(length >= 0 && length <= font.size - offset) { "table $i length" }
            val padded = (length + 3) and 3.inv()
            expect(padded <= font.size - offset) { "table $i padding" }
            for (p in offset + length until offset + padded) {
                expect(font[p].toInt() == 0) { "padding" }
            }
            offsets[i] = offset
            lengths[i] = length
            expectedOffset = offset + padded
        }
        expect(expectedOffset == font.size) { "data after the tables" }
        for (i in TAGS.indices) {
            val record = 12 + i * 16
            expect(s32(record + 4) == checksum(offsets[i], lengths[i], i == HEAD)) {
                "table $i checksum"
            }
        }
        val adjustment = s32(offsets[HEAD] + 8)
        expect(adjustment == CHECKSUM_MAGIC - checksum(0, font.size, true)) { "checksumAdjustment" }
    }

    /**
     * The sum of the words of [length] bytes from [offset], padded with zeros; with
     * [skipAdjustment], without `head.checksumAdjustment`.
     */
    private fun checksum(offset: Int, length: Int, skipAdjustment: Boolean): Int {
        val adjustment = offsets[HEAD] + 8
        var sum = 0
        var i = offset
        while (i < offset + length) {
            if (!(skipAdjustment && i == adjustment)) {
                var word = 0
                for (j in 0 until 4) {
                    val at = i + j
                    word =
                        (word shl 8) or (if (at < offset + length) font[at].toInt() and 0xFF else 0)
                }
                sum += word
            }
            i += 4
        }
        return sum
    }

    private fun checkHead() {
        expect(lengths[HEAD] == 54) { "head length" }
        field32(HEAD, 0, 0x00010000, "head version")
        field32(HEAD, 4, 0x00010000, "fontRevision")
        field32(HEAD, 12, 0x5F0F3CF5, "head magic")
        field(HEAD, 16, 0x000B, "head flags")
        unitsPerEm = u16(offsets[HEAD] + 18)
        expect(unitsPerEm in 16..16384) { "unitsPerEm" }
        for (at in 20 until 36 step 2) field(HEAD, at, 0, "dates")
        macStyle = u16(offsets[HEAD] + 44)
        expect(macStyle and 3.inv() == 0) { "macStyle" }
        field(HEAD, 46, 8, "lowestRecPPEM")
        field(HEAD, 48, 2, "fontDirectionHint")
        field(HEAD, 50, 1, "indexToLocFormat")
        field(HEAD, 52, 0, "glyphDataFormat")
        // The bounding box at 36 is checked against the glyphs in checkGlyf.
    }

    private fun checkMaxp() {
        expect(lengths[MAXP] == 32) { "maxp length" }
        field32(MAXP, 0, 0x00010000, "maxp version")
        numGlyphs = u16(offsets[MAXP] + 4)
        expect(numGlyphs >= 1) { "no glyphs" }
        // maxPoints and maxContours, at 6 and 8, are checked against the glyphs in checkGlyf.
        for (at in 10 until 32 step 2) field(MAXP, at, if (at == 14) 1 else 0, "maxp field $at")
    }

    private fun checkGlyf() {
        expect(lengths[LOCA] == 4 * (numGlyphs + 1)) { "loca length" }
        nonEmpty = BooleanArray(numGlyphs)
        glyphXMin = IntArray(numGlyphs)
        glyphXMax = IntArray(numGlyphs)
        val loca = offsets[LOCA]
        var previous = 0
        expect(s32(loca) == 0) { "first loca offset" }
        for (glyph in 0 until numGlyphs) {
            val start = previous
            val end = s32(loca + (glyph + 1) * 4)
            expect(end >= start && end <= lengths[GLYF]) { "loca offset ${glyph + 1}" }
            expect(end % 4 == 0) { "loca alignment" }
            if (end > start) checkGlyph(glyph, offsets[GLYF] + start, end - start)
            previous = end
        }
        expect(previous == lengths[GLYF]) { "glyf length" }
        val head = offsets[HEAD]
        expect(
            s16(head + 36) == xMin &&
                s16(head + 38) == yMin &&
                s16(head + 40) == xMax &&
                s16(head + 42) == yMax
        ) {
            "head bounding box"
        }
        field(MAXP, 6, maxPoints, "maxPoints")
        field(MAXP, 8, maxContours, "maxContours")
    }

    /** A simple glyph of [length] bytes at [start], a multiple of 4. */
    private fun checkGlyph(glyph: Int, start: Int, length: Int) {
        val end = start + length
        val contours = s16(start)
        expect(contours >= 1) { "glyph $glyph contours" }
        var lastEnd = -1
        for (c in 0 until contours) {
            val endPoint = u16(start + 10 + c * 2)
            expect(endPoint > lastEnd) { "glyph $glyph end points" }
            lastEnd = endPoint
        }
        val points = lastEnd + 1
        expect(points <= limits.maxGlyphPoints) { "glyph $glyph points" }
        var at = start + 10 + contours * 2
        expect(u16(at) == 0) { "glyph $glyph instructions" }
        at += 2

        val flags = IntArray(points)
        val flagsStart = at
        var p = 0
        while (p < points) {
            expect(at < end) { "glyph $glyph flags" }
            val flag = u8(at++)
            expect(flag and 0x3F.inv() == 0) { "glyph $glyph flag bits" }
            flags[p++] = flag
            if (flag and 0x08 != 0) {
                expect(at < end) { "glyph $glyph repeat" }
                val count = u8(at++)
                expect(count <= points - p) { "glyph $glyph repeat count" }
                for (k in 0 until count) flags[p++] = flag
            }
        }
        val flagsEnd = at
        val xs = IntArray(points)
        at = readCoordinates(glyph, flags, 0x02, 0x10, at, end, xs)
        val ys = IntArray(points)
        at = readCoordinates(glyph, flags, 0x04, 0x20, at, end, ys)
        expect((at + 3 - start) and 3.inv() == length) { "glyph $glyph length" }
        for (k in at until end) expect(font[k].toInt() == 0) { "glyph $glyph padding" }
        checkFlagEncoding(glyph, flags, xs, ys, flagsStart, flagsEnd)

        val boxXMin = xs.min()
        val boxYMin = ys.min()
        val boxXMax = xs.max()
        val boxYMax = ys.max()
        expect(
            s16(start + 2) == boxXMin &&
                s16(start + 4) == boxYMin &&
                s16(start + 6) == boxXMax &&
                s16(start + 8) == boxYMax
        ) {
            "glyph $glyph bounding box"
        }
        nonEmpty[glyph] = true
        glyphXMin[glyph] = boxXMin
        glyphXMax[glyph] = boxXMax
        if (!anyOutline) {
            xMin = boxXMin
            yMin = boxYMin
            xMax = boxXMax
            yMax = boxYMax
            anyOutline = true
        } else {
            xMin = minOf(xMin, boxXMin)
            yMin = minOf(yMin, boxYMin)
            xMax = maxOf(xMax, boxXMax)
            yMax = maxOf(yMax, boxYMax)
        }
        maxPoints = maxOf(maxPoints, points)
        maxContours = maxOf(maxContours, contours)
    }

    /**
     * Checks that the flags of a glyph, from [start] to [end], are encoded in the one way that the
     * canonical form allows: each coordinate in its shortest form, and runs of three or more equal
     * flags, up to 256, with a repeat count. So a font has one canonical encoding, and sanitizing a
     * canonical font gives the same bytes.
     */
    private fun checkFlagEncoding(
        glyph: Int,
        flags: IntArray,
        xs: IntArray,
        ys: IntArray,
        start: Int,
        end: Int,
    ) {
        val expected = IntArray(flags.size)
        for (p in flags.indices) {
            val dx = xs[p] - (if (p == 0) 0 else xs[p - 1])
            val dy = ys[p] - (if (p == 0) 0 else ys[p - 1])
            expected[p] =
                (flags[p] and 0x01) or shortest(dx, 0x02, 0x10) or shortest(dy, 0x04, 0x20)
        }
        var at = start
        var p = 0
        while (p < expected.size) {
            var run = 1
            while (p + run < expected.size && expected[p + run] == expected[p] && run < 256) run++
            if (run >= 3) {
                expect(u8(at) == expected[p] or 0x08 && u8(at + 1) == run - 1) {
                    "glyph $glyph flags"
                }
                at += 2
                p += run
            } else {
                expect(u8(at) == expected[p]) { "glyph $glyph flags" }
                at++
                p++
            }
        }
        expect(at == end) { "glyph $glyph flags" }
    }

    private fun shortest(delta: Int, short: Int, sameOrPositive: Int): Int =
        when {
            delta == 0 -> sameOrPositive
            delta in -255..255 -> short or (if (delta > 0) sameOrPositive else 0)
            else -> 0
        }

    /** Reads one axis of coordinates into [out], each within range; returns the end. */
    private fun readCoordinates(
        glyph: Int,
        flags: IntArray,
        short: Int,
        sameOrPositive: Int,
        start: Int,
        end: Int,
        out: IntArray,
    ): Int {
        var at = start
        var value = 0
        for (p in flags.indices) {
            val flag = flags[p]
            if (flag and short != 0) {
                expect(at < end) { "glyph $glyph coordinates" }
                val delta = u8(at++)
                value += if (flag and sameOrPositive != 0) delta else -delta
            } else if (flag and sameOrPositive == 0) {
                expect(at + 2 <= end) { "glyph $glyph coordinates" }
                value += s16(at)
                at += 2
            }
            expect(value in MIN_COORDINATE..MAX_COORDINATE) { "glyph $glyph coordinate range" }
            out[p] = value
        }
        return at
    }

    private fun checkHhea() {
        expect(lengths[HHEA] == 36) { "hhea length" }
        val hhea = offsets[HHEA]
        field32(HHEA, 0, 0x00010000, "hhea version")
        ascender = s16(hhea + 4)
        descender = s16(hhea + 6)
        lineGap = s16(hhea + 8)
        expect(ascender in 1..2 * unitsPerEm) { "ascender" }
        expect(descender in -2 * unitsPerEm..0) { "descender" }
        expect(lineGap in 0..unitsPerEm) { "lineGap" }
        expect(lengths[HMTX] == 4 * numGlyphs) { "hmtx length" }
        advances = IntArray(numGlyphs) { u16(offsets[HMTX] + it * 4) }
        var minLeft = 0
        var minRight = 0
        var maxExtent = 0
        var any = false
        for (glyph in 0 until numGlyphs) {
            if (!nonEmpty[glyph]) continue
            val left = glyphXMin[glyph]
            val right = advances[glyph] - glyphXMax[glyph]
            val extent = glyphXMax[glyph]
            minLeft = if (any) minOf(minLeft, left) else left
            minRight = if (any) minOf(minRight, right) else right
            maxExtent = if (any) maxOf(maxExtent, extent) else extent
            any = true
        }
        field(HHEA, 10, advances.max(), "advanceWidthMax")
        expect(s16(hhea + 12) == minLeft) { "minLeftSideBearing" }
        expect(s16(hhea + 14) == minRight) { "minRightSideBearing" }
        expect(s16(hhea + 16) == maxExtent) { "xMaxExtent" }
        field(HHEA, 18, 1, "caretSlopeRise")
        for (at in 20 until 34 step 2) field(HHEA, at, 0, "hhea field $at")
        field(HHEA, 34, numGlyphs, "numberOfHMetrics")
    }

    private fun checkHmtx() {
        for (glyph in 0 until numGlyphs) {
            expect(advances[glyph] <= 4 * unitsPerEm) { "advance of glyph $glyph" }
            val leftSideBearing = s16(offsets[HMTX] + glyph * 4 + 2)
            val expected = if (nonEmpty[glyph]) glyphXMin[glyph] else 0
            expect(leftSideBearing == expected) { "left side bearing of glyph $glyph" }
        }
    }

    private fun checkCmap() {
        val cmap = offsets[CMAP]
        field(CMAP, 0, 0, "cmap version")
        val tables = u16(cmap + 2)
        val format12: Int
        if (tables == 1) {
            expect(u16(cmap + 4) == 3 && u16(cmap + 6) == 10) { "cmap record" }
            expect(s32(cmap + 8) == 12) { "cmap offset" }
            format12 = 12
        } else {
            expect(tables == 2) { "cmap records" }
            expect(u16(cmap + 4) == 0 && u16(cmap + 6) == 5) { "cmap variation record" }
            expect(s32(cmap + 8) == 20) { "cmap variation offset" }
            expect(u16(cmap + 12) == 3 && u16(cmap + 14) == 10) { "cmap record" }
            format12 = 20 + checkFormat14(cmap + 20)
            expect(s32(cmap + 16) == format12) { "cmap offset" }
        }
        val end = checkFormat12(cmap + format12)
        expect(end == cmap + lengths[CMAP]) { "cmap length" }
    }

    /** Checks the format 12 subtable at [start]; returns its end. */
    private fun checkFormat12(start: Int): Int {
        expect(u16(start) == 12 && u16(start + 2) == 0) { "format 12 header" }
        val groups = s32(start + 12)
        expect(groups >= 1 && groups <= (font.size - start) / 12) { "format 12 groups" }
        expect(s32(start + 4) == 16 + 12 * groups) { "format 12 length" }
        expect(s32(start + 8) == 0) { "format 12 language" }
        val widths = IntArray(2)
        var widthCount = 0
        var proportional = false
        var previousEnd = -2
        var previousGlyphEnd = -2
        for (g in 0 until groups) {
            val group = start + 16 + g * 12
            val first = s32(group)
            val last = s32(group + 4)
            val glyph = s32(group + 8)
            expect(first in 0..MAX_CODE_POINT && last in first..MAX_CODE_POINT) { "group range" }
            expect(last < 0xD800 || first > 0xDFFF) { "group with surrogates" }
            expect(first > previousEnd) { "groups not in order" }
            expect(glyph >= 1 && glyph < numGlyphs && last - first < numGlyphs - glyph) {
                "group glyphs"
            }
            expect(!(first == previousEnd + 1 && glyph == previousGlyphEnd + 1)) {
                "groups not merged"
            }
            if (g == 0) firstCodePoint = first
            lastCodePoint = last
            previousEnd = last
            previousGlyphEnd = glyph + (last - first)
            for (id in glyph..previousGlyphEnd) {
                val advance = advances[id]
                if (advance == 0 || proportional) continue
                when {
                    widthCount > 0 && widths[0] == advance -> Unit
                    widthCount > 1 && widths[1] == advance -> Unit
                    widthCount < 2 -> widths[widthCount++] = advance
                    else -> proportional = true
                }
            }
        }
        val narrow = minOf(widths[0], widths[1])
        val wide = maxOf(widths[0], widths[1])
        widthClass =
            when {
                proportional -> WidthClass.Proportional
                widthCount == 1 -> WidthClass.Monospaced
                widthCount == 2 && wide == 2 * narrow -> WidthClass.DualWidth
                else -> WidthClass.Proportional
            }
        return start + 16 + 12 * groups
    }

    /** Checks the format 14 subtable at [start]; returns its length. */
    private fun checkFormat14(start: Int): Int {
        expect(u16(start) == 14) { "format 14 header" }
        val length = s32(start + 2)
        val records = s32(start + 6)
        expect(records in 1..(3 + 1 + 16 + 240)) { "format 14 records" }
        // The tables follow the records, in the order of the records, each default table before
        // the non-default one.
        var expectedOffset = 10 + 11 * records
        var previousSelector = -1
        for (r in 0 until records) {
            val record = start + 10 + r * 11
            val selector = u24(record)
            expect(isVariationSelector(selector) && selector > previousSelector) { "selector" }
            previousSelector = selector
            val defaultOffset = s32(record + 3)
            val nonDefaultOffset = s32(record + 7)
            expect(defaultOffset != 0 || nonDefaultOffset != 0) { "selector without sequences" }
            if (defaultOffset != 0) {
                expect(defaultOffset == expectedOffset) { "default table offset" }
                expectedOffset += checkDefaultTable(start + defaultOffset)
            }
            if (nonDefaultOffset != 0) {
                expect(nonDefaultOffset == expectedOffset) { "non-default table offset" }
                expectedOffset += checkNonDefaultTable(start + nonDefaultOffset)
            }
        }
        expect(length == expectedOffset) { "format 14 length" }
        return length
    }

    /** Checks a default table of variation sequences at [start]; returns its length. */
    private fun checkDefaultTable(start: Int): Int {
        val ranges = s32(start)
        expect(ranges >= 1 && ranges <= (font.size - start) / 4) { "default ranges" }
        var previousEnd = -2
        var previousFull = false
        for (r in 0 until ranges) {
            val first = u24(start + 4 + r * 4)
            val count = u8(start + 4 + r * 4 + 3)
            val last = first + count
            expect(last <= MAX_CODE_POINT) { "default range" }
            expect(last < 0xD800 || first > 0xDFFF) { "default range with surrogates" }
            expect(first > previousEnd) { "default ranges not in order" }
            expect(first != previousEnd + 1 || previousFull) { "default ranges not merged" }
            previousEnd = last
            previousFull = count == 255
        }
        return 4 + 4 * ranges
    }

    /** Checks a non-default table of variation sequences at [start]; returns its length. */
    private fun checkNonDefaultTable(start: Int): Int {
        val mappings = s32(start)
        expect(mappings >= 1 && mappings <= (font.size - start) / 5) { "non-default mappings" }
        var previous = -1
        for (m in 0 until mappings) {
            val c = u24(start + 4 + m * 5)
            val glyph = u16(start + 4 + m * 5 + 3)
            expect(c > previous && c <= MAX_CODE_POINT && c !in 0xD800..0xDFFF) { "sequence base" }
            expect(glyph in 1 until numGlyphs) { "sequence glyph" }
            previous = c
        }
        return 4 + 5 * mappings
    }

    private fun isVariationSelector(c: Int): Boolean =
        c in 0x180B..0x180D || c == 0x180F || c in 0xFE00..0xFE0F || c in 0xE0100..0xE01EF

    private fun checkOs2() {
        expect(lengths[OS2] == 96) { "OS/2 length" }
        val os2 = offsets[OS2]
        val em = unitsPerEm
        field(OS2, 0, 4, "OS/2 version")
        var sum = 0L
        var count = 0
        for (advance in advances) {
            if (advance != 0) {
                sum += advance
                count++
            }
        }
        field(OS2, 2, if (count == 0) 0 else ((sum + count / 2) / count).toInt(), "xAvgCharWidth")
        expect(u16(os2 + 4) in 1..1000) { "usWeightClass" }
        expect(u16(os2 + 6) in 1..9) { "usWidthClass" }
        field(OS2, 8, 0, "fsType")
        field(OS2, 10, em * 65 / 100, "ySubscriptXSize")
        field(OS2, 12, em * 60 / 100, "ySubscriptYSize")
        field(OS2, 14, 0, "ySubscriptXOffset")
        field(OS2, 16, em * 14 / 100, "ySubscriptYOffset")
        field(OS2, 18, em * 65 / 100, "ySuperscriptXSize")
        field(OS2, 20, em * 60 / 100, "ySuperscriptYSize")
        field(OS2, 22, 0, "ySuperscriptXOffset")
        field(OS2, 24, em * 48 / 100, "ySuperscriptYOffset")
        field(OS2, 26, em * 5 / 100, "yStrikeoutSize")
        field(OS2, 28, em * 26 / 100, "yStrikeoutPosition")
        // sFamilyClass, panose, and ulUnicodeRange1 to 4.
        for (at in 30 until 58 step 2) field(OS2, at, 0, "OS/2 field $at")
        field32(OS2, 58, 0x76746678, "achVendID")
        val bold = macStyle and 1 != 0
        val italic = macStyle and 2 != 0
        val fsSelection =
            (if (italic) 0x01 else 0) or
                (if (bold) 0x20 else 0) or
                (if (!bold && !italic) 0x40 else 0) or
                0x80
        field(OS2, 62, fsSelection, "fsSelection")
        field(OS2, 64, minOf(firstCodePoint, 0xFFFF), "usFirstCharIndex")
        field(OS2, 66, minOf(lastCodePoint, 0xFFFF), "usLastCharIndex")
        expect(s16(os2 + 68) == ascender) { "sTypoAscender" }
        expect(s16(os2 + 70) == descender) { "sTypoDescender" }
        expect(s16(os2 + 72) == lineGap) { "sTypoLineGap" }
        field(OS2, 74, maxOf(yMax, 0), "usWinAscent")
        field(OS2, 76, maxOf(-yMin, 0), "usWinDescent")
        for (at in 78 until 86 step 2) field(OS2, at, 0, "ulCodePageRange")
        expect(s16(os2 + 86) in 0..2 * em) { "sxHeight" }
        expect(s16(os2 + 88) in 0..2 * em) { "sCapHeight" }
        field(OS2, 90, 0, "usDefaultChar")
        field(OS2, 92, 0x20, "usBreakChar")
        field(OS2, 94, 0, "usMaxContext")
    }

    /** The name table is fixed for each style, so it is compared with the bytes it must have. */
    private fun checkName() {
        val subfamily =
            when (macStyle) {
                1 -> "Bold"
                2 -> "Italic"
                3 -> "Bold Italic"
                else -> "Regular"
            }
        val strings =
            listOf("vtfx", subfamily, "vtfx $subfamily", "vtfx-" + subfamily.replace(" ", ""))
        val nameIds = intArrayOf(1, 2, 4, 6)
        val expected = java.io.ByteArrayOutputStream()
        fun put16(value: Int) {
            expected.write(value ushr 8)
            expected.write(value)
        }
        put16(0)
        put16(strings.size)
        put16(6 + 12 * strings.size)
        var offset = 0
        for (i in strings.indices) {
            for (value in intArrayOf(3, 1, 0x0409, nameIds[i], strings[i].length * 2, offset)) {
                put16(value)
            }
            offset += strings[i].length * 2
        }
        for (string in strings) string.forEach { put16(it.code) }
        val bytes = expected.toByteArray()
        expect(lengths[NAME] == bytes.size) { "name length" }
        for (i in bytes.indices) expect(font[offsets[NAME] + i] == bytes[i]) { "name byte $i" }
    }

    private fun checkPost() {
        expect(lengths[POST] == 32) { "post length" }
        val post = offsets[POST]
        field32(POST, 0, 0x00030000, "post version")
        val italicAngle = s32(post + 4)
        expect(italicAngle in -MAX_ITALIC_ANGLE..MAX_ITALIC_ANGLE) { "italicAngle" }
        expect(s16(post + 8) in -unitsPerEm..unitsPerEm) { "underlinePosition" }
        expect(s16(post + 10) in 0..unitsPerEm) { "underlineThickness" }
        field32(POST, 12, if (widthClass == WidthClass.Monospaced) 1 else 0, "isFixedPitch")
        for (at in 16 until 32 step 4) field32(POST, at, 0, "post memory")
    }
}
