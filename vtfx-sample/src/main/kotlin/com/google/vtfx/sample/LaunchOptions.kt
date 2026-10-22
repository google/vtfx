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
    /** A language tag for the locale of Han glyphs, such as "ja", or null. */
    val locale: String?,
) {
    companion object {
        /** A boolean extra that turns on [logHostInput]. */
        const val EXTRA_LOG_HOST_INPUT = "logHostInput"

        /** A boolean extra that turns on [benchmark]. */
        const val EXTRA_BENCHMARK = "benchmark"

        /** A string extra for [locale]. */
        const val EXTRA_LOCALE = "locale"

        /**
         * Reads the extras with [booleanExtra] and [stringExtra]. Only a [debuggable] build takes
         * those for testing, which log what the user types or run the benchmark: the activity is
         * exported, so any app can start it with them (docs/decisions/0053).
         */
        fun read(
            debuggable: Boolean,
            booleanExtra: (String) -> Boolean,
            stringExtra: (String) -> String?,
        ): LaunchOptions =
            LaunchOptions(
                logHostInput = debuggable && booleanExtra(EXTRA_LOG_HOST_INPUT),
                benchmark = debuggable && booleanExtra(EXTRA_BENCHMARK),
                locale = stringExtra(EXTRA_LOCALE),
            )
    }
}
