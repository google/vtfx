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
import kotlin.math.roundToInt

/**
 * Reads the TrueType outlines of the `glyf` and `loca` tables, flattening composite glyphs into
 * simple ones and dropping instructions.
 *
 * Each outline is moved horizontally by its left side bearing in `hmtx` less the `xMin` of its
 * header, as FreeType places it, so that it draws where it did although the canonical form sets the
 * left side bearing to `xMin`. In a canonical font the two are equal, and nothing moves.
 *
 * Not thread-safe: one builder, with its arrays, collects every glyph, so that converting the tens
 * of thousands of glyphs of a CJK font does not allocate and grow new arrays for each of them. A
 * glyph that fails to convert leaves the builder in the middle of it, which is fine since the font
 * is then rejected and no other glyph is read.
 */
internal class GlyfOutlines(
    private val glyf: Reader,
    loca: Reader,
    longOffsets: Boolean,
    private val numGlyphs: Int,
    private val leftSideBearings: IntArray,
    private val limits: FontLimits,
) : OutlineSource {
    private val offsets = IntArray(numGlyphs + 1)
    private val builder = OutlineBuilder(limits.maxGlyphPoints)
    private val components = WorkBudget(MAX_FACE_COMPONENTS, "components")

    /** The components of the glyph being read. */
    private var glyphComponents = 0

    init {
        for (i in 0..numGlyphs) {
            offsets[i] = if (longOffsets) loca.index32(i * 4) else loca.u16(i * 2) * 2
            if (i > 0 && offsets[i] < offsets[i - 1]) malformed("loca not in order")
        }
        if (offsets[numGlyphs] > glyf.length) malformed("loca beyond glyf")
    }

    override fun outline(glyphId: Int): Outline {
        val data = glyphData(glyphId) ?: return Outline.EMPTY
        glyphComponents = 0
        val shift = leftSideBearings[glyphId] - data.s16(2)
        append(glyphId, IDENTITY, shift.toDouble(), 0.0, depth = 0)
        return builder.build()
    }

    /** The data of a glyph, or null if it has none. */
    private fun glyphData(glyphId: Int): Reader? {
        val start = offsets[glyphId]
        val length = offsets[glyphId + 1] - start
        if (length == 0) return null
        val data = glyf.sub(start, length)
        // A glyph of zero contours draws nothing, whatever follows its header.
        return if (data.s16(0) == 0) null else data
    }

    /**
     * Appends the contours of [glyphId] to [builder], each point transformed by [matrix] (a, b, c,
     * d, as `x' = a x + c y + dx`, `y' = b x + d y + dy`) and moved by [dx] and [dy].
     */
    private fun append(
        glyphId: Int,
        matrix: DoubleArray,
        dx: Double,
        dy: Double,
        depth: Int,
    ) {
        val data = glyphData(glyphId) ?: return
        val contours = data.s16(0)
        if (contours > 0) {
            appendSimple(data, contours, matrix, dx, dy)
            return
        }
        if (depth == MAX_DEPTH) tooComplex("composite glyphs nested deeper than $MAX_DEPTH")
        var at = 10
        do {
            if (++glyphComponents > MAX_COMPONENTS) {
                tooComplex("glyph of more than $MAX_COMPONENTS components")
            }
            components.spend()
            val flags = data.u16(at)
            val component = data.u16(at + 2)
            if (component >= numGlyphs) malformed("component $component of $numGlyphs glyphs")
            if (flags and ARGS_ARE_XY_VALUES == 0) unsupported("components placed by points")
            val offsetX: Int
            val offsetY: Int
            if (flags and ARG_1_AND_2_ARE_WORDS != 0) {
                offsetX = data.s16(at + 4)
                offsetY = data.s16(at + 6)
                at += 8
            } else {
                offsetX = data.u8(at + 4).toByte().toInt()
                offsetY = data.u8(at + 5).toByte().toInt()
                at += 6
            }
            val a: Double
            val b: Double
            val c: Double
            val d: Double
            when {
                flags and WE_HAVE_A_SCALE != 0 -> {
                    a = f2dot14(data, at)
                    b = 0.0
                    c = 0.0
                    d = a
                    at += 2
                }
                flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> {
                    a = f2dot14(data, at)
                    b = 0.0
                    c = 0.0
                    d = f2dot14(data, at + 2)
                    at += 4
                }
                flags and WE_HAVE_A_TWO_BY_TWO != 0 -> {
                    a = f2dot14(data, at)
                    b = f2dot14(data, at + 2)
                    c = f2dot14(data, at + 4)
                    d = f2dot14(data, at + 6)
                    at += 8
                }
                else -> {
                    a = 1.0
                    b = 0.0
                    c = 0.0
                    d = 1.0
                }
            }
            // FreeType scales the offset only when asked to, and so does this.
            val scaled =
                flags and SCALED_COMPONENT_OFFSET != 0 && flags and UNSCALED_COMPONENT_OFFSET == 0
            val ox = if (scaled) a * offsetX + c * offsetY else offsetX.toDouble()
            val oy = if (scaled) b * offsetX + d * offsetY else offsetY.toDouble()
            // The component's transform, then the parent's.
            val combined =
                doubleArrayOf(
                    matrix[0] * a + matrix[2] * b,
                    matrix[1] * a + matrix[3] * b,
                    matrix[0] * c + matrix[2] * d,
                    matrix[1] * c + matrix[3] * d,
                )
            append(
                component,
                combined,
                matrix[0] * ox + matrix[2] * oy + dx,
                matrix[1] * ox + matrix[3] * oy + dy,
                depth + 1,
            )
        } while (flags and MORE_COMPONENTS != 0)
    }

    private fun appendSimple(
        data: Reader,
        contours: Int,
        matrix: DoubleArray,
        dx: Double,
        dy: Double,
    ) {
        val endPoints = IntArray(contours) { data.u16(10 + it * 2) }
        val points = endPoints.last() + 1
        if (points > limits.maxGlyphPoints) tooComplex("glyph of $points points")
        val instructionLength = data.u16(10 + contours * 2)
        var at = 12 + contours * 2 + instructionLength
        val flags = IntArray(points)
        var i = 0
        while (i < points) {
            val flag = data.u8(at++)
            flags[i++] = flag
            if (flag and REPEAT != 0) {
                val count = data.u8(at++)
                if (count > points - i) malformed("flags repeated beyond the points")
                flags.fill(flag, i, i + count)
                i += count
            }
        }
        val xs = IntArray(points)
        var x = 0
        for (p in 0 until points) {
            val flag = flags[p]
            x +=
                when {
                    flag and X_SHORT != 0 -> {
                        val delta = data.u8(at++)
                        if (flag and X_SAME_OR_POSITIVE != 0) delta else -delta
                    }
                    flag and X_SAME_OR_POSITIVE != 0 -> 0
                    else -> data.s16(at).also { at += 2 }
                }
            xs[p] = x
        }
        var y = 0
        var contour = 0
        for (p in 0 until points) {
            val flag = flags[p]
            y +=
                when {
                    flag and Y_SHORT != 0 -> {
                        val delta = data.u8(at++)
                        if (flag and Y_SAME_OR_POSITIVE != 0) delta else -delta
                    }
                    flag and Y_SAME_OR_POSITIVE != 0 -> 0
                    else -> data.s16(at).also { at += 2 }
                }
            builder.point(
                (matrix[0] * xs[p] + matrix[2] * y + dx).roundToInt(),
                (matrix[1] * xs[p] + matrix[3] * y + dy).roundToInt(),
                flag and ON_CURVE != 0,
            )
            // Contours that end where the one before ended are empty and add no points.
            while (contour < contours && endPoints[contour] == p) {
                builder.closeContour()
                contour++
            }
        }
        // Only end points out of order leave contours unclosed: the last one ends at the last
        // point.
        if (contour != contours) malformed("contours not in order")
    }

    private fun f2dot14(data: Reader, at: Int): Double = data.s16(at) / 16384.0

    private companion object {
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 1.0)

        /** The deepest nesting of composite glyphs; real fonts nest one or two levels. */
        const val MAX_DEPTH = 8

        /** The most components that flattening one glyph visits, at any depth. */
        const val MAX_COMPONENTS = 4096

        /**
         * The most components that flattening all the glyphs of a face visits together, since
         * glyphs can all use the same composite glyphs. Real fonts visit about one for each glyph:
         * Droid Sans Fallback, of 49382 glyphs, visits about 55000.
         */
        const val MAX_FACE_COMPONENTS = 1L shl 22

        const val ON_CURVE = 0x01
        const val X_SHORT = 0x02
        const val Y_SHORT = 0x04
        const val REPEAT = 0x08
        const val X_SAME_OR_POSITIVE = 0x10
        const val Y_SAME_OR_POSITIVE = 0x20

        const val ARG_1_AND_2_ARE_WORDS = 0x0001
        const val ARGS_ARE_XY_VALUES = 0x0002
        const val WE_HAVE_A_SCALE = 0x0008
        const val MORE_COMPONENTS = 0x0020
        const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
        const val WE_HAVE_A_TWO_BY_TWO = 0x0080
        const val SCALED_COMPONENT_OFFSET = 0x0800
        const val UNSCALED_COMPONENT_OFFSET = 0x1000
    }
}
