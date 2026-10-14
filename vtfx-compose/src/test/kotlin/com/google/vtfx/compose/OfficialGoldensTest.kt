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

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks the provisional goldens in src/test/screenshots against the official goldens that humans
 * promoted to spec/screenshots (docs/design/testing.md section 5).
 *
 * The screenshot tests compare what is drawn with the provisional goldens, so an official golden
 * with the same pixels as its provisional golden also matches what is drawn. An official golden
 * that no longer matches is listed in `spec-expected-failures.txt` as `screenshots/<file name>`
 * until a human promotes the new provisional golden (section 6).
 */
class OfficialGoldensTest {
    private val official = directory("vtfx.officialGoldensDir")
    private val provisional = directory("vtfx.provisionalGoldensDir")

    @Test
    fun provisionalGoldensMatchTheOfficialGoldens() {
        val goldens =
            official.listFiles { file -> file.name.endsWith(".png") }?.sortedBy { it.name }
                ?: error("No official goldens: $official")
        check(goldens.isNotEmpty()) { "No official goldens: $official" }
        val expectedFailures = loadExpectedFailures()

        val problems = mutableListOf<String>()
        for (golden in goldens) {
            val id = "$PREFIX${golden.name}"
            val difference = difference(golden, File(provisional, golden.name))
            if (id !in expectedFailures && difference != null) {
                problems += "$id: $difference"
            } else if (id in expectedFailures && difference == null) {
                problems += "$id matches; remove it from spec-expected-failures.txt"
            }
        }
        val stale = expectedFailures - goldens.map { "$PREFIX${it.name}" }.toSet()
        for (id in stale.sorted()) {
            problems += "$id in spec-expected-failures.txt is not an official golden"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
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

    private fun loadExpectedFailures(): Set<String> =
        OfficialGoldensTest::class
            .java
            .getResource("/spec-expected-failures.txt")
            ?.readText()
            .orEmpty()
            .lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private companion object {
        const val PREFIX = "screenshots/"

        fun directory(property: String): File =
            File(System.getProperty(property) ?: error("$property is not set"))
    }
}
