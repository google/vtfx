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
 * The outline of one glyph in TrueType's terms: contours of on-curve and off-curve points of
 * quadratic curves, in font units. Contour `i` ends at point `endPoints[i]`, inclusive. Every
 * contour has at least one point. Immutable by convention: the arrays are not changed after
 * construction.
 */
internal class Outline(
    val endPoints: IntArray,
    val xs: IntArray,
    val ys: IntArray,
    val onCurve: BooleanArray,
) {
    init {
        check(xs.size == ys.size && xs.size == onCurve.size) { "point arrays differ in size" }
        check(endPoints.isEmpty() == xs.isEmpty()) { "points without contours" }
        for (i in endPoints.indices) {
            check(endPoints[i] > (if (i == 0) -1 else endPoints[i - 1])) { "empty contour" }
        }
        check(endPoints.isEmpty() || endPoints.last() == xs.size - 1) { "points after contours" }
    }

    val pointCount: Int
        get() = xs.size

    val isEmpty: Boolean
        get() = endPoints.isEmpty()

    companion object {
        val EMPTY = Outline(IntArray(0), IntArray(0), IntArray(0), BooleanArray(0))
    }
}

/** The outlines of the glyphs of a face, read one at a time. */
internal interface OutlineSource {
    /**
     * The outline of glyph [glyphId], in `0 until numGlyphs`, with at most
     * [FontLimits.maxGlyphPoints] points.
     */
    fun outline(glyphId: Int): Outline
}

/**
 * Collects the contours of an outline, refusing more than [maxPoints] points. Contours without
 * points are dropped, as they draw nothing.
 *
 * [build] starts the next outline, keeping the arrays, so that one builder can collect the outlines
 * of all the glyphs of a font without growing its arrays again for each. Not thread-safe.
 */
internal class OutlineBuilder(private val maxPoints: Int) {
    private var xs = IntArray(16)
    private var ys = IntArray(16)
    private var onCurve = BooleanArray(16)
    private var endPoints = IntArray(4)
    private var points = 0
    private var contours = 0
    private var contourStart = 0

    fun point(x: Int, y: Int, on: Boolean) {
        if (points == maxPoints) tooComplex("glyph of more than $maxPoints points")
        if (points == xs.size) {
            val size = minOf(xs.size * 2, maxPoints)
            xs = xs.copyOf(size)
            ys = ys.copyOf(size)
            onCurve = onCurve.copyOf(size)
        }
        xs[points] = x
        ys[points] = y
        onCurve[points] = on
        points++
    }

    /** Ends the contour of the points since the last one. */
    fun closeContour() {
        if (points == contourStart) return
        if (contours == endPoints.size) endPoints = endPoints.copyOf(contours * 2)
        endPoints[contours++] = points - 1
        contourStart = points
    }

    /** The outline of the points and contours collected since the last call. */
    fun build(): Outline {
        check(points == contourStart) { "open contour" }
        if (points == 0) return Outline.EMPTY
        val outline =
            Outline(
                endPoints.copyOf(contours),
                xs.copyOf(points),
                ys.copyOf(points),
                onCurve.copyOf(points),
            )
        points = 0
        contours = 0
        contourStart = 0
        return outline
    }
}
