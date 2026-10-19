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

class FontFuzzTest {
    /** Reads arbitrary bytes as a font file: the only exception may be [InvalidFontException]. */
    @FuzzTest
    fun fontFile(input: ByteArray) {
        try {
            FontFile.inspect(input, LIMITS)
        } catch (_: InvalidFontException) {}
    }

    private companion object {
        val LIMITS = FontLimits()
    }
}
