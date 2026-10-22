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
        )

    private fun read(debuggable: Boolean) =
        LaunchOptions.read(
            debuggable,
            { extras[it] ?: false },
            { if (it == "locale") "ja" else null },
        )

    @Test
    fun aDebuggableBuildTakesEveryExtra() {
        val options = read(debuggable = true)
        assertTrue(options.logHostInput)
        assertTrue(options.benchmark)
        assertEquals("ja", options.locale)
    }

    @Test
    fun aReleaseBuildDoesNotLogInputOrRunTheBenchmarkForAnotherApp() {
        // The activity is exported: any app can start it with these extras.
        val options = read(debuggable = false)
        assertFalse(options.logHostInput)
        assertFalse(options.benchmark)
        assertEquals("ja", options.locale)
    }
}
