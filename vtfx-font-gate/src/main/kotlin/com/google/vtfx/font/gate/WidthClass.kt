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

// A value class rather than an enum, since vtfx returns it and may add classes
// (docs/decisions/0037).
/**
 * How the advance widths of the characters of a font compare, for an app to warn about a font that
 * is not monospaced. The terminal draws any font in its cells; a proportional font just looks
 * uneven. More classes may be added.
 */
@JvmInline
public value class WidthClass private constructor(private val value: Int) {
    override fun toString(): String =
        when (this) {
            Monospaced -> "Monospaced"
            DualWidth -> "DualWidth"
            Proportional -> "Proportional"
            else -> "WidthClass($value)"
        }

    public companion object {
        /** Every character that is not of zero width has the same advance width. */
        public val Monospaced: WidthClass = WidthClass(0)

        /**
         * Every character that is not of zero width has one advance width or twice it, as the
         * monospaced fonts for Chinese, Japanese, and Korean have.
         */
        public val DualWidth: WidthClass = WidthClass(1)

        /** Neither of the others, or no character has a width. */
        public val Proportional: WidthClass = WidthClass(2)
    }
}
