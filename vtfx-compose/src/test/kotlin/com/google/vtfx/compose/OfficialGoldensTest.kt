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

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import com.google.vtfx.testing.ExpectedFailures
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the provisional goldens in src/test/screenshots against the official goldens that humans
 * promoted to spec/screenshots (docs/design/testing.md section 5).
 *
 * The screenshot tests compare what is drawn with the provisional goldens, so an official golden
 * with the same pixels as its provisional golden also matches what is drawn. An official golden
 * that no longer matches is listed in `spec-expected-failures.txt` as `screenshots/<file name>`
 * until a human promotes the new provisional golden (section 6).
 */
@RunWith(TestParameterInjector::class)
class OfficialGoldensTest {
    private val expectedFailures = ExpectedFailures.load(OfficialGoldensTest::class.java)

    @Test
    fun provisionalGoldenMatchesTheOfficialGolden(
        @TestParameter(valuesProvider = Goldens::class) golden: File
    ) {
        val id = id(golden)
        expectedFailures.verify(id) {
            difference(golden, File(provisional, golden.name))?.let {
                throw AssertionError("$id: $it")
            }
        }
    }

    @Test
    fun expectedFailuresListOnlyOfficialGoldens() {
        expectedFailures.verifyListsOnly(goldens().map { id(it) })
    }

    /** Returns how [actual] differs from [expected], or null if their pixels are the same. */
    private fun difference(expected: File, actual: File): String? {
        if (!actual.isFile) return "no provisional golden $actual"
        val expectedImage = ImageIO.read(expected) ?: error("Not an image: $expected")
        val actualImage = ImageIO.read(actual) ?: error("Not an image: $actual")
        if (
            expectedImage.width != actualImage.width || expectedImage.height != actualImage.height
        ) {
            return "the size is ${actualImage.width}x${actualImage.height}, " +
                "not ${expectedImage.width}x${expectedImage.height}"
        }
        var differing = 0
        for (y in 0 until expectedImage.height) {
            for (x in 0 until expectedImage.width) {
                if (expectedImage.getRGB(x, y) != actualImage.getRGB(x, y)) differing++
            }
        }
        return if (differing == 0) null else "$differing pixels differ"
    }

    private class Goldens : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<*> =
            goldens().map { value(it).withName(it.name) }
    }

    private companion object {
        val official = directory("vtfx.officialGoldensDir")
        val provisional = directory("vtfx.provisionalGoldensDir")

        fun directory(property: String): File =
            File(System.getProperty(property) ?: error("$property is not set"))

        fun goldens(): List<File> {
            val goldens =
                official.listFiles { file -> file.name.endsWith(".png") }?.sortedBy { it.name }
                    ?: error("No official goldens: $official")
            check(goldens.isNotEmpty()) { "No official goldens: $official" }
            return goldens
        }

        /** The id of [golden] in spec-expected-failures.txt. */
        fun id(golden: File): String = "screenshots/${golden.name}"
    }
}
