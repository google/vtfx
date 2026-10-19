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

// The defaults are chosen in docs/decisions/0040.
/**
 * Bounds on the resources that reading a font may use. A font beyond a limit is rejected with
 * [InvalidFontException.Kind.TooLarge] or [InvalidFontException.Kind.TooComplex]. Immutable.
 */
public class FontLimits(
    // Integer literals here have no digit separators: Dokka 2.2 leaves a value with them out of
    // the API reference.
    /**
     * The largest font file, in bytes. The default holds one weight of the Noto Sans CJK
     * collection, about 20 MB, with room to spare.
     */
    public val maxInputBytes: Int = 64 * 1024 * 1024,
    /**
     * The largest sanitized font, in bytes. Outlines converted from CFF take more space than the
     * original, typically up to twice as much.
     */
    public val maxOutputBytes: Int = 64 * 1024 * 1024,
    /**
     * The most points in one glyph, after composite glyphs are flattened and CFF outlines are
     * converted.
     */
    public val maxGlyphPoints: Int = 16384,
) {
    init {
        require(maxInputBytes in 0..MAX_BYTES) { "maxInputBytes out of range: $maxInputBytes" }
        require(maxOutputBytes in 0..MAX_BYTES) { "maxOutputBytes out of range: $maxOutputBytes" }
        require(maxGlyphPoints in 0..MAX_GLYPH_POINTS) {
            "maxGlyphPoints out of range: $maxGlyphPoints"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FontLimits &&
            other.maxInputBytes == maxInputBytes &&
            other.maxOutputBytes == maxOutputBytes &&
            other.maxGlyphPoints == maxGlyphPoints

    override fun hashCode(): Int = listOf(maxInputBytes, maxOutputBytes, maxGlyphPoints).hashCode()

    override fun toString(): String =
        "FontLimits(maxInputBytes=$maxInputBytes, maxOutputBytes=$maxOutputBytes, " +
            "maxGlyphPoints=$maxGlyphPoints)"

    public companion object {
        // Plain vals rather than consts, so that apps read the values of the vtfx they run with,
        // not those they were compiled with (docs/decisions/0037).
        /**
         * The largest [maxInputBytes][FontLimits.maxInputBytes] and
         * [maxOutputBytes][FontLimits.maxOutputBytes]: 256 MiB.
         */
        public val MAX_BYTES: Int = 256 * 1024 * 1024

        /**
         * The largest [maxGlyphPoints][FontLimits.maxGlyphPoints]: the most points that a TrueType
         * glyph can have.
         */
        public val MAX_GLYPH_POINTS: Int = 65535
    }
}
