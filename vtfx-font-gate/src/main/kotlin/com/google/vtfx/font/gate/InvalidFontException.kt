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

/**
 * Thrown when a font cannot be read or sanitized. [message][InvalidFontException.message] describes
 * the problem in English, for logs; [kind][InvalidFontException.kind] tells apps what to say to
 * users.
 *
 * The constructor is public because the readers of font files, in vtfx-font, throw it from outside
 * the module that defines it; apps have no reason to construct one.
 *
 * @param message What is wrong with the font, in English.
 */
public class InvalidFontException(
    /** What kind of problem the font has. */
    public val kind: Kind,
    message: String,
) : Exception(message) {
    // A value class rather than an enum, since vtfx returns it and may add kinds
    // (docs/decisions/0087).
    /** What kind of problem a font has. More kinds may be added. */
    @JvmInline
    public value class Kind private constructor(private val value: Int) {
        override fun toString(): String =
            when (this) {
                TooLarge -> "TooLarge"
                Unsupported -> "Unsupported"
                Malformed -> "Malformed"
                TooComplex -> "TooComplex"
                else -> "Kind($value)"
            }

        public companion object {
            /** The file or the sanitized font is larger than [FontLimits] allows. */
            public val TooLarge: Kind = Kind(0)

            /**
             * The font is valid, but uses a format or a feature that vtfx does not read, such as
             * WOFF, CFF2, or bitmap glyphs only, or has metrics out of the ranges that vtfx
             * accepts.
             */
            public val Unsupported: Kind = Kind(1)

            /** The data is not a valid font. */
            public val Malformed: Kind = Kind(2)

            /** A glyph has more points than [FontLimits] allows, or takes too much work to read. */
            public val TooComplex: Kind = Kind(3)
        }
    }
}
