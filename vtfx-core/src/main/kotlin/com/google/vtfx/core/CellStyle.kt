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

/** A color set by SGR: the default color, one of the 256 indexed colors, or a 24-bit RGB color. */
@JvmInline
public value class TerminalColor internal constructor(internal val bits: Int) {
    /** Whether this is the default foreground or background color. */
    public val isDefault: Boolean
        get() = kind == KIND_DEFAULT

    /** Whether this is one of the 256 indexed colors. */
    public val isIndexed: Boolean
        get() = kind == KIND_INDEXED

    /** Whether this is a 24-bit RGB color. */
    public val isRgb: Boolean
        get() = kind == KIND_RGB

    /** The palette index, if [isIndexed]. */
    public val index: Int
        get() {
            check(isIndexed) { "Not an indexed color: $this" }
            return bits and 0xFF
        }

    /** The color as 0xRRGGBB, if [isRgb]. */
    public val rgb: Int
        get() {
            check(isRgb) { "Not an RGB color: $this" }
            return bits and 0xFFFFFF
        }

    private val kind: Int
        get() = bits ushr 24

    override fun toString(): String =
        when (kind) {
            KIND_DEFAULT -> "default"
            KIND_INDEXED -> "indexed($index)"
            else -> "rgb(#%06x)".format(rgb)
        }

    public companion object {
        private const val KIND_DEFAULT = 0
        private const val KIND_INDEXED = 1
        private const val KIND_RGB = 2

        /** The default color. */
        public val Default: TerminalColor = TerminalColor(0)

        /** Returns the indexed color [index], which must be in 0..255. */
        public fun indexed(index: Int): TerminalColor {
            require(index in 0..255) { "index out of range: $index" }
            return TerminalColor((KIND_INDEXED shl 24) or index)
        }

        /** Returns the RGB color whose components are in 0..255. */
        public fun rgb(red: Int, green: Int, blue: Int): TerminalColor {
            require(red in 0..255 && green in 0..255 && blue in 0..255) { "component out of range" }
            return TerminalColor((KIND_RGB shl 24) or (red shl 16) or (green shl 8) or blue)
        }
    }
}

/** The style of an underline. The set is closed: a style is added only in a major version. */
public enum class UnderlineStyle {
    None,
    Single,
    Double,
    Curly,
    Dotted,
    Dashed,
}

// Packed into a Long so that a line stores styles without an object per cell. Bits 0-25 hold the
// foreground, 26-51 the background, and 52-63 the flags.
/** The rendition of a cell, set by SGR. */
@JvmInline
public value class CellStyle internal constructor(internal val bits: Long) {
    /** The color of the text (SGR 30-39, 90-97). */
    public val foreground: TerminalColor
        get() = TerminalColor((bits and COLOR_MASK).toInt())

    /** The color behind the text (SGR 40-49, 100-107). */
    public val background: TerminalColor
        get() = TerminalColor(((bits ushr BACKGROUND_SHIFT) and COLOR_MASK).toInt())

    /** SGR 1. */
    public val isBold: Boolean
        get() = hasFlag(BOLD)

    /** SGR 2. */
    public val isFaint: Boolean
        get() = hasFlag(FAINT)

    /** SGR 3. */
    public val isItalic: Boolean
        get() = hasFlag(ITALIC)

    /** SGR 5 or 6. */
    public val isBlinking: Boolean
        get() = hasFlag(BLINK)

    /** SGR 7: the foreground and background are swapped when displayed. */
    public val isInverse: Boolean
        get() = hasFlag(INVERSE)

    /** SGR 8: the text is not displayed. */
    public val isInvisible: Boolean
        get() = hasFlag(INVISIBLE)

    /** SGR 9. */
    public val isStrikethrough: Boolean
        get() = hasFlag(STRIKETHROUGH)

    /** SGR 53. */
    public val isOverlined: Boolean
        get() = hasFlag(OVERLINE)

    /** SGR 4 and its sub-parameters (4:0 to 4:5), and SGR 21. */
    public val underline: UnderlineStyle
        get() = UnderlineStyle.entries[((bits ushr UNDERLINE_SHIFT) and UNDERLINE_MASK).toInt()]

    internal fun withForeground(color: TerminalColor): CellStyle =
        CellStyle((bits and COLOR_MASK.inv()) or color.bits.toLong())

    internal fun withBackground(color: TerminalColor): CellStyle =
        CellStyle(
            (bits and (COLOR_MASK shl BACKGROUND_SHIFT).inv()) or
                (color.bits.toLong() shl BACKGROUND_SHIFT)
        )

    internal fun withFlag(flag: Long, enabled: Boolean): CellStyle =
        CellStyle(if (enabled) bits or flag else bits and flag.inv())

    internal fun withUnderline(style: UnderlineStyle): CellStyle =
        CellStyle(
            (bits and (UNDERLINE_MASK shl UNDERLINE_SHIFT).inv()) or
                (style.ordinal.toLong() shl UNDERLINE_SHIFT)
        )

    /** The style of cells erased with this style: only the background is kept (xterm's BCE). */
    internal fun erased(): CellStyle = CellStyle(bits and (COLOR_MASK shl BACKGROUND_SHIFT))

    private fun hasFlag(flag: Long): Boolean = bits and flag != 0L

    override fun toString(): String = buildList {
        if (!foreground.isDefault) add("fg=$foreground")
        if (!background.isDefault) add("bg=$background")
        if (isBold) add("bold")
        if (isFaint) add("faint")
        if (isItalic) add("italic")
        if (underline != UnderlineStyle.None) add("underline=$underline")
        if (isBlinking) add("blink")
        if (isInverse) add("inverse")
        if (isInvisible) add("invisible")
        if (isStrikethrough) add("strikethrough")
        if (isOverlined) add("overline")
    }
        .joinToString(prefix = "CellStyle(", postfix = ")")

    public companion object {
        private const val COLOR_MASK = (1L shl 26) - 1
        private const val BACKGROUND_SHIFT = 26
        private const val UNDERLINE_SHIFT = 52
        private const val UNDERLINE_MASK = 0x7L
        internal const val BOLD = 1L shl 55
        internal const val FAINT = 1L shl 56
        internal const val ITALIC = 1L shl 57
        internal const val BLINK = 1L shl 58
        internal const val INVERSE = 1L shl 59
        internal const val INVISIBLE = 1L shl 60
        internal const val STRIKETHROUGH = 1L shl 61
        internal const val OVERLINE = 1L shl 62

        /** Default colors and no attributes. */
        public val Default: CellStyle = CellStyle(0L)
    }
}
