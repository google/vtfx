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

// The tables (UnicodeWidthTables) are generated from the Unicode Character Database in
// third_party/unicode by scripts/generate_unicode_tables.py. The policy is in
// docs/decisions/0011.
/**
 * The number of cells that a code point occupies. The terminal model uses it for host output, and
 * the display uses it for text that is not in the model, such as the IME's composing text.
 *
 * The widths follow the Unicode Character Database of [UNICODE_VERSION] with glibc's rules, so that
 * they agree with the hosts' wcwidth().
 *
 * Stateless, so it may be used from any thread.
 */
public object CharWidth {
    // A plain val rather than a const, so that apps read the version of the tables they run with
    // (docs/decisions/0037).
    /** The Unicode version of the tables. */
    public val UNICODE_VERSION: String = UnicodeWidthTables.UNICODE_VERSION

    /**
     * Returns 0 for characters that combine with the previous one, and 1 or 2 otherwise. East Asian
     * Ambiguous characters are [ambiguousWidth] wide, except U+10EEEE, the placeholder of an image
     * of the kitty graphics protocol, which takes one cell as in kitty.
     */
    public fun of(codePoint: Int, ambiguousWidth: AmbiguousWidth = AmbiguousWidth.Narrow): Int {
        val kind = if (codePoint < BMP_SIZE) BMP[codePoint].toInt() else kindOf(codePoint)
        return when (kind) {
            ZERO -> 0
            WIDE -> 2
            AMBIGUOUS ->
                if (ambiguousWidth == AmbiguousWidth.Wide && codePoint != IMAGE_PLACEHOLDER) 2
                else 1
            else -> 1
        }
    }

    /** The placeholder of an image of the kitty graphics protocol (docs/decisions/0038). */
    private const val IMAGE_PLACEHOLDER = 0x10EEEE

    private const val NARROW = 0
    private const val ZERO = 1
    private const val WIDE = 2
    private const val AMBIGUOUS = 3
    private const val BMP_SIZE = 0x10000

    private fun kindOf(codePoint: Int): Int =
        when {
            contains(UnicodeWidthTables.ZERO_WIDTH, codePoint) -> ZERO
            contains(UnicodeWidthTables.WIDE, codePoint) -> WIDE
            contains(UnicodeWidthTables.AMBIGUOUS, codePoint) -> AMBIGUOUS
            else -> NARROW
        }

    /** The kind of every code point of the BMP, so that the common case is one array access. */
    private val BMP =
        ByteArray(BMP_SIZE).also { table ->
            for ((ranges, kind) in
                listOf(
                    UnicodeWidthTables.AMBIGUOUS to AMBIGUOUS,
                    UnicodeWidthTables.WIDE to WIDE,
                    UnicodeWidthTables.ZERO_WIDTH to ZERO,
                )) {
                for (i in ranges.indices step 2) {
                    if (ranges[i] >= BMP_SIZE) break
                    table.fill(kind.toByte(), ranges[i], minOf(ranges[i + 1], BMP_SIZE - 1) + 1)
                }
            }
        }

    /** Returns whether [codePoint] is in one of the sorted, inclusive [ranges]. */
    private fun contains(ranges: IntArray, codePoint: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < ranges[mid * 2] -> high = mid - 1
                codePoint > ranges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

/**
 * The width of East Asian Ambiguous characters (UAX #11), such as "○", "※", and Greek and Cyrillic
 * letters. It must match the host's setting (for example, vim's 'ambiwidth' and the locale's
 * wcwidth), or the cursor goes out of place.
 */
public enum class AmbiguousWidth {
    /** One cell, as wcwidth() in most locales and as vtfx's default. */
    Narrow,

    /** Two cells, as in some East Asian legacy environments. */
    Wide,
}
