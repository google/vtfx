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
import com.google.vtfx.font.gate.SanitizedFont
import com.google.vtfx.font.gate.WidthClass
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Sanitizes real fonts, which Gradle downloads at pinned commits (docs/decisions/0071): one of each
 * kind of font file, a TrueType font, a CID-keyed CFF font, and a collection of CID-keyed CFF
 * faces. The fonts that the other tests build cover the structures that the readers know; these
 * cover the sizes and the combinations of real CJK fonts. Each sanitized font must be one that the
 * verifier of vtfx-font-gate accepts and that sanitizing leaves as it is.
 */
class ThirdPartyFontsTest {
    private val limits = FontLimits()

    @Test
    fun inspect_trueTypeFont_describesItsFace() {
        val font = thirdPartyFont("BIZUDGothic-Regular.ttf")

        val faces = FontFile.inspect(font, limits)

        assertEquals(
            listOf(FaceInfo(0, "BIZ UDGothic", "Regular", OutlineFormat.TrueType)),
            faces,
        )
    }

    @Test
    fun sanitize_trueTypeFont_givesCanonicalFont() {
        val font = thirdPartyFont("BIZUDGothic-Regular.ttf")

        val sanitized = FontFile.sanitize(font, 0, limits)

        assertCanonical(sanitized)
    }

    @Test
    fun inspect_cidKeyedCffFont_describesItsFace() {
        val font = thirdPartyFont("NotoSansJP-Regular.otf")

        val faces = FontFile.inspect(font, limits)

        assertEquals(listOf(FaceInfo(0, "Noto Sans JP", "Regular", OutlineFormat.Cff)), faces)
    }

    @Test
    fun sanitize_cidKeyedCffFont_givesCanonicalFont() {
        val font = thirdPartyFont("NotoSansJP-Regular.otf")

        val sanitized = FontFile.sanitize(font, 0, limits)

        assertCanonical(sanitized)
    }

    @Test
    fun inspect_collection_describesEachFace() {
        val font = thirdPartyFont("NotoSansCJK-Regular.ttc")

        val faces = FontFile.inspect(font, limits)

        assertEquals(
            listOf(
                FaceInfo(0, "Noto Sans CJK JP", "Regular", OutlineFormat.Cff),
                FaceInfo(1, "Noto Sans CJK KR", "Regular", OutlineFormat.Cff),
                FaceInfo(2, "Noto Sans CJK SC", "Regular", OutlineFormat.Cff),
                FaceInfo(3, "Noto Sans CJK TC", "Regular", OutlineFormat.Cff),
                FaceInfo(4, "Noto Sans CJK HK", "Regular", OutlineFormat.Cff),
                FaceInfo(5, "Noto Sans Mono CJK JP", "Regular", OutlineFormat.Cff),
                FaceInfo(6, "Noto Sans Mono CJK KR", "Regular", OutlineFormat.Cff),
                FaceInfo(7, "Noto Sans Mono CJK SC", "Regular", OutlineFormat.Cff),
                FaceInfo(8, "Noto Sans Mono CJK TC", "Regular", OutlineFormat.Cff),
                FaceInfo(9, "Noto Sans Mono CJK HK", "Regular", OutlineFormat.Cff),
            ),
            faces,
        )
    }

    @Test
    fun sanitize_faceAfterTheFirstOfCollection_givesCanonicalFont() {
        val font = thirdPartyFont("NotoSansCJK-Regular.ttc")

        val sanitized = FontFile.sanitize(font, 5, limits)

        assertCanonical(sanitized)
    }

    @Test
    fun sanitize_fontOfHalfAndFullWidths_givesDualWidth() {
        // Every character of BIZ UDGothic is 1024 or 2048 units wide.
        val font = thirdPartyFont("BIZUDGothic-Regular.ttf")

        val sanitized = FontFile.sanitize(font, 0, limits)

        assertEquals(WidthClass.DualWidth, sanitized.widthClass)
    }

    /**
     * [font] is canonical: the verifier accepts its bytes, and sanitizing them gives the same
     * bytes.
     */
    private fun assertCanonical(font: SanitizedFont) {
        val bytes = font.toByteArray()
        assertContentEquals(bytes, SanitizedFont.fromCanonical(bytes, limits).toByteArray())
        assertContentEquals(bytes, FontFile.sanitize(bytes, 0, limits).toByteArray())
    }

    /** The bytes of the font file [name], which the build downloads and passes to the test. */
    private fun thirdPartyFont(name: String): ByteArray {
        val path =
            checkNotNull(System.getProperty("vtfx.thirdPartyFont.$name")) {
                "no path to $name; run the test with Gradle"
            }
        return File(path).readBytes()
    }
}
