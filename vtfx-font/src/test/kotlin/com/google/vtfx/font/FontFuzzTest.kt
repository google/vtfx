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

import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.SanitizedFont

class FontFuzzTest {
    /**
     * Reads arbitrary bytes as a font file and as a saved sanitized font. The only exception may be
     * [InvalidFontException]; sanitizing a sanitized font gives the same bytes; and bytes that load
     * as a saved font are what sanitizing them gives, so the verifier accepts only fonts in the one
     * canonical encoding.
     */
    @FuzzTest
    fun fontFile(input: ByteArray) {
        val faces =
            try {
                FontFile.inspect(input, LIMITS)
            } catch (_: InvalidFontException) {
                emptyList()
            }
        for (face in faces.take(MAX_FACES)) {
            val font =
                try {
                    FontFile.sanitize(input, face.index, LIMITS)
                } catch (_: InvalidFontException) {
                    continue
                }
            val again = FontFile.sanitize(font.toByteArray(), 0, LIMITS)
            check(again == font) { "sanitizing face ${face.index} again changed it" }
        }

        val loaded =
            try {
                SanitizedFont.fromCanonical(input, LIMITS)
            } catch (_: InvalidFontException) {
                return
            }
        check(loaded.toByteArray().contentEquals(input)) { "loading changed the bytes" }
        val sanitized = FontFile.sanitize(input, 0, LIMITS)
        check(sanitized == loaded) { "the verifier accepted a font that sanitizing changes" }
    }

    private companion object {
        val LIMITS = FontLimits()

        /** The faces of one input that are sanitized, so that one run stays short. */
        const val MAX_FACES = 4
    }
}
