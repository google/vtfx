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
import org.junit.Assert.assertEquals
import org.junit.Test

class VelocityEstimatorTest {
    private val estimator = VelocityEstimator()

    @Test
    fun aSteadyMoveGivesItsSpeed() {
        // 2 pixels a millisecond down and 1 to the left, every 8 ms.
        for (step in 0..10) estimator.add(step * 8L, Offset(-step * 8f, step * 16f))
        val velocity = estimator.velocity(maximum = 100_000f)
        assertEquals(-1000f, velocity.x, 1f)
        assertEquals(2000f, velocity.y, 1f)
    }

    @Test
    fun anAcceleratingMoveGivesTheSpeedAtTheEnd() {
        // y = t² in milliseconds: 2 t pixels a millisecond, 160 at the end, 80 on average.
        for (step in 0..10) {
            val t = step * 8f
            estimator.add(step * 8L, Offset(0f, t * t))
        }
        assertEquals(160_000f, estimator.velocity(maximum = 1_000_000f).y, 1f)
    }

    @Test
    fun onlyTheLastHundredMillisecondsCount() {
        // Slow at first, then 1 pixel a millisecond for the last 100 ms.
        for (step in 0..20) estimator.add(step * 10L, Offset(0f, step * 1f))
        for (step in 1..10) estimator.add(200L + step * 10, Offset(0f, 20f + step * 10f))
        assertEquals(1000f, estimator.velocity(maximum = 100_000f).y, 1f)
    }

    @Test
    fun aFingerThatStoppedBeforeLiftingDoesNotFling() {
        for (step in 0..5) estimator.add(step * 8L, Offset(0f, step * 16f))
        // Lifted 50 ms after the last move, where it was.
        estimator.add(90L, Offset(0f, 80f))
        assertEquals(Offset.Zero, estimator.velocity(maximum = 100_000f))
    }

    @Test
    fun theVelocityIsCappedKeepingItsDirection() {
        for (step in 0..5) estimator.add(step * 10L, Offset(step * 30f, step * 40f))
        val velocity = estimator.velocity(maximum = 500f)
        assertEquals(300f, velocity.x, 0.5f)
        assertEquals(400f, velocity.y, 0.5f)
    }

    @Test
    fun fewerThanTwoPositionsOrOneTimeGiveNoVelocity() {
        assertEquals(Offset.Zero, estimator.velocity(maximum = 100_000f))
        estimator.add(5L, Offset(0f, 10f))
        assertEquals(Offset.Zero, estimator.velocity(maximum = 100_000f))
        estimator.add(5L, Offset(0f, 30f))
        assertEquals(Offset.Zero, estimator.velocity(maximum = 100_000f))
    }

    @Test
    fun twoPositionsGiveTheSpeedBetweenThem() {
        estimator.add(0L, Offset(0f, 0f))
        estimator.add(20L, Offset(0f, 30f))
        assertEquals(1500f, estimator.velocity(maximum = 100_000f).y, 0.5f)
    }
}
