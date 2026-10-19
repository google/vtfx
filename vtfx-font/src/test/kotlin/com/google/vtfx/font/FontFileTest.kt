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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FontFileTest {
    private val limits = FontLimits()

    private val trueTypeTables = mapOf("glyf" to ByteArray(0), "loca" to ByteArray(0))

    private fun fontWithNames(vararg names: TestName): ByteArray =
        TestSfnt.font(trueTypeTables + ("name" to TestSfnt.nameTable(names.toList())))

    private fun inspectFailure(input: ByteArray, limits: FontLimits = this.limits): Kind =
        assertFailsWith<InvalidFontException> { FontFile.inspect(input, limits) }.kind

    @Test
    fun singleFontHasOneFaceWithItsNames() {
        val font = fontWithNames(windowsName(1, "Mono"), windowsName(2, "Bold"))

        val faces = FontFile.inspect(font, limits)

        assertEquals(listOf(FaceInfo(0, "Mono", "Bold", OutlineFormat.TrueType)), faces)
    }

    @Test
    fun typographicNamesComeBeforeTheLegacyOnes() {
        val font =
            fontWithNames(
                windowsName(1, "Mono Light"),
                windowsName(2, "Regular"),
                windowsName(16, "Mono"),
                windowsName(17, "Light"),
            )

        val face = FontFile.inspect(font, limits).single()

        assertEquals(listOf("Mono", "Light"), listOf(face.familyName, face.styleName))
    }

    @Test
    fun windowsNameInUsEnglishComesBeforeOtherLanguages() {
        val font = fontWithNames(windowsName(1, "等幅", language = 0x0411), windowsName(1, "Mono"))

        assertEquals("Mono", FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun windowsNameInAnotherLanguageComesBeforeUnicodePlatform() {
        val font =
            fontWithNames(
                TestName(0, 3, 0, 1, "Unicode".toByteArray(Charsets.UTF_16BE)),
                windowsName(1, "等幅", language = 0x0411),
            )

        assertEquals("等幅", FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun unicodePlatformNameIsReadWithoutWindowsNames() {
        val font = fontWithNames(TestName(0, 4, 0, 1, "Mono".toByteArray(Charsets.UTF_16BE)))

        assertEquals("Mono", FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun macintoshOnlyNamesAreNotRead() {
        val font = fontWithNames(TestName(1, 0, 0, 1, "Mono".toByteArray(Charsets.ISO_8859_1)))

        assertNull(FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun faceWithoutNameTableHasNoNames() {
        val face = FontFile.inspect(TestSfnt.font(trueTypeTables), limits).single()

        assertEquals(listOf(null, null), listOf(face.familyName, face.styleName))
    }

    @Test
    fun namesLoseControlAndBidirectionalFormattingCharacters() {
        val font = fontWithNames(windowsName(1, "\u202Eab\u0000c\u2066d\u200F"))

        assertEquals("abcd", FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun namesAreCutTo64CodePointsWithoutSplittingSurrogatePairs() {
        val font = fontWithNames(windowsName(1, "a".repeat(63) + "\uD83D\uDE00" + "b"))

        assertEquals(
            "a".repeat(63) + "\uD83D\uDE00",
            FontFile.inspect(font, limits).single().familyName,
        )
    }

    @Test
    fun unpairedSurrogatesInNamesAreReplaced() {
        val font = fontWithNames(windowsName(1, "a\uD83Db"))

        assertEquals("a\uFFFDb", FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun nameLeftEmptyIsNoName() {
        val font = fontWithNames(windowsName(1, "\u202A\u202C"))

        assertNull(FontFile.inspect(font, limits).single().familyName)
    }

    @Test
    fun nameOfOddLengthIsMalformed() {
        val font = fontWithNames(TestName(3, 1, 0x0409, 1, byteArrayOf(0, 0x41, 0)))

        assertEquals(Kind.Malformed, inspectFailure(font))
    }

    @Test
    fun collectionListsEveryFaceWithItsIndex() {
        val name = { family: String -> TestSfnt.nameTable(listOf(windowsName(1, family))) }
        val collection =
            TestSfnt.collection(
                listOf(
                    trueTypeTables + ("name" to name("JP")),
                    mapOf("CFF " to ByteArray(0), "name" to name("KR")),
                ),
                listOf(Tag.TRUE_TYPE, Tag.OPEN_TYPE_CFF),
            )

        val faces = FontFile.inspect(collection, limits)

        assertEquals(
            listOf(
                FaceInfo(0, "JP", null, OutlineFormat.TrueType),
                FaceInfo(1, "KR", null, OutlineFormat.Cff),
            ),
            faces,
        )
    }

    @Test
    fun cffFaceIsSupported() {
        val font = TestSfnt.font(mapOf("CFF " to ByteArray(0)), Tag.OPEN_TYPE_CFF)

        assertTrue(FontFile.inspect(font, limits).single().isSupported)
    }

    @Test
    fun cff2FaceIsListedButNotSupported() {
        val font = TestSfnt.font(mapOf("CFF2" to ByteArray(0)), Tag.OPEN_TYPE_CFF)

        val face = FontFile.inspect(font, limits).single()

        assertEquals(OutlineFormat.Cff2, face.outlineFormat)
        assertFalse(face.isSupported)
    }

    @Test
    fun faceWithBothTrueTypeAndCffHasNoOutlineFormat() {
        val font = TestSfnt.font(trueTypeTables + ("CFF " to ByteArray(0)))

        assertEquals(OutlineFormat.None, FontFile.inspect(font, limits).single().outlineFormat)
    }

    @Test
    fun faceWithoutOutlinesHasNoOutlineFormat() {
        val font = TestSfnt.font(mapOf("CBDT" to ByteArray(0), "CBLC" to ByteArray(0)))

        assertEquals(OutlineFormat.None, FontFile.inspect(font, limits).single().outlineFormat)
    }

    @Test
    fun woffIsUnsupported() {
        val woff = bytes { u32(tag("wOFF")).u32(Tag.TRUE_TYPE).u32(44) }

        assertEquals(Kind.Unsupported, inspectFailure(woff))
    }

    @Test
    fun unknownFileIsUnsupported() {
        assertEquals(Kind.Unsupported, inspectFailure("%PDF-1.7".toByteArray()))
    }

    @Test
    fun fileShorterThanAVersionIsMalformed() {
        assertEquals(Kind.Malformed, inspectFailure(byteArrayOf(0, 1, 0)))
    }

    @Test
    fun collectionFaceWithWoffHeaderIsUnsupported() {
        val collection = bytes { u32(Tag.COLLECTION).u16(1).u16(0).u32(1).u32(16).u32(tag("wOFF")) }

        assertEquals(Kind.Unsupported, inspectFailure(collection))
    }

    @Test
    fun collectionInsideACollectionIsMalformed() {
        val collection = bytes {
            u32(Tag.COLLECTION).u16(1).u16(0).u32(1).u32(16).u32(Tag.COLLECTION)
        }

        assertEquals(Kind.Malformed, inspectFailure(collection))
    }

    @Test
    fun collectionWithoutFacesIsMalformed() {
        val collection = bytes { u32(Tag.COLLECTION).u16(1).u16(0).u32(0) }

        assertEquals(Kind.Malformed, inspectFailure(collection))
    }

    @Test
    fun collectionOfAnUnknownVersionIsUnsupported() {
        val collection = bytes { u32(Tag.COLLECTION).u16(3).u16(0).u32(1).u32(16) }

        assertEquals(Kind.Unsupported, inspectFailure(collection))
    }

    @Test
    fun tableBeyondTheEndOfTheFileIsMalformed() {
        val font = bytes {
            u32(Tag.TRUE_TYPE).u16(1).u16(16).u16(0).u16(0)
            u32(tag("glyf")).u32(0).u32(28).u32(5)
            u32(0)
        }

        assertEquals(Kind.Malformed, inspectFailure(font))
    }

    @Test
    fun tableDirectoryBeyondTheEndOfTheFileIsMalformed() {
        val font = bytes { u32(Tag.TRUE_TYPE).u16(2).u16(32).u16(1).u16(0) }

        assertEquals(Kind.Malformed, inspectFailure(font))
    }

    @Test
    fun twoTablesWithOneTagAreMalformed() {
        val font = bytes {
            u32(Tag.TRUE_TYPE).u16(2).u16(32).u16(1).u16(0)
            u32(tag("glyf")).u32(0).u32(44).u32(0)
            u32(tag("glyf")).u32(0).u32(44).u32(0)
        }

        assertEquals(Kind.Malformed, inspectFailure(font))
    }

    @Test
    fun fileLargerThanTheLimitIsTooLarge() {
        val font = TestSfnt.font(trueTypeTables)

        assertEquals(Kind.TooLarge, inspectFailure(font, FontLimits(maxInputBytes = font.size - 1)))
    }
}
