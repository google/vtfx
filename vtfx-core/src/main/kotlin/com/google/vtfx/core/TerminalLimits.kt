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

package com.google.vtfx.core

// The defaults are chosen in docs/decisions/0002.
/**
 * Bounds on the resources that host output can make the terminal use. Input beyond a limit is
 * dropped. Immutable.
 */
public class TerminalLimits(
    // Integer literals here have no digit separators: Dokka 2.2 leaves a value with them out of
    // the API reference.
    /**
     * The number of lines kept after they scroll off the top of the main screen. Lines that take
     * much memory, such as lines full of combining characters, are kept fewer, within a budget that
     * grows with this number and the width.
     */
    public val scrollbackLines: Int = 10000,
    /**
     * The longest OSC or DCS string, in UTF-16 code units, that is not discarded. A character
     * outside the Basic Multilingual Plane, such as an emoji, counts as two.
     */
    public val maxStringLength: Int = 256 * 1024,
) {
    init {
        require(scrollbackLines in 0..MAX_SCROLLBACK_LINES) {
            "scrollbackLines out of range: $scrollbackLines"
        }
        require(maxStringLength in 0..MAX_STRING_LENGTH) {
            "maxStringLength out of range: $maxStringLength"
        }
    }

    public companion object {
        // Plain vals rather than consts, so that apps read the values of the vtfx they run with,
        // not
        // those they were compiled with (docs/decisions/0037).
        /** The largest number of columns or rows. */
        public val MAX_DIMENSION: Int = 2048

        /** The largest [scrollbackLines][TerminalLimits.scrollbackLines]. */
        public val MAX_SCROLLBACK_LINES: Int = 1000000

        /**
         * The largest [maxStringLength][TerminalLimits.maxStringLength]: 16 Mi UTF-16 code units.
         */
        public val MAX_STRING_LENGTH: Int = 16 * 1024 * 1024
    }
}
