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
import com.google.vtfx.font.gate.WidthClass
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SanitizedFontTest {
    private val limits = FontLimits()

    private fun sanitize(font: TestTrueTypeFont, limits: FontLimits = this.limits) =
        FontFile.sanitize(font.build(), 0, limits)

    private fun contents(font: TestTrueTypeFont) = SanitizedContents(sanitize(font))

    private fun failure(font: TestTrueTypeFont, limits: FontLimits = this.limits): Kind =
        assertFailsWith<InvalidFontException> { sanitize(font, limits) }.kind

    @Test
    fun sanitizedFontHasExactlyTheTablesOfTheCanonicalForm() {
        val font =
            TestTrueTypeFont(
                extraTables =
                    mapOf(
                        "fpgm" to ByteArray(8),
                        "GSUB" to ByteArray(8),
                        "CBDT" to ByteArray(8),
                        "gvar" to ByteArray(8),
                    )
            )

        val tags = contents(font).tags

        assertEquals(
            listOf("OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post"),
            tags,
        )
    }

    @Test
    fun simpleGlyphsKeepTheirPoints() {
        val contours =
            listOf(listOf(P(0, 0), P(100, 300, on = false), P(200, 0)), square(10, 20, 30))
        val font = TestTrueTypeFont(glyphs = listOf(TestGlyph.Empty(), TestGlyph.Simple(contours)))

        assertEquals(contours, contents(font).contours(1))
    }

    @Test
    fun instructionsAreDropped() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Empty(),
                        TestGlyph.Simple(
                            listOf(square(0, 0, 100)),
                            instructions = byteArrayOf(0xB0.toByte(), 1),
                        ),
                    )
            )

        assertEquals(listOf(square(0, 0, 100)), contents(font).contours(1))
    }

    @Test
    fun shortLocaOffsetsAreRead() {
        val font = TestTrueTypeFont(shortLoca = true)

        assertEquals(
            listOf(square(100, 0, 300), square(150, 50, 200).reversed()),
            contents(font).contours(1),
        )
    }

    @Test
    fun outlineMovesByItsLeftSideBearingLessItsXMin() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Empty(),
                        TestGlyph.Simple(listOf(square(100, 0, 50)), leftSideBearing = 30),
                    )
            )

        assertEquals(listOf(square(30, 0, 50)), contents(font).contours(1))
    }

    @Test
    fun compositeGlyphIsFlattenedWithItsOffsets() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Simple(listOf(square(0, 0, 10))),
                        TestGlyph.Composite(
                            listOf(TestComponent(0, 0, 0), TestComponent(0, 100, 200))
                        ),
                    )
            )

        assertEquals(
            listOf(square(0, 0, 10), square(100, 200, 10)),
            contents(font).contours(1),
        )
    }

    @Test
    fun compositeGlyphIsFlattenedWithItsScale() {
        val half = 0x2000
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Simple(listOf(square(0, 0, 100))),
                        TestGlyph.Composite(listOf(TestComponent(0, 10, 0, scale = listOf(half)))),
                    )
            )

        assertEquals(listOf(square(10, 0, 50)), contents(font).contours(1))
    }

    @Test
    fun compositeGlyphIsFlattenedWithAMatrix() {
        val one = 0x4000
        val minusOne = 0xC000
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Simple(listOf(listOf(P(10, 0), P(20, 30)))),
                        // x' = -y, y' = x: a quarter turn.
                        TestGlyph.Composite(
                            listOf(TestComponent(0, 0, 0, scale = listOf(0, one, minusOne, 0)))
                        ),
                    )
            )

        assertEquals(listOf(listOf(P(0, 10), P(-30, 20))), contents(font).contours(1))
    }

    @Test
    fun nestedCompositeGlyphsAreFlattened() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Simple(listOf(square(0, 0, 10))),
                        TestGlyph.Composite(listOf(TestComponent(0, 5, 0))),
                        TestGlyph.Composite(listOf(TestComponent(1, 0, 7))),
                    )
            )

        assertEquals(listOf(square(5, 7, 10)), contents(font).contours(2))
    }

    @Test
    fun selfReferentialCompositeGlyphIsTooComplex() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(TestGlyph.Empty(), TestGlyph.Composite(listOf(TestComponent(1, 0, 0))))
            )

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun componentsThatAllGlyphsVisitAreLimitedForTheWholeFace() {
        // Glyph 4 visits 63 + 63 * 64 components and each glyph after it one more: 4096, the
        // most for one glyph, so the glyphs from 4 on visit 2^22 together after 1024 of them.
        val glyphs =
            List(1100) {
                when (it) {
                    0 -> TestGlyph.Empty()
                    1 -> TestGlyph.Simple(listOf(square(50, 0, 400)))
                    2 -> TestGlyph.Empty()
                    3 -> TestGlyph.Composite(List(64) { TestComponent(2, 0, 0) })
                    4 -> TestGlyph.Composite(List(63) { TestComponent(3, 0, 0) })
                    else -> TestGlyph.Composite(listOf(TestComponent(4, 0, 0)))
                }
            }

        assertEquals(Kind.TooComplex, failure(TestTrueTypeFont(glyphs = glyphs)))
    }

    @Test
    fun componentPlacedByPointsIsUnsupported() {
        val font =
            TestTrueTypeFont(
                glyphs =
                    listOf(
                        TestGlyph.Simple(listOf(square(0, 0, 10))),
                        TestGlyph.Composite(listOf(TestComponent(0, 0, 0, xyValues = false))),
                    )
            )

        assertEquals(Kind.Unsupported, failure(font))
    }

    @Test
    fun glyphWithMorePointsThanTheLimitIsTooComplex() {
        val font = TestTrueTypeFont()

        assertEquals(Kind.TooComplex, failure(font, FontLimits(maxGlyphPoints = 7)))
    }

    @Test
    fun coordinateOutOfTheCanonicalRangeIsUnsupported() {
        val font =
            TestTrueTypeFont(
                glyphs = listOf(TestGlyph.Empty(), TestGlyph.Simple(listOf(square(0, 0, 20000))))
            )

        assertEquals(Kind.Unsupported, failure(font))
    }

    @Test
    fun fontLargerThanTheOutputLimitIsTooLarge() {
        val font = TestTrueTypeFont()

        assertEquals(Kind.TooLarge, failure(font, FontLimits(maxOutputBytes = 512)))
    }

    @Test
    fun fileLargerThanTheInputLimitIsTooLarge() {
        val font = TestTrueTypeFont()

        assertEquals(Kind.TooLarge, failure(font, FontLimits(maxInputBytes = 100)))
    }

    @Test
    fun charactersAreKeptFromFormat12() {
        val font = TestTrueTypeFont(characters = mapOf(0x41 to 1, 0x42 to 1, 0x1F600 to 1))

        assertEquals(mapOf(0x41 to 1, 0x42 to 1, 0x1F600 to 1), contents(font).characters)
    }

    @Test
    fun charactersAreKeptFromFormat4() {
        val font = TestTrueTypeFont(characters = mapOf(0x41 to 1, 0x3042 to 1), cmapFormat = 4)

        assertEquals(mapOf(0x41 to 1, 0x3042 to 1), contents(font).characters)
    }

    @Test
    fun consecutiveCharactersOfConsecutiveGlyphsShareAGroup() {
        val font =
            TestTrueTypeFont(
                glyphs = List(4) { TestGlyph.Simple(listOf(square(0, 0, 10))) },
                characters = mapOf(0x41 to 1, 0x42 to 2, 0x43 to 3),
            )

        val cmap = contents(font).table("cmap")

        // The number of groups of the format 12 subtable, after the header and one record.
        assertEquals(1, Reader(cmap).s32(24))
    }

    @Test
    fun fontWithoutCharactersIsUnsupported() {
        val font = TestTrueTypeFont(characters = emptyMap())

        assertEquals(Kind.Unsupported, failure(font))
    }

    @Test
    fun characterMappedToAMissingGlyphIsMalformed() {
        val font = TestTrueTypeFont(characters = mapOf(0x41 to 2))

        assertEquals(Kind.Malformed, failure(font))
    }

    @Test
    fun surrogatesAreNotMapped() {
        val font = TestTrueTypeFont(characters = mapOf(0x41 to 1, 0xD800 to 1))

        assertEquals(mapOf(0x41 to 1), contents(font).characters)
    }

    @Test
    fun variationSequencesAreKept() {
        val font =
            TestTrueTypeFont(
                glyphs = List(3) { TestGlyph.Simple(listOf(square(0, 0, 10))) },
                characters = mapOf(0x845B to 1, 0x8FBB to 2),
                variations =
                    mapOf(
                        0xE0100 to Pair(listOf(0x845B, 0x845C, 0x845D), mapOf(0x8FBB to 2)),
                        0xE0101 to Pair(emptyList(), mapOf(0x845B to 2)),
                    ),
            )

        assertEquals(
            mapOf(
                0xE0100 to Pair(listOf(0x845B, 0x845C, 0x845D), mapOf(0x8FBB to 2)),
                0xE0101 to Pair(emptyList(), mapOf(0x845B to 2)),
            ),
            contents(font).variations,
        )
    }

    @Test
    fun variationSequenceOfNotdefIsDropped() {
        val font =
            TestTrueTypeFont(variations = mapOf(0xFE00 to Pair(emptyList(), mapOf(0x41 to 0))))

        assertEquals(emptyMap(), contents(font).variations)
    }

    @Test
    fun selectorThatIsNotAVariationSelectorIsMalformed() {
        val font = TestTrueTypeFont(variations = mapOf(0x41 to Pair(listOf(0x42), emptyMap())))

        assertEquals(Kind.Malformed, failure(font))
    }

    @Test
    fun hheaMetricsAreKept() {
        val font = TestTrueTypeFont(ascender = 900, descender = -250, lineGap = 100)

        val contents = contents(font)

        assertEquals(
            listOf(900, -250, 100),
            listOf(contents.ascender, contents.descender, contents.lineGap),
        )
    }

    @Test
    fun typographicMetricsAreKeptWhenTheFontAsksForThem() {
        val font = TestTrueTypeFont(typoMetrics = Triple(880, -120, 50))

        val contents = contents(font)

        assertEquals(
            listOf(880, -120, 50),
            listOf(contents.ascender, contents.descender, contents.lineGap),
        )
    }

    @Test
    fun ascenderBeyondTwoEmsIsUnsupported() {
        assertEquals(Kind.Unsupported, failure(TestTrueTypeFont(ascender = 2001)))
    }

    @Test
    fun advanceBeyondFourEmsIsUnsupported() {
        val font =
            TestTrueTypeFont(glyphs = listOf(TestGlyph.Empty(), TestGlyph.Empty(advance = 4001)))

        assertEquals(Kind.Unsupported, failure(font))
    }

    @Test
    fun unitsPerEmBelow16IsUnsupported() {
        assertEquals(Kind.Unsupported, failure(TestTrueTypeFont(unitsPerEm = 8)))
    }

    @Test
    fun fontWithoutOs2AndPostIsSanitized() {
        val font = TestTrueTypeFont(hasOs2 = false, hasPost = false)

        assertEquals(1, contents(font).characters.getValue('A'.code))
    }

    @Test
    fun cff2FontIsUnsupported() {
        val tables = TestTrueTypeFont().tables() - "glyf" - "loca" + ("CFF2" to ByteArray(0))
        val font = TestSfnt.font(tables, Tag.OPEN_TYPE_CFF)

        val kind = assertFailsWith<InvalidFontException> { FontFile.sanitize(font, 0, limits) }.kind

        assertEquals(Kind.Unsupported, kind)
    }

    @Test
    fun faceOfACollectionIsChosenByIndex() {
        val first = TestTrueTypeFont(characters = mapOf(0x41 to 1)).tables()
        val second = TestTrueTypeFont(characters = mapOf(0x42 to 1)).tables()
        val collection = TestSfnt.collection(listOf(first, second))

        val font = FontFile.sanitize(collection, 1, limits)

        assertEquals(mapOf(0x42 to 1), SanitizedContents(font).characters)
    }

    @Test
    fun faceIndexBeyondTheFacesIsAnError() {
        val font = TestTrueTypeFont().build()

        assertFailsWith<IllegalArgumentException> { FontFile.sanitize(font, 1, limits) }
    }

    @Test
    fun sanitizingAnInterruptedThreadStops() {
        val font = TestTrueTypeFont().build()
        Thread.currentThread().interrupt()

        assertFailsWith<InterruptedException> { FontFile.sanitize(font, 0, limits) }
    }

    @Test
    fun sanitizingASanitizedFontGivesTheSameBytes() {
        val first = sanitize(TestTrueTypeFont(characters = mapOf(0x41 to 1, 0x42 to 1)))

        val second = FontFile.sanitize(first.toByteArray(), 0, limits)

        assertContentEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun fromCanonicalLoadsASavedFont() {
        val saved = sanitize(TestTrueTypeFont())

        val loaded = SanitizedFont.fromCanonical(saved.toByteArray(), limits)

        assertEquals(saved, loaded)
    }

    @Test
    fun fromCanonicalRejectsAFontThatWasNotSanitized() {
        val font = TestTrueTypeFont().build()

        val kind =
            assertFailsWith<InvalidFontException> { SanitizedFont.fromCanonical(font, limits) }.kind

        assertEquals(Kind.Malformed, kind)
    }

    @Test
    fun fromCanonicalCopiesTheBytes() {
        val bytes = sanitize(TestTrueTypeFont()).toByteArray()
        val original = bytes.copyOf()
        val loaded = SanitizedFont.fromCanonical(bytes, limits)

        bytes.fill(0)

        assertContentEquals(original, loaded.toByteArray())
    }

    @Test
    fun byteBufferIsReadOnlyAndDirect() {
        val buffer = sanitize(TestTrueTypeFont()).toByteBuffer()

        assertTrue(buffer.isReadOnly && buffer.isDirect)
    }

    @Test
    fun monospacedFontHasOneWidth() {
        val font =
            TestTrueTypeFont(
                glyphs = listOf(TestGlyph.Empty(), TestGlyph.Empty(600), TestGlyph.Empty(0)),
                characters = mapOf(0x41 to 1, 0x301 to 2),
            )

        assertEquals(WidthClass.Monospaced, sanitize(font).widthClass)
    }

    @Test
    fun cjkMonospacedFontHasTwoWidths() {
        val font =
            TestTrueTypeFont(
                glyphs = listOf(TestGlyph.Empty(), TestGlyph.Empty(500), TestGlyph.Empty(1000)),
                characters = mapOf(0x41 to 1, 0x3042 to 2),
            )

        assertEquals(WidthClass.DualWidth, sanitize(font).widthClass)
    }

    @Test
    fun fontOfOtherWidthsIsProportional() {
        val font =
            TestTrueTypeFont(
                glyphs = listOf(TestGlyph.Empty(), TestGlyph.Empty(500), TestGlyph.Empty(700)),
                characters = mapOf(0x41 to 1, 0x42 to 2),
            )

        assertEquals(WidthClass.Proportional, sanitize(font).widthClass)
    }
}
