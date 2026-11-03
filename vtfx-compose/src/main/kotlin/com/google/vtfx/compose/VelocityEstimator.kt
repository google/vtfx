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

package com.google.vtfx.compose

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * Estimates the velocity of a pointer at its last position, from its positions in the last
 * [HORIZON_MILLIS], for flinging (docs/decisions/0100): the slope at the last position of a
 * quadratic fitted to them by least squares, as Android's `VelocityTracker` fits them (LSQ2), or of
 * a line for two positions. Positions before a pause longer than [STOPPED_MILLIS] do not count, so
 * a finger that stopped before it was lifted does not fling. `config/boundaries.toml` leaves
 * Compose's `VelocityTracker` out of `vtfx-compose`.
 */
internal class VelocityEstimator {
    private val times = LongArray(MAX_SAMPLES)
    private val xs = FloatArray(MAX_SAMPLES)
    private val ys = FloatArray(MAX_SAMPLES)
    private var count = 0
    private var newest = -1

    /** Adds the position of the pointer at [timeMillis], which does not go back. */
    fun add(timeMillis: Long, position: Offset) {
        newest = (newest + 1) % MAX_SAMPLES
        times[newest] = timeMillis
        xs[newest] = position.x
        ys[newest] = position.y
        if (count < MAX_SAMPLES) count++
    }

    /**
     * Returns the velocity in pixels per second, of at most [maximum] pixels per second, keeping
     * its direction.
     */
    fun velocity(maximum: Float): Offset {
        val t = DoubleArray(count)
        val x = DoubleArray(count)
        val y = DoubleArray(count)
        var n = 0
        val last = if (count == 0) 0L else times[newest]
        var previous = last
        while (n < count) {
            val index = Math.floorMod(newest - n, MAX_SAMPLES)
            val time = times[index]
            if (last - time > HORIZON_MILLIS || previous - time > STOPPED_MILLIS) break
            // In seconds before the last position.
            t[n] = (time - last) / 1000.0
            x[n] = xs[index].toDouble()
            y[n] = ys[index].toDouble()
            previous = time
            n++
        }
        val velocity = Offset(slope(t, x, n).toFloat(), slope(t, y, n).toFloat())
        val speed = velocity.getDistance()
        return if (speed > maximum) velocity * (maximum / speed) else velocity
    }

    private companion object {
        /** The most positions kept. */
        const val MAX_SAMPLES = 20

        /** How far back positions count, as in Compose's `VelocityTracker`. */
        const val HORIZON_MILLIS = 100L

        /** A pause after which the pointer is taken to have stopped, as in Compose. */
        const val STOPPED_MILLIS = 40L

        /**
         * Returns the slope at time 0 of the curve that fits the first [n] of [values] at [times]:
         * a quadratic for three or more, a line for two, 0 for fewer or for a curve that the times
         * do not determine.
         */
        fun slope(times: DoubleArray, values: DoubleArray, n: Int): Double {
            if (n < 2) return 0.0
            var s1 = 0.0
            var s2 = 0.0
            var s3 = 0.0
            var s4 = 0.0
            var p0 = 0.0
            var p1 = 0.0
            var p2 = 0.0
            for (i in 0 until n) {
                val t = times[i]
                val v = values[i]
                s1 += t
                s2 += t * t
                s3 += t * t * t
                s4 += t * t * t * t
                p0 += v
                p1 += t * v
                p2 += t * t * v
            }
            val s0 = n.toDouble()
            if (n >= 3) {
                // Cramer's rule for b of a + b t + c t² in the normal equations.
                val determinant =
                    s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s2 * s3) + s2 * (s1 * s3 - s2 * s2)
                if (abs(determinant) > EPSILON) {
                    val b =
                        s0 * (p1 * s4 - s3 * p2) - p0 * (s1 * s4 - s2 * s3) +
                            s2 * (s1 * p2 - p1 * s2)
                    return b / determinant
                }
            }
            val determinant = s0 * s2 - s1 * s1
            if (abs(determinant) <= EPSILON) return 0.0
            return (s0 * p1 - s1 * p0) / determinant
        }

        /** Below this, the times do not determine the curve (all at one time, for example). */
        const val EPSILON = 1e-18
    }
}
