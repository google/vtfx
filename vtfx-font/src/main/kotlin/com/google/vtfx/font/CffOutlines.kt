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

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Converts the CFF outlines of a `CFF ` table to TrueType's: the charstrings are run, and their
 * cubic curves are approximated with quadratic ones within 1/1000 of the em (docs/decisions/0040).
 *
 * The outlines are not moved: CFF draws them relative to the origin, with no left side bearing to
 * apply. Contours are reversed, since CFF draws outer contours counter-clockwise and TrueType
 * clockwise.
 */
internal class CffOutlines(
    private val font: CffFont,
    unitsPerEm: Int,
    private val limits: FontLimits,
) : OutlineSource {
    private val tolerance = unitsPerEm / 1000.0

    override fun outline(glyphId: Int): Outline {
        val pen = QuadraticPen(limits.maxGlyphPoints, tolerance)
        Type2Charstring(font.globalSubrs, font.localSubrs(glyphId), pen)
            .run(font.charStrings.item(glyphId))
        return pen.build()
    }
}

/**
 * Collects the contours of a charstring as quadratic ones, each reversed with its start point kept
 * first (as fontTools' reverse pen does), with coordinates rounded to integers.
 *
 * Closing a contour drops its last point if it is on the curve where the contour starts, since
 * TrueType closes contours by itself; a contour of one point, which draws nothing, is dropped.
 */
private class QuadraticPen(private val maxPoints: Int, private val tolerance: Double) : CubicPen {
    private val builder = OutlineBuilder(maxPoints)
    private var xs = IntArray(16)
    private var ys = IntArray(16)
    private var onCurve = BooleanArray(16)

    /** The points of the open contour. */
    private var count = 0

    /** The points of the contours already given to [builder]. */
    private var emitted = 0
    private var x = 0.0
    private var y = 0.0

    override fun moveTo(x: Double, y: Double) {
        closeContour()
        point(x, y, on = true)
        this.x = x
        this.y = y
    }

    override fun lineTo(x: Double, y: Double) {
        point(x, y, on = true)
        this.x = x
        this.y = y
    }

    override fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        for (value in doubleArrayOf(x1, y1, x2, y2, x, y)) checkCoordinate(value)
        CubicToQuadratic.approximate(this.x, this.y, x1, y1, x2, y2, x, y, tolerance) { qx, qy ->
            point(qx, qy, on = false)
        }
        point(x, y, on = true)
        this.x = x
        this.y = y
    }

    override fun endGlyph() {
        closeContour()
    }

    fun build(): Outline {
        closeContour()
        return builder.build()
    }

    private fun point(x: Double, y: Double, on: Boolean) {
        // One point more than the limit may be held, as the closing point that may be dropped;
        // the builder checks the limit exactly.
        if (emitted + count > maxPoints) tooComplex("glyph of more than $maxPoints points")
        if (count == xs.size) {
            val size = count * 2
            xs = xs.copyOf(size)
            ys = ys.copyOf(size)
            onCurve = onCurve.copyOf(size)
        }
        xs[count] = checkCoordinate(x).roundToInt()
        ys[count] = checkCoordinate(y).roundToInt()
        onCurve[count] = on
        count++
    }

    private fun closeContour() {
        var last = count - 1
        if (last > 0 && onCurve[last] && xs[last] == xs[0] && ys[last] == ys[0]) last--
        if (last > 0) {
            builder.point(xs[0], ys[0], onCurve[0])
            for (i in last downTo 1) builder.point(xs[i], ys[i], onCurve[i])
            builder.closeContour()
            emitted += last + 1
        }
        count = 0
    }

    private fun checkCoordinate(value: Double): Double {
        // Also false for NaN. The canonical form takes a narrower range, which the writer checks.
        if (!(abs(value) <= MAX_COORDINATE)) unsupported("coordinate $value out of range")
        return value
    }

    private companion object {
        const val MAX_COORDINATE = 65536.0
    }
}

/**
 * Approximates cubic curves with quadratic splines, as fontTools' cu2qu does: the fewest quadratic
 * curves, up to [MAX_CURVES], whose distance from the cubic stays within the tolerance.
 *
 * A spline of `n` curves has `n` off-curve points; the on-curve points between consecutive ones are
 * implied at their midpoints, as TrueType draws them.
 */
internal object CubicToQuadratic {
    /** The most quadratic curves for one cubic one. */
    const val MAX_CURVES = 32

    /** How many times an error curve is split in half before it is taken as not fitting. */
    private const val MAX_SPLIT_DEPTH = 32

    /**
     * Gives [offCurve] the off-curve points of the quadratic spline that approximates the cubic
     * curve from ([x0], [y0]) to ([x3], [y3]) within [tolerance].
     */
    fun approximate(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        tolerance: Double,
        offCurve: (Double, Double) -> Unit,
    ) {
        val cubic = arrayOf(V(x0, y0), V(x1, y1), V(x2, y2), V(x3, y3))
        for (n in 1..MAX_CURVES) {
            val spline = spline(cubic, n, tolerance) ?: continue
            for (point in spline) offCurve(point.x, point.y)
            return
        }
        tooComplex("curve that $MAX_CURVES quadratic curves cannot approximate")
    }

    /** The off-curve points of a spline of [n] curves, or null if it does not fit. */
    private fun spline(cubic: Array<V>, n: Int, tolerance: Double): List<V>? {
        if (n == 1) return quadratic(cubic, tolerance)?.let { listOf(it) }
        val cubics = split(cubic, n)
        var nextQ1 = control(0.0, cubics[0])
        val controls = arrayListOf(nextQ1)
        var q2 = cubic[0]
        var d1 = V(0.0, 0.0)
        for (i in 1..n) {
            val (_, c1, c2, c3) = cubics[i - 1]
            val q0 = q2
            val q1 = nextQ1
            if (i < n) {
                nextQ1 = control(i / (n - 1.0), cubics[i])
                controls += nextQ1
                q2 = (q1 + nextQ1) * 0.5
            } else {
                q2 = c3
            }
            val d0 = d1
            d1 = q2 - c3
            if (
                d1.length > tolerance ||
                    !fitsInside(
                        d0,
                        q0 + (q1 - q0) * (2.0 / 3) - c1,
                        q2 + (q1 - q2) * (2.0 / 3) - c2,
                        d1,
                        tolerance,
                        depth = 0,
                    )
            ) {
                return null
            }
        }
        return controls
    }

    /** The control point of one quadratic curve for [cubic], or null if it does not fit. */
    private fun quadratic(cubic: Array<V>, tolerance: Double): V? {
        val (c0, c1, c2, c3) = cubic
        val q1 = intersection(c0, c1, c2, c3) ?: return null
        val error1 = c0 + (q1 - c0) * (2.0 / 3) - c1
        val error2 = c3 + (q1 - c3) * (2.0 / 3) - c2
        val zero = V(0.0, 0.0)
        return if (fitsInside(zero, error1, error2, zero, tolerance, depth = 0)) q1 else null
    }

    /** Where the line through [a] and [b] meets the one through [c] and [d], if they do. */
    private fun intersection(a: V, b: V, c: V, d: V): V? {
        val ab = b - a
        val cd = d - c
        // ab turned by 90 degrees.
        val p = V(-ab.y, ab.x)
        val denominator = p dot cd
        if (denominator == 0.0) return null
        return c + cd * ((p dot (a - c)) / denominator)
    }

    /** The control point of a quadratic curve at [t] along the one that [cubic] suggests. */
    private fun control(t: Double, cubic: Array<V>): V {
        val (p0, p1, p2, p3) = cubic
        val q1 = p0 + (p1 - p0) * 1.5
        val q2 = p3 + (p2 - p3) * 1.5
        return q1 + (q2 - q1) * t
    }

    /** [cubic] split into [n] cubic curves of equal steps of its parameter. */
    private fun split(cubic: Array<V>, n: Int): List<Array<V>> {
        val (p0, p1, p2, p3) = cubic
        // The cubic as a t^3 + b t^2 + c t + d.
        val c = (p1 - p0) * 3.0
        val b = (p2 - p1) * 3.0 - c
        val a = p3 - p0 - c - b
        val dt = 1.0 / n
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        return List(n) { i ->
            val t = i * dt
            val t2 = t * t
            val a1 = a * dt3
            val b1 = (a * (3 * t) + b) * dt2
            val c1 = (b * (2 * t) + c + a * (3 * t2)) * dt
            val d1 = a * (t * t2) + b * t2 + c * t + p0
            val q1 = c1 * (1 / 3.0) + d1
            val q2 = (b1 + c1) * (1 / 3.0) + q1
            arrayOf(d1, q1, q2, a1 + d1 + c1 + b1)
        }
    }

    /**
     * Whether the cubic curve of [p0] to [p3], the difference between a cubic curve and its
     * approximation, stays within [tolerance] of the origin.
     */
    private fun fitsInside(p0: V, p1: V, p2: V, p3: V, tolerance: Double, depth: Int): Boolean {
        if (p2.length <= tolerance && p1.length <= tolerance) return true
        if (depth == MAX_SPLIT_DEPTH) return false
        val mid = (p0 + (p1 + p2) * 3.0 + p3) * 0.125
        if (mid.length > tolerance) return false
        val derivative = (p3 + p2 - p1 - p0) * 0.125
        return fitsInside(p0, (p0 + p1) * 0.5, mid - derivative, mid, tolerance, depth + 1) &&
            fitsInside(mid, mid + derivative, (p2 + p3) * 0.5, p3, tolerance, depth + 1)
    }

    /** A point or a vector. */
    private data class V(val x: Double, val y: Double) {
        operator fun plus(other: V) = V(x + other.x, y + other.y)

        operator fun minus(other: V) = V(x - other.x, y - other.y)

        operator fun times(factor: Double) = V(x * factor, y * factor)

        infix fun dot(other: V) = x * other.x + y * other.y

        val length: Double
            get() = hypot(x, y)
    }
}
