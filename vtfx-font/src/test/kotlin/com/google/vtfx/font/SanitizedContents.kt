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

/** Reads back what a sanitized font holds, with the readers of the input. */
class SanitizedContents(font: SanitizedFont) {
    private val bytes = font.toByteArray()
    private val face = Sfnt.face(bytes, 0)
    private val parts = FaceParts.read(face, FontLimits())
    private val cmap = CmapReader(face.require(Tag.CMAP), parts.numGlyphs)

    /** The tags of the tables, in the order of the directory. */
    val tags: List<String> =
        Reader(bytes).let { file -> List(file.u16(4)) { Tag.toString(file.s32(12 + it * 16)) } }

    val numGlyphs: Int
        get() = parts.numGlyphs

    val advances: List<Int>
        get() = parts.advances.toList()

    val ascender: Int
        get() = parts.ascender

    val descender: Int
        get() = parts.descender

    val lineGap: Int
        get() = parts.lineGap

    fun contours(glyph: Int): List<List<P>> {
        val outline = parts.outlines.outline(glyph)
        var start = 0
        return outline.endPoints.map { end ->
            (start..end)
                .map { P(outline.xs[it], outline.ys[it], outline.onCurve[it]) }
                .also {
                    start = end + 1
                }
        }
    }

    val characters: Map<Int, Int>
        get() {
            val map = cmap.charMap()
            return map.codePoints.indices.associate { map.codePoints[it] to map.glyphs[it] }
        }

    /** For each selector, its default characters and its other sequences. */
    val variations: Map<Int, Pair<List<Int>, Map<Int, Int>>>
        get() =
            cmap.variationSelectors().associate { selector ->
                selector.selector to
                    Pair(
                        selector.defaults.toList(),
                        selector.baseCharacters.indices.associate {
                            selector.baseCharacters[it] to selector.glyphs[it]
                        },
                    )
            }

    fun table(tag: String): ByteArray {
        val reader = face.require(tag(tag))
        return ByteArray(reader.length) { reader.u8(it).toByte() }
    }
}
