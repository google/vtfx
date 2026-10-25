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
import com.google.vtfx.font.gate.SanitizedFont
import java.io.File
import kotlin.test.assertContentEquals
import org.junit.Test

/**
 * [FontFile.sanitize] writes the canonical fonts that vtfx-font-gate's tests check its verifier
 * with, byte for byte. vtfx-font-gate cannot depend on this module, so those tests read the fonts
 * from files, and this test keeps the files what this module writes. The files are also a golden of
 * the canonical form: apps save sanitized fonts and load them later with
 * [SanitizedFont.fromCanonical], so a change to these bytes is a change that fonts saved before it
 * may no longer load after (docs/decisions/0086).
 *
 * The fonts are built by the test code of this module, so no third party holds rights to them.
 *
 * To change the canonical form on purpose, or to add a fixture: run this test (`./gradlew
 * :vtfx-font:test --tests com.google.vtfx.font.CanonicalFixturesTest`). On a mismatch it writes
 * what sanitizing gave to `vtfx-font/build/canonical-fixtures/`. Review those files, and copy them
 * to `vtfx-font-gate/src/fuzz/resources/com/google/vtfx/font/gate/canonical/`, which is under
 * security review.
 */
class CanonicalFixturesTest {
    private val limits = FontLimits()

    private fun sanitized(input: ByteArray): ByteArray =
        FontFile.sanitize(input, 0, limits).toByteArray()

    /**
     * Checks that [actual] is the fixture [name]. If it is not, writes [actual] to the directory
     * that the KDoc of the class names, for review.
     */
    private fun assertIsFixture(name: String, actual: ByteArray) {
        val fixture = File(directory("vtfx.canonicalFixtures"), name)
        val expected = if (fixture.exists()) fixture.readBytes() else ByteArray(0)
        if (!expected.contentEquals(actual)) {
            val output = File(directory("vtfx.canonicalFixturesActual"), name)
            output.parentFile.mkdirs()
            output.writeBytes(actual)
        }
        assertContentEquals(
            expected,
            actual,
            "$name differs; see the KDoc of CanonicalFixturesTest",
        )
    }

    private fun directory(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "$property is set by the build file" })

    /** Composite glyphs, variation sequences in cmap format 14, and an empty glyph. */
    @Test
    fun variationsFontIsSanitizedToItsFixture() {
        val input = variationsTestFont().build()

        val font = sanitized(input)

        assertIsFixture("variations.ttf", font)
    }

    /**
     * Two advance widths, one twice the other; a bold italic style; coordinates that take one and
     * two bytes, negative ones, and runs of equal flags; and characters that merge into groups.
     */
    @Test
    fun dualWidthFontIsSanitizedToItsFixture() {
        val input =
            TestTrueTypeFont(
                    glyphs =
                        listOf(
                            TestGlyph.Simple(listOf(square(50, 0, 400))),
                            TestGlyph.Simple(
                                listOf(
                                    listOf(
                                        P(10, -200),
                                        P(20, -200),
                                        P(30, -200),
                                        P(40, -200),
                                        P(40, 700),
                                        P(-30, 700, on = false),
                                        P(10, 600),
                                    )
                                ),
                                advance = 500,
                            ),
                            TestGlyph.Simple(
                                listOf(square(100, 0, 800), square(300, 200, 400).reversed()),
                                advance = 1000,
                            ),
                        ),
                    characters = mapOf(0x61 to 1, 0x62 to 2, 0x3042 to 2),
                    cmapFormat = 4,
                    macStyle = 3,
                    italicAngle = -12 shl 16,
                )
                .build()

        val font = sanitized(input)

        assertIsFixture("dual-width.ttf", font)
    }

    /** Outlines from CFF, with a curve that the writer approximates with quadratic ones. */
    @Test
    fun cffFontIsSanitizedToItsFixture() {
        val input =
            TestCffFont(
                    glyphs =
                        listOf(
                            "endchar",
                            "100 0 rmoveto 300 0 rlineto 0 300 rlineto -300 0 rlineto endchar",
                            "50 0 rmoveto 0 200 200 200 200 -200 rrcurveto endchar",
                        ),
                    characters = mapOf(0x41 to 1, 0x42 to 2),
                )
                .build()

        val font = sanitized(input)

        assertIsFixture("cff.ttf", font)
    }

    /**
     * A glyph with fewer points than the glyph before it, whose points the verifier must not read
     * as its own, and three advance widths.
     */
    @Test
    fun fewerPointsFontIsSanitizedToItsFixture() {
        val input =
            TestTrueTypeFont(
                    glyphs =
                        listOf(
                            TestGlyph.Simple(listOf(square(0, 0, 1000), square(100, 100, 800))),
                            TestGlyph.Simple(listOf(square(300, 300, 100))),
                            TestGlyph.Simple(listOf(square(100, 0, 500)), advance = 700),
                        ),
                    characters = mapOf(0x41 to 1, 0x42 to 2),
                )
                .build()

        val font = sanitized(input)

        assertIsFixture("fewer-points.ttf", font)
    }
}

/**
 * A test font with composite glyphs, variation sequences in cmap format 14, and an empty glyph; the
 * input of the fixture variations.ttf.
 */
internal fun variationsTestFont(): TestTrueTypeFont =
    TestTrueTypeFont(
        glyphs =
            listOf(
                TestGlyph.Simple(listOf(square(0, 0, 500))),
                TestGlyph.Simple(
                    listOf(
                        listOf(P(0, 0), P(300, 700, on = false), P(600, 0)),
                        square(1000, 1000, 1),
                    )
                ),
                TestGlyph.Composite(listOf(TestComponent(1, 0, 0), TestComponent(0, 10, 300))),
                TestGlyph.Empty(),
            ),
        characters = mapOf(0x41 to 1, 0x42 to 2, 0x43 to 3, 0x845B to 1),
        variations = mapOf(0xE0100 to Pair(listOf(0x845B), mapOf(0x845B to 2))),
    )
