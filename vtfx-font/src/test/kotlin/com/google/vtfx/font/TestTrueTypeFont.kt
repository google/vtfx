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

/** A point of a test glyph. */
data class P(val x: Int, val y: Int, val on: Boolean = true)

/** A glyph of a test font. */
sealed interface TestGlyph {
    val advance: Int

    /** A simple glyph; [leftSideBearing] defaults to the glyph's xMin, as fonts usually have. */
    class Simple(
        val contours: List<List<P>>,
        override val advance: Int = 500,
        val leftSideBearing: Int? = null,
        val instructions: ByteArray = ByteArray(0),
    ) : TestGlyph

    class Composite(
        val components: List<TestComponent>,
        override val advance: Int = 500,
        val leftSideBearing: Int = 0,
        val headerXMin: Int = 0,
    ) : TestGlyph

    class Empty(override val advance: Int = 500) : TestGlyph
}

/**
 * A component of a composite glyph, moved by [dx] and [dy]. [scale] is null, one number, two (x and
 * y), or four (a 2x2 matrix), each in F2Dot14.
 */
class TestComponent(
    val glyph: Int,
    val dx: Int,
    val dy: Int,
    val scale: List<Int> = emptyList(),
    val xyValues: Boolean = true,
)

/** A square contour, a common shape for tests. */
fun square(x: Int, y: Int, size: Int): List<P> =
    listOf(P(x, y), P(x, y + size), P(x + size, y + size), P(x + size, y))

/**
 * A font of many points in few bytes: glyph 1 has 16384 points at (0, 0), the most that one glyph
 * has by default, which the canonical form writes as repeated flags of two bytes for each 256, and
 * each of the [glyphs] - 1 glyphs after it has them again through one component; so its glyphs have
 * [glyphs] times 16384 points together.
 */
fun fontOfGlyphsAtTheOrigin(glyphs: Int): TestTrueTypeFont =
    TestTrueTypeFont(
        glyphs =
            List(glyphs + 1) {
                when (it) {
                    0 -> TestGlyph.Empty()
                    1 -> TestGlyph.Simple(listOf(List(16384) { P(0, 0) }))
                    else -> TestGlyph.Composite(listOf(TestComponent(1, 0, 0)))
                }
            }
    )

/**
 * Builds TrueType fonts for tests, with the tables a real font has. Each property can be changed to
 * make the font of a test.
 */
class TestTrueTypeFont(
    var glyphs: List<TestGlyph> =
        listOf(
            TestGlyph.Simple(listOf(square(50, 0, 400))),
            TestGlyph.Simple(listOf(square(100, 0, 300), square(150, 50, 200).reversed())),
        ),
    var characters: Map<Int, Int> = mapOf('A'.code to 1),
    var cmapFormat: Int = 12,
    var variations: Map<Int, Pair<List<Int>, Map<Int, Int>>> = emptyMap(),
    var unitsPerEm: Int = 1000,
    var ascender: Int = 800,
    var descender: Int = -200,
    var lineGap: Int = 0,
    var macStyle: Int = 0,
    var weightClass: Int = 400,
    var typoMetrics: Triple<Int, Int, Int>? = null,
    var xHeight: Int = 500,
    var capHeight: Int = 700,
    var italicAngle: Int = 0,
    var underlinePosition: Int = -100,
    var underlineThickness: Int = 50,
    var shortLoca: Boolean = false,
    var hasOs2: Boolean = true,
    var hasPost: Boolean = true,
    var extraTables: Map<String, ByteArray> = emptyMap(),
) {
    fun build(): ByteArray = TestSfnt.font(tables())

    fun tables(): Map<String, ByteArray> {
        val glyf = TestBytes()
        val offsets = ArrayList<Int>()
        val leftSideBearings = ArrayList<Int>()
        for (glyph in glyphs) {
            offsets += glyf.size
            when (glyph) {
                is TestGlyph.Simple -> {
                    val points = glyph.contours.flatten()
                    val xMin = points.minOf { it.x }
                    leftSideBearings += glyph.leftSideBearing ?: xMin
                    glyf.u16(glyph.contours.size)
                    glyf.u16(xMin).u16(points.minOf { it.y })
                    glyf.u16(points.maxOf { it.x }).u16(points.maxOf { it.y })
                    var end = -1
                    for (contour in glyph.contours) {
                        end += contour.size
                        glyf.u16(end)
                    }
                    glyf.u16(glyph.instructions.size).bytes(glyph.instructions)
                    // Every coordinate as a 16-bit delta, unlike the canonical form.
                    for (point in points) glyf.u8(if (point.on) 1 else 0)
                    var x = 0
                    for (point in points) {
                        glyf.u16(point.x - x)
                        x = point.x
                    }
                    var y = 0
                    for (point in points) {
                        glyf.u16(point.y - y)
                        y = point.y
                    }
                }
                is TestGlyph.Composite -> {
                    leftSideBearings += glyph.leftSideBearing
                    glyf.u16(-1).u16(glyph.headerXMin).u16(0).u16(0).u16(0)
                    for ((i, component) in glyph.components.withIndex()) {
                        val more = if (i < glyph.components.size - 1) 0x20 else 0
                        val scaleFlag =
                            when (component.scale.size) {
                                0 -> 0
                                1 -> 0x08
                                2 -> 0x40
                                else -> 0x80
                            }
                        val xy = if (component.xyValues) 0x0002 else 0
                        glyf.u16(0x0001 or xy or more or scaleFlag)
                        glyf.u16(component.glyph).u16(component.dx).u16(component.dy)
                        component.scale.forEach { glyf.u16(it) }
                    }
                }
                is TestGlyph.Empty -> leftSideBearings += 0
            }
            while (glyf.size % 4 != 0) glyf.u8(0)
        }
        offsets += glyf.size

        val loca = TestBytes()
        for (offset in offsets) if (shortLoca) loca.u16(offset / 2) else loca.u32(offset)
        val hmtx = TestBytes()
        for ((i, glyph) in glyphs.withIndex()) hmtx.u16(glyph.advance).u16(leftSideBearings[i])

        val tables =
            mutableMapOf(
                "head" to head(),
                "hhea" to hhea(),
                "maxp" to bytes { u32(0x00010000).u16(glyphs.size).bytes(ByteArray(26)) },
                "hmtx" to hmtx.toByteArray(),
                "loca" to loca.toByteArray(),
                "glyf" to glyf.toByteArray(),
                "cmap" to cmap(),
                "name" to TestSfnt.nameTable(listOf(windowsName(1, "Test"))),
            )
        if (hasOs2) tables["OS/2"] = os2()
        if (hasPost) tables["post"] = post()
        tables += extraTables
        return tables
    }

    private fun head() = bytes {
        u32(0x00010000).u32(0x00010000).u32(0).u32(0x5F0F3CF5)
        u16(0x0003).u16(unitsPerEm)
        bytes(ByteArray(16)) // dates
        u16(0).u16(0).u16(0).u16(0) // bounding box, not read
        u16(macStyle).u16(8).u16(2)
        u16(if (shortLoca) 0 else 1).u16(0)
    }

    private fun hhea() = bytes {
        u32(0x00010000).u16(ascender).u16(descender).u16(lineGap)
        bytes(ByteArray(24))
        u16(glyphs.size)
    }

    private fun os2() = bytes {
        val typo = typoMetrics
        u16(4).u16(500).u16(weightClass).u16(5).u16(0)
        bytes(ByteArray(52)) // subscript to vendor, not read
        u16(if (typo != null) 0x80 else 0x40) // fsSelection
        u16(0x20).u16(0x7E)
        u16(typo?.first ?: ascender).u16(typo?.second ?: descender).u16(typo?.third ?: lineGap)
        u16(1000).u16(300).u32(0).u32(0)
        u16(xHeight).u16(capHeight).u16(0).u16(0x20).u16(0)
    }

    private fun post() = bytes {
        u32(0x00020000).u32(italicAngle).u16(underlinePosition).u16(underlineThickness)
        bytes(ByteArray(20))
    }

    private fun cmap(): ByteArray {
        val subtables = ArrayList<Pair<Pair<Int, Int>, ByteArray>>()
        if (variations.isNotEmpty()) subtables += (0 to 5) to format14()
        subtables +=
            when (cmapFormat) {
                4 -> (3 to 1) to format4()
                else -> (3 to 10) to format12()
            }
        val out = TestBytes().u16(0).u16(subtables.size)
        var offset = 4 + 8 * subtables.size
        for ((ids, data) in subtables) {
            out.u16(ids.first).u16(ids.second).u32(offset)
            offset += data.size
        }
        subtables.forEach { out.bytes(it.second) }
        return out.toByteArray()
    }

    /** One group per character, unmerged, unlike the canonical form. */
    private fun format12(): ByteArray {
        val sorted = characters.toSortedMap()
        return bytes {
            u16(12).u16(0).u32(16 + 12 * sorted.size).u32(0).u32(sorted.size)
            for ((c, glyph) in sorted) u32(c).u32(c).u32(glyph)
        }
    }

    /** One segment per character, with idDelta, then the final segment. */
    private fun format4(): ByteArray {
        val sorted = characters.toSortedMap()
        val segments = sorted.size + 1
        return bytes {
            u16(4).u16(16 + 8 * segments).u16(0).u16(segments * 2).u16(0).u16(0).u16(0)
            sorted.keys.forEach { u16(it) }
            u16(0xFFFF)
            u16(0)
            sorted.keys.forEach { u16(it) }
            u16(0xFFFF)
            sorted.forEach { (c, glyph) -> u16(glyph - c) }
            u16(1)
            repeat(segments) { u16(0) }
        }
    }

    private fun format14(): ByteArray {
        val selectors = variations.toSortedMap()
        val tables = TestBytes()
        val records = TestBytes()
        val headerSize = 10 + 11 * selectors.size
        for ((selector, sequences) in selectors) {
            val (defaults, nonDefaults) = sequences
            records.u24(selector)
            if (defaults.isEmpty()) {
                records.u32(0)
            } else {
                records.u32(headerSize + tables.size)
                // One range per code point, unmerged, unlike the canonical form.
                tables.u32(defaults.size)
                defaults.sorted().forEach { tables.u24(it).u8(0) }
            }
            if (nonDefaults.isEmpty()) {
                records.u32(0)
            } else {
                records.u32(headerSize + tables.size)
                tables.u32(nonDefaults.size)
                nonDefaults.toSortedMap().forEach { (c, glyph) -> tables.u24(c).u16(glyph) }
            }
        }
        return bytes {
            u16(14).u32(headerSize + tables.size).u32(selectors.size)
            bytes(records.toByteArray())
            bytes(tables.toByteArray())
        }
    }
}
