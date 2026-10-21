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

package com.google.vtfx.font.gate

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the corpus of [VerifierFuzzer] (docs/design/testing.md section 7): every file of
 * src/fuzz/corpus/VerifierFuzzer, and the empty input. Each input has the time limit at which
 * fuzzing reports a hang.
 */
@RunWith(TestParameterInjector::class)
class VerifierFuzzerCorpusTest {
    @Test(timeout = HANG_MILLIS)
    fun corpus(@TestParameter(valuesProvider = Corpus::class) input: File) {
        VerifierFuzzer.fuzzerTestOneInput(input.readBytes())
    }

    @Test(timeout = HANG_MILLIS)
    fun emptyInput() {
        VerifierFuzzer.fuzzerTestOneInput(ByteArray(0))
    }

    private class Corpus : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<*> {
            val directory =
                File(
                    System.getProperty("vtfx.fuzzCorpusDir")
                        ?: error("vtfx.fuzzCorpusDir is not set")
                )
            val files =
                directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                    ?: error("No corpus: $directory")
            check(files.isNotEmpty()) { "No inputs in $directory" }
            return files.map { value(it).withName(it.name) }
        }
    }

    private companion object {
        const val HANG_MILLIS = 10_000L
    }
}
