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
import kotlin.math.roundToInt

/**
 * Converts the CFF outlines of a `CFF ` table to TrueType's: the charstrings are run, and their
 * cubic curves are approximated with quadratic ones within 1/1000 of the em (docs/decisions/0040).
 *
 * The outlines are not moved: CFF draws them relative to the origin, with no left side bearing to
 * apply. Contours are reversed, since CFF draws outer contours counter-clockwise and TrueType
 * clockwise.
 *
 * Not thread-safe: one pen, with its arrays, converts every glyph, so that converting the tens of
 * thousands of glyphs of a CJK font does not allocate and grow new arrays for each of them. A glyph
 * that fails to convert leaves the pen in the middle of it, which is fine since the font is then
 * rejected and no other glyph is read.
 */
internal class CffOutlines(
    private val font: CffFont,
    unitsPerEm: Int,
    limits: FontLimits,
) : OutlineSource {
    private val pen = QuadraticPen(limits.maxGlyphPoints, unitsPerEm / 1000.0)
    private val tokens = WorkBudget(Type2Charstring.MAX_FACE_TOKENS, "charstring tokens")

    override fun outline(glyphId: Int): Outline {
        Type2Charstring(font.globalSubrs, font.localSubrs(glyphId), pen, tokens)
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
 *
 * [build] starts the next glyph, keeping the arrays. Not thread-safe.
 */
private class QuadraticPen(private val maxPoints: Int, tolerance: Double) : CubicPen {
    private val builder = OutlineBuilder(maxPoints)
    private val curves = CubicToQuadratic(tolerance)
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
        checkCoordinate(x1)
        checkCoordinate(y1)
        checkCoordinate(x2)
        checkCoordinate(y2)
        checkCoordinate(x)
        checkCoordinate(y)
        val count = curves.approximate(this.x, this.y, x1, y1, x2, y2, x, y)
        for (i in 0 until count) point(curves.points[2 * i], curves.points[2 * i + 1], on = false)
        point(x, y, on = true)
        this.x = x
        this.y = y
    }

    override fun endGlyph() {
        closeContour()
    }

    /** The outline of the glyph drawn since the last call. */
    fun build(): Outline {
        closeContour()
        emitted = 0
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
 * curves, up to [MAX_CURVES], whose distance from the cubic stays within [tolerance].
 *
 * A spline of `n` curves has `n` off-curve points; the on-curve points between consecutive ones are
 * implied at their midpoints, as TrueType draws them.
 *
 * Points are pairs of doubles in arrays rather than objects, and the result goes to an array, so
 * that converting the curves of a CJK font does not allocate for each of them: Android's runtime,
 * unlike HotSpot, does not remove short-lived objects, which made a CJK font take over a minute.
 * Not thread-safe: it reuses its arrays.
 */
internal class CubicToQuadratic(tolerance: Double) {
    /**
     * Distances are compared squared, without square roots: on Android, a debuggable app calls
     * `Math.sqrt` through JNI, and these comparisons run millions of times for a CJK font.
     */
    private val squaredTolerance = tolerance * tolerance

    /** The n cubic curves of a split, 8 numbers each (x0, y0, ..., x3, y3). */
    private val pieces = DoubleArray(8 * MAX_CURVES)

    /** The off-curve points of the last spline that [approximate] made, x and y in turn. */
    val points = DoubleArray(2 * MAX_CURVES)

    /**
     * Approximates the cubic curve from ([x0], [y0]) to ([x3], [y3]) and returns the number of
     * off-curve points, which are in [points].
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
    ): Int {
        if (quadratic(x0, y0, x1, y1, x2, y2, x3, y3)) return 1
        for (n in 2..MAX_CURVES) {
            if (spline(x0, y0, x1, y1, x2, y2, x3, y3, n)) return n
        }
        tooComplex("curve that $MAX_CURVES quadratic curves cannot approximate")
    }

    /** Whether one quadratic curve fits; if so, its control point is the first of [points]. */
    private fun quadratic(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
    ): Boolean {
        // Where the line through the first two points meets the one through the last two.
        val abX = x1 - x0
        val abY = y1 - y0
        val cdX = x3 - x2
        val cdY = y3 - y2
        // ab turned by 90 degrees.
        val pX = -abY
        val pY = abX
        val denominator = pX * cdX + pY * cdY
        if (denominator == 0.0) return false
        val h = (pX * (x0 - x2) + pY * (y0 - y2)) / denominator
        val qX = x2 + cdX * h
        val qY = y2 + cdY * h
        val error1X = x0 + (qX - x0) * (2.0 / 3) - x1
        val error1Y = y0 + (qY - y0) * (2.0 / 3) - y1
        val error2X = x3 + (qX - x3) * (2.0 / 3) - x2
        val error2Y = y3 + (qY - y3) * (2.0 / 3) - y2
        if (!fitsInside(0.0, 0.0, error1X, error1Y, error2X, error2Y, 0.0, 0.0, depth = 0)) {
            return false
        }
        points[0] = qX
        points[1] = qY
        return true
    }

    /** Whether a spline of [n] curves fits; if so, its control points are in [points]. */
    private fun spline(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        n: Int,
    ): Boolean {
        split(x0, y0, x1, y1, x2, y2, x3, y3, n)
        control(0.0, 0)
        var q1X = points[0]
        var q1Y = points[1]
        var q2X = x0
        var q2Y = y0
        var d1X = 0.0
        var d1Y = 0.0
        for (i in 1..n) {
            val piece = (i - 1) * 8
            val c1X = pieces[piece + 2]
            val c1Y = pieces[piece + 3]
            val c2X = pieces[piece + 4]
            val c2Y = pieces[piece + 5]
            val c3X = pieces[piece + 6]
            val c3Y = pieces[piece + 7]
            val q0X = q2X
            val q0Y = q2Y
            val currentX = q1X
            val currentY = q1Y
            if (i < n) {
                control(i / (n - 1.0), i)
                q1X = points[2 * i]
                q1Y = points[2 * i + 1]
                q2X = (currentX + q1X) * 0.5
                q2Y = (currentY + q1Y) * 0.5
            } else {
                q2X = c3X
                q2Y = c3Y
            }
            val d0X = d1X
            val d0Y = d1Y
            d1X = q2X - c3X
            d1Y = q2Y - c3Y
            if (
                squaredLength(d1X, d1Y) > squaredTolerance ||
                    !fitsInside(
                        d0X,
                        d0Y,
                        q0X + (currentX - q0X) * (2.0 / 3) - c1X,
                        q0Y + (currentY - q0Y) * (2.0 / 3) - c1Y,
                        q2X + (currentX - q2X) * (2.0 / 3) - c2X,
                        q2Y + (currentY - q2Y) * (2.0 / 3) - c2Y,
                        d1X,
                        d1Y,
                        depth = 0,
                    )
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Puts in [points] at [index] the control point of a quadratic curve at [t] along the one that
     * piece [index] suggests.
     */
    private fun control(t: Double, index: Int) {
        val piece = index * 8
        val q1X = pieces[piece] + (pieces[piece + 2] - pieces[piece]) * 1.5
        val q1Y = pieces[piece + 1] + (pieces[piece + 3] - pieces[piece + 1]) * 1.5
        val q2X = pieces[piece + 6] + (pieces[piece + 4] - pieces[piece + 6]) * 1.5
        val q2Y = pieces[piece + 7] + (pieces[piece + 5] - pieces[piece + 7]) * 1.5
        points[2 * index] = q1X + (q2X - q1X) * t
        points[2 * index + 1] = q1Y + (q2Y - q1Y) * t
    }

    /** Splits the cubic curve into [n] in [pieces], at equal steps of its parameter. */
    private fun split(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        n: Int,
    ) {
        // The cubic as a t^3 + b t^2 + c t + d.
        val cX = (x1 - x0) * 3.0
        val cY = (y1 - y0) * 3.0
        val bX = (x2 - x1) * 3.0 - cX
        val bY = (y2 - y1) * 3.0 - cY
        val aX = x3 - x0 - cX - bX
        val aY = y3 - y0 - cY - bY
        val dt = 1.0 / n
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        for (i in 0 until n) {
            val t = i * dt
            val t2 = t * t
            val a1X = aX * dt3
            val a1Y = aY * dt3
            val b1X = (aX * (3 * t) + bX) * dt2
            val b1Y = (aY * (3 * t) + bY) * dt2
            val c1X = (bX * (2 * t) + cX + aX * (3 * t2)) * dt
            val c1Y = (bY * (2 * t) + cY + aY * (3 * t2)) * dt
            val d1X = aX * (t * t2) + bX * t2 + cX * t + x0
            val d1Y = aY * (t * t2) + bY * t2 + cY * t + y0
            val q1X = c1X * (1 / 3.0) + d1X
            val q1Y = c1Y * (1 / 3.0) + d1Y
            val q2X = (b1X + c1X) * (1 / 3.0) + q1X
            val q2Y = (b1Y + c1Y) * (1 / 3.0) + q1Y
            val piece = i * 8
            pieces[piece] = d1X
            pieces[piece + 1] = d1Y
            pieces[piece + 2] = q1X
            pieces[piece + 3] = q1Y
            pieces[piece + 4] = q2X
            pieces[piece + 5] = q2Y
            pieces[piece + 6] = a1X + d1X + c1X + b1X
            pieces[piece + 7] = a1Y + d1Y + c1Y + b1Y
        }
    }

    /**
     * Whether the cubic curve of p0 to p3, the difference between a cubic curve and its
     * approximation, stays within [tolerance] of the origin.
     */
    private fun fitsInside(
        p0X: Double,
        p0Y: Double,
        p1X: Double,
        p1Y: Double,
        p2X: Double,
        p2Y: Double,
        p3X: Double,
        p3Y: Double,
        depth: Int,
    ): Boolean {
        if (
            squaredLength(p2X, p2Y) <= squaredTolerance &&
                squaredLength(p1X, p1Y) <= squaredTolerance
        ) {
            return true
        }
        if (depth == MAX_SPLIT_DEPTH) return false
        val midX = (p0X + (p1X + p2X) * 3.0 + p3X) * 0.125
        val midY = (p0Y + (p1Y + p2Y) * 3.0 + p3Y) * 0.125
        if (squaredLength(midX, midY) > squaredTolerance) return false
        val derivativeX = (p3X + p2X - p1X - p0X) * 0.125
        val derivativeY = (p3Y + p2Y - p1Y - p0Y) * 0.125
        return fitsInside(
            p0X,
            p0Y,
            (p0X + p1X) * 0.5,
            (p0Y + p1Y) * 0.5,
            midX - derivativeX,
            midY - derivativeY,
            midX,
            midY,
            depth + 1,
        ) &&
            fitsInside(
                midX,
                midY,
                midX + derivativeX,
                midY + derivativeY,
                (p2X + p3X) * 0.5,
                (p2Y + p3Y) * 0.5,
                p3X,
                p3Y,
                depth + 1,
            )
    }

    private fun squaredLength(x: Double, y: Double): Double = x * x + y * y

    companion object {
        /** The most quadratic curves for one cubic one. */
        const val MAX_CURVES = 32

        /** How many times an error curve is split in half before it is taken as not fitting. */
        private const val MAX_SPLIT_DEPTH = 32
    }
}
