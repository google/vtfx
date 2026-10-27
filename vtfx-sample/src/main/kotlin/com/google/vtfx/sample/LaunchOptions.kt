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

/**
 * What the extras of the intent that starts [MainActivity] ask for (section 1 of
 * docs/device-checks.md).
 */
internal class LaunchOptions(
    /** Whether to write the bytes sent to the host to logcat ([SampleViewModel.logHostInput]). */
    val logHostInput: Boolean,
    /** Whether to run [RenderBenchmark]. */
    val benchmark: Boolean,
    /** Whether to run [ThroughputBenchmark]. */
    val throughput: Boolean,
    /** The only workload that [ThroughputBenchmark] measures, or null for all of them. */
    val workload: String?,
    /** How many seconds [ThroughputBenchmark] measures each workload for. */
    val seconds: Double,
    /** A language tag for the locale of Han glyphs, such as "ja", or null. */
    val locale: String?,
) {
    companion object {
        /** A boolean extra that turns on [logHostInput]. */
        const val EXTRA_LOG_HOST_INPUT = "logHostInput"

        /** A boolean extra that turns on [benchmark]. */
        const val EXTRA_BENCHMARK = "benchmark"

        /** A boolean extra that turns on [throughput]. */
        const val EXTRA_THROUGHPUT = "throughput"

        /** A string extra for [workload]. */
        const val EXTRA_WORKLOAD = "workload"

        /** A string extra for [seconds], a decimal number. */
        const val EXTRA_SECONDS = "seconds"

        /** A string extra for [locale]. */
        const val EXTRA_LOCALE = "locale"

        /** The default of [seconds], as in `TerminalBenchmark` of vtfx-core. */
        const val DEFAULT_SECONDS = 3.0

        /**
         * Reads the extras with [booleanExtra] and [stringExtra]. The activity is exported, so any
         * app can start it with them: only a [debuggable] build takes the one that logs what the
         * user types, and only a debuggable build or one [profileable] by the shell, as the
         * benchmark build is, takes the ones that run the benchmarks (docs/decisions/0089).
         */
        fun read(
            debuggable: Boolean,
            profileable: Boolean,
            booleanExtra: (String) -> Boolean,
            stringExtra: (String) -> String?,
        ): LaunchOptions =
            LaunchOptions(
                logHostInput = debuggable && booleanExtra(EXTRA_LOG_HOST_INPUT),
                benchmark = (debuggable || profileable) && booleanExtra(EXTRA_BENCHMARK),
                throughput = (debuggable || profileable) && booleanExtra(EXTRA_THROUGHPUT),
                workload = stringExtra(EXTRA_WORKLOAD),
                seconds =
                    stringExtra(EXTRA_SECONDS)?.toDoubleOrNull()?.takeIf { it > 0 }
                        ?: DEFAULT_SECONDS,
                locale = stringExtra(EXTRA_LOCALE),
            )
    }
}
