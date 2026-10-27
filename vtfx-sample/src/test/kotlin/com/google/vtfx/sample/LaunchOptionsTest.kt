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
package com.google.vtfx.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchOptionsTest {
    private val extras =
        mapOf(
            LaunchOptions.EXTRA_LOG_HOST_INPUT to true,
            LaunchOptions.EXTRA_BENCHMARK to true,
            LaunchOptions.EXTRA_THROUGHPUT to true,
        )

    private val strings = mutableMapOf("locale" to "ja")

    private fun read(debuggable: Boolean, profileable: Boolean) =
        LaunchOptions.read(
            debuggable,
            profileable,
            { extras[it] ?: false },
            { strings[it] },
        )

    @Test
    fun aDebuggableBuildTakesEveryExtra() {
        val options = read(debuggable = true, profileable = false)
        assertTrue(options.logHostInput)
        assertTrue(options.benchmark)
        assertTrue(options.throughput)
        assertEquals("ja", options.locale)
    }

    @Test
    fun aReleaseBuildDoesNotLogInputOrRunTheBenchmarkForAnotherApp() {
        // The activity is exported: any app can start it with these extras.
        val options = read(debuggable = false, profileable = false)
        assertFalse(options.logHostInput)
        assertFalse(options.benchmark)
        assertFalse(options.throughput)
        assertEquals("ja", options.locale)
    }

    @Test
    fun theBenchmarkBuildRunsTheBenchmark() {
        // The benchmark build is profileable by the shell, not debuggable.
        val options = read(debuggable = false, profileable = true)

        assertTrue(options.benchmark)
        assertTrue(options.throughput)
    }

    @Test
    fun theThroughputBenchmarkMeasuresAllWorkloadsForThreeSecondsByDefault() {
        val options = read(debuggable = false, profileable = true)

        assertEquals(null, options.workload)
        assertEquals(3.0, options.seconds, 0.0)
    }

    @Test
    fun theThroughputBenchmarkTakesAWorkloadAndADuration() {
        strings[LaunchOptions.EXTRA_WORKLOAD] = "ascii"
        strings[LaunchOptions.EXTRA_SECONDS] = "30"

        val options = read(debuggable = false, profileable = true)

        assertEquals("ascii", options.workload)
        assertEquals(30.0, options.seconds, 0.0)
    }

    @Test
    fun aDurationThatIsNotAPositiveNumberIsTheDefault() {
        for (seconds in listOf("x", "0", "-1")) {
            strings[LaunchOptions.EXTRA_SECONDS] = seconds

            assertEquals(seconds, 3.0, read(debuggable = true, profileable = false).seconds, 0.0)
        }
    }

    @Test
    fun theBenchmarkBuildDoesNotLogInputForAnotherApp() {
        val options = read(debuggable = false, profileable = true)

        assertFalse(options.logHostInput)
    }
}
