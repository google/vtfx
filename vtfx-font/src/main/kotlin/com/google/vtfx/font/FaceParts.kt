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
import kotlin.math.abs

/**
 * What the canonical form keeps of a face, read and checked against the ranges of the canonical
 * form (docs/decisions/0086). Values outside them make the font unsupported rather than being
 * clamped, so that no font is drawn differently from what its maker meant without a word.
 */
internal class FaceParts(
    val unitsPerEm: Int,
    /** Bold (bit 0) and italic (bit 1), as `head.macStyle` has them. */
    val macStyle: Int,
    val ascender: Int,
    val descender: Int,
    val lineGap: Int,
    val weightClass: Int,
    val widthClass: Int,
    val xHeight: Int,
    val capHeight: Int,
    /** The italic angle in degrees as a 16.16 fixed-point number, as `post` has it. */
    val italicAngle: Int,
    val underlinePosition: Int,
    val underlineThickness: Int,
    val advances: IntArray,
    val charMap: CharMap,
    val variationSelectors: List<VariationSelector>,
    val outlines: OutlineSource,
) {
    val numGlyphs: Int
        get() = advances.size

    companion object {
        fun read(face: Face, limits: FontLimits): FaceParts {
            val head = face.require(Tag.HEAD)
            if (head.u16(0) != 1) unsupported("head version ${head.u16(0)}")
            val unitsPerEm = head.u16(18)
            if (unitsPerEm !in 16..16384) unsupported("unitsPerEm $unitsPerEm")
            val macStyle = head.u16(44) and (BOLD or ITALIC)
            val indexToLocFormat = head.s16(50)

            val numGlyphs = face.require(Tag.MAXP).u16(4)
            if (numGlyphs == 0) malformed("no glyphs")

            val hhea = face.require(Tag.HHEA)
            val hmtx = face.require(Tag.HMTX)
            val numberOfHMetrics = hhea.u16(34)
            if (numberOfHMetrics !in 1..numGlyphs) malformed("numberOfHMetrics $numberOfHMetrics")
            val advances = IntArray(numGlyphs) { hmtx.u16(minOf(it, numberOfHMetrics - 1) * 4) }
            for (advance in advances) {
                checkRange("advance", advance, 0, 4 * unitsPerEm)
            }

            val os2 = face.table(Tag.OS2)
            // Android draws with the typographic metrics when the font asks for them, and with
            // those of hhea otherwise; the canonical form makes both the same.
            val useTypoMetrics = os2 != null && os2.u16(62) and USE_TYPO_METRICS != 0
            val ascender = if (useTypoMetrics) os2.s16(68) else hhea.s16(4)
            val descender = if (useTypoMetrics) os2.s16(70) else hhea.s16(6)
            val lineGap = if (useTypoMetrics) os2.s16(72) else hhea.s16(8)
            checkRange("ascender", ascender, 1, 2 * unitsPerEm)
            checkRange("descender", descender, -2 * unitsPerEm, 0)
            checkRange("line gap", lineGap, 0, unitsPerEm)
            val weightClass = os2?.u16(4) ?: 400
            val widthClass = os2?.u16(6) ?: 5
            checkRange("weight", weightClass, 1, 1000)
            checkRange("width", widthClass, 1, 9)
            val hasHeights = os2 != null && os2.u16(0) >= 2
            val xHeight = if (hasHeights) os2.s16(86) else 0
            val capHeight = if (hasHeights) os2.s16(88) else 0
            checkRange("x-height", xHeight, 0, 2 * unitsPerEm)
            checkRange("cap height", capHeight, 0, 2 * unitsPerEm)

            val post = face.table(Tag.POST)
            val italicAngle = post?.s32(4) ?: 0
            if (abs(italicAngle.toLong()) > MAX_ITALIC_ANGLE) unsupported("italic angle")
            val underlinePosition = post?.s16(8) ?: 0
            val underlineThickness = post?.s16(10) ?: 0
            checkRange("underline position", underlinePosition, -unitsPerEm, unitsPerEm)
            checkRange("underline thickness", underlineThickness, 0, unitsPerEm)

            val cmap = CmapReader(face.require(Tag.CMAP), numGlyphs)
            val outlines =
                when (face.outlineFormat) {
                    OutlineFormat.TrueType -> {
                        val leftSideBearings =
                            IntArray(numGlyphs) {
                                if (it < numberOfHMetrics) {
                                    hmtx.s16(it * 4 + 2)
                                } else {
                                    hmtx.s16(numberOfHMetrics * 4 + (it - numberOfHMetrics) * 2)
                                }
                            }
                        if (indexToLocFormat !in 0..1) malformed("indexToLocFormat")
                        GlyfOutlines(
                            face.require(Tag.GLYF),
                            face.require(Tag.LOCA),
                            indexToLocFormat == 1,
                            numGlyphs,
                            leftSideBearings,
                            limits,
                        )
                    }
                    OutlineFormat.Cff ->
                        CffOutlines(
                            CffFont.read(face.require(Tag.CFF), numGlyphs, unitsPerEm),
                            unitsPerEm,
                            limits,
                        )
                    OutlineFormat.Cff2 -> unsupported("CFF2 outlines")
                    else -> unsupported("no outlines that vtfx reads")
                }
            return FaceParts(
                unitsPerEm = unitsPerEm,
                macStyle = macStyle,
                ascender = ascender,
                descender = descender,
                lineGap = lineGap,
                weightClass = weightClass,
                widthClass = widthClass,
                xHeight = xHeight,
                capHeight = capHeight,
                italicAngle = italicAngle,
                underlinePosition = underlinePosition,
                underlineThickness = underlineThickness,
                advances = advances,
                charMap = cmap.charMap(),
                variationSelectors = cmap.variationSelectors(),
                outlines = outlines,
            )
        }

        private fun checkRange(what: String, value: Int, min: Int, max: Int) {
            if (value !in min..max) unsupported("$what $value out of $min..$max")
        }

        const val BOLD = 0x01
        const val ITALIC = 0x02

        private const val USE_TYPO_METRICS = 0x80

        /** 60 degrees in 16.16 fixed point. */
        private const val MAX_ITALIC_ANGLE = 60L shl 16
    }
}
