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

/**
 * The colors that the host set with OSC 4, 10, 11, and 12, which the display draws in place of its
 * own (docs/decisions/0096). A color the host did not set, or reset, is the display's. Immutable,
 * and equal to colors with the same values.
 *
 * @suppress
 */
@InternalVtfxApi
public class HostColors private constructor(private val colors: IntArray) {
    /** Returns [target] as 0xRRGGBB if the host set it, or null for the display's color. */
    public fun rgb(target: ColorTarget): Int? = colors[slot(target)].takeIf { it != UNSET }

    /**
     * Returns these colors with [edit] applied to a copy, or these colors if it changed nothing. An
     * OSC changes its colors in one copy, however many it sets.
     */
    internal fun edit(edit: (Editor) -> Unit): HostColors {
        val editor = Editor(colors.copyOf())
        edit(editor)
        return if (editor.colors.contentEquals(colors)) this else HostColors(editor.colors)
    }

    /** The colors being edited; [rgb] sees the changes made so far. */
    internal class Editor(internal val colors: IntArray) {
        fun rgb(target: ColorTarget): Int? = colors[slot(target)].takeIf { it != UNSET }

        /** Sets [target] to [rgb], 0xRRGGBB. */
        fun set(target: ColorTarget, rgb: Int) {
            require(rgb in 0..0xFFFFFF) { "color out of range: $rgb" }
            colors[slot(target)] = rgb
        }

        /** Returns [target] to the display's color. */
        fun reset(target: ColorTarget) {
            colors[slot(target)] = UNSET
        }

        /** Returns the 256 indexed colors to the display's. */
        fun resetPalette() {
            colors.fill(UNSET, 0, PALETTE_SIZE)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is HostColors && colors.contentEquals(other.colors)

    override fun hashCode(): Int = colors.contentHashCode()

    override fun toString(): String =
        "HostColors(" +
            colors
                .withIndex()
                .filter { it.value != UNSET }
                .joinToString { (slot, rgb) -> "${slotName(slot)}=#%06x".format(rgb) } +
            ")"

    @InternalVtfxApi
    public companion object {
        private const val PALETTE_SIZE = 256
        private const val FOREGROUND = PALETTE_SIZE
        private const val BACKGROUND = PALETTE_SIZE + 1
        private const val CURSOR = PALETTE_SIZE + 2
        private const val UNSET = -1

        /** No color set by the host. */
        public val None: HostColors = HostColors(IntArray(PALETTE_SIZE + 3) { UNSET })

        private fun slot(target: ColorTarget): Int =
            when (target) {
                ColorTarget.Foreground -> FOREGROUND
                ColorTarget.Background -> BACKGROUND
                ColorTarget.Cursor -> CURSOR
                is ColorTarget.Palette -> {
                    require(target.index in 0 until PALETTE_SIZE) {
                        "index out of range: ${target.index}"
                    }
                    target.index
                }
            }

        private fun slotName(slot: Int): String =
            when (slot) {
                FOREGROUND -> "foreground"
                BACKGROUND -> "background"
                CURSOR -> "cursor"
                else -> "$slot"
            }

        /**
         * Parses a color as XParseColor does for `rgb:R/G/B`, with 1 to 4 hexadecimal digits for
         * each component, scaled to its range, and `#RGB`, `#RRGGBB`, `#RRRGGGBBB`, or
         * `#RRRRGGGGBBBB`, whose components are the high bits. Returns 0xRRGGBB, the high 8 bits of
         * each 16-bit component as an X server with 8 bits per component keeps them, or null for
         * any other form, including X11 color names (docs/decisions/0096).
         */
        internal fun parse(spec: String): Int? =
            when {
                spec.startsWith("rgb:", ignoreCase = true) -> parseRgb(spec.substring(4))
                spec.startsWith("#") -> parseSharp(spec.substring(1))
                else -> null
            }

        private fun parseRgb(components: String): Int? {
            val parts = components.split('/')
            if (parts.size != 3) return null
            var rgb = 0
            for (part in parts) {
                val value = hex(part) ?: return null
                val max = (1 shl (4 * part.length)) - 1
                // In Long: 4 digits times 0xFFFF overflow an Int.
                rgb = rgb shl 8 or (value * 0xFFFFL / max shr 8).toInt()
            }
            return rgb
        }

        private fun parseSharp(digits: String): Int? {
            if (digits.length !in setOf(3, 6, 9, 12)) return null
            val length = digits.length / 3
            var rgb = 0
            for (i in 0 until 3) {
                val value = hex(digits.substring(i * length, (i + 1) * length)) ?: return null
                rgb = rgb shl 8 or (value shl (16 - 4 * length) shr 8)
            }
            return rgb
        }

        /** Returns 1 to 4 hexadecimal digits as a number, or null. */
        private fun hex(digits: String): Int? {
            if (digits.length !in 1..4 || !digits.all { it in HEX_DIGITS }) return null
            return digits.toInt(16)
        }

        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
    }
}
