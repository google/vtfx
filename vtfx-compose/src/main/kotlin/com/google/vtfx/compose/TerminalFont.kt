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

@file:OptIn(InternalVtfxApi::class)

package com.google.vtfx.compose

import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.font.FontFile
import com.google.vtfx.font.gate.SanitizedFamily
import com.google.vtfx.font.gate.SanitizedFont
import com.google.vtfx.font.gate.android.SanitizedTypefaces

// The fonts that are accepted are chosen in docs/decisions/0086.
/**
 * The font of a terminal: a font family of the system, fonts bundled in the app's resources, or
 * fonts that users chose, sanitized. It should be monospaced: the cell size is measured from it.
 *
 * Fonts are parsed by native code in the app's process, where a malicious font file could take over
 * the app and what it holds, such as keys and connections. So only fonts that come with the system
 * or with the app's own signed package are accepted as they are, and a font file from anywhere else
 * only as a [SanitizedFont], which vtfx wrote itself in a simple form that it checked. Files, URIs,
 * and downloaded fonts are never read directly.
 */
@Immutable
public sealed interface TerminalFont {
    /**
     * A font family of the system. If the system has no family of that name, its default font is
     * used.
     *
     * @property family The name of the font family, such as "monospace" or "serif-monospace".
     */
    public class System(public val family: String = "monospace") : TerminalFont {
        override fun equals(other: Any?): Boolean = other is System && other.family == family

        override fun hashCode(): Int = family.hashCode()

        override fun toString(): String = "TerminalFont.System($family)"
    }

    /**
     * Fonts in the app's `res/font`. Without [bold][TerminalFont.Resources.bold],
     * [italic][TerminalFont.Resources.italic], or [boldItalic][TerminalFont.Resources.boldItalic],
     * the system synthesizes them from [regular][TerminalFont.Resources.regular].
     */
    public class Resources(
        /** The regular font. */
        @FontRes public val regular: Int,
        /** The bold font, or null to synthesize it. */
        @FontRes public val bold: Int? = null,
        /** The italic font, or null to synthesize it. */
        @FontRes public val italic: Int? = null,
        /** The bold italic font, or null to synthesize it. */
        @FontRes public val boldItalic: Int? = null,
    ) : TerminalFont {
        override fun equals(other: Any?): Boolean =
            other is Resources &&
                other.regular == regular &&
                other.bold == bold &&
                other.italic == italic &&
                other.boldItalic == boldItalic

        override fun hashCode(): Int = listOf(regular, bold, italic, boldItalic).hashCode()

        override fun toString(): String =
            "TerminalFont.Resources($regular, $bold, $italic, $boldItalic)"
    }

    /**
     * Fonts that users chose, sanitized with [FontFile.sanitize]: a list of families, each tried in
     * turn for a character, such as a Latin font and then a CJK font. Characters in none of them
     * are drawn with the system's monospace font and its fallbacks.
     *
     * The cell size is measured from the first family.
     *
     * @property families The families, at least one and at most [MAX_FAMILIES].
     */
    public class Sanitized(public val families: List<SanitizedFamily>) : TerminalFont {
        init {
            require(families.size in 1..MAX_FAMILIES) {
                "${families.size} families out of 1..$MAX_FAMILIES"
            }
        }

        override fun equals(other: Any?): Boolean = other is Sanitized && other.families == families

        override fun hashCode(): Int = families.hashCode()

        override fun toString(): String = "TerminalFont.Sanitized($families)"

        public companion object {
            // A plain val rather than a const, so that apps read the value of the vtfx they run
            // with (docs/decisions/0037). The platform takes this many fallback families.
            /** The most families that [Sanitized] takes. */
            public val MAX_FAMILIES: Int = 64
        }
    }
}

/** The typefaces of a font family for each combination of bold and italic. */
internal data class TextTypefaces(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
) {
    companion object {
        /**
         * Returns the typefaces that [fontFamily] resolves to now. A font that loads
         * asynchronously, such as a downloadable font, is a fallback until it has loaded; use
         * [rememberTextTypefaces] to follow it.
         */
        fun resolve(fontFamily: FontFamily, resolver: FontFamily.Resolver): TextTypefaces =
            resolveStates(fontFamily, resolver).current()

        private fun resolveStates(
            fontFamily: FontFamily,
            resolver: FontFamily.Resolver,
        ): List<State<Any>> = STYLES.map { (weight, style) ->
            resolver.resolve(fontFamily, weight, style)
        }

        private fun List<State<Any>>.current(): TextTypefaces {
            val typefaces = map { it.value as Typeface }
            return TextTypefaces(typefaces[0], typefaces[1], typefaces[2], typefaces[3])
        }

        /**
         * Returns the typefaces of [fontFamily], read in the composition, so that it recomposes
         * when a font that loads asynchronously has loaded. Without that, the terminal keeps
         * drawing with the typeface resolved before the font loaded.
         */
        @Composable
        fun rememberTextTypefaces(
            fontFamily: FontFamily,
            resolver: FontFamily.Resolver,
        ): TextTypefaces =
            remember(fontFamily, resolver) { resolveStates(fontFamily, resolver) }.current()
    }
}

/**
 * The typefaces of [font] for each combination of bold and italic, read in the composition, so that
 * the terminal follows a font that resolves asynchronously.
 */
@Composable
internal fun rememberTextTypefaces(
    font: TerminalFont,
    resolver: FontFamily.Resolver,
): TextTypefaces =
    when (font) {
        is TerminalFont.System ->
            TextTypefaces.rememberTextTypefaces(remember(font) { font.toFontFamily() }, resolver)
        is TerminalFont.Resources ->
            TextTypefaces.rememberTextTypefaces(remember(font) { font.toFontFamily() }, resolver)
        is TerminalFont.Sanitized -> remember(font) { sanitizedTypefaces(font) }
    }

/**
 * The typefaces that [font] resolves to now, outside a composition, for the tests that draw as the
 * terminal does. A font that loads asynchronously is a fallback until it has loaded.
 */
internal fun resolveTextTypefaces(
    font: TerminalFont,
    resolver: FontFamily.Resolver,
): TextTypefaces =
    when (font) {
        is TerminalFont.System -> TextTypefaces.resolve(font.toFontFamily(), resolver)
        is TerminalFont.Resources -> TextTypefaces.resolve(font.toFontFamily(), resolver)
        is TerminalFont.Sanitized -> sanitizedTypefaces(font)
    }

/**
 * The typefaces of [font]. Only vtfx-font-gate-android makes the regular one from the fonts' data;
 * the others are derived from it.
 */
internal fun sanitizedTypefaces(font: TerminalFont.Sanitized): TextTypefaces {
    val regular = SanitizedTypefaces.regular(font.families)
    val normal = FontWeight.Normal.weight
    val bold = FontWeight.Bold.weight
    // Each family gives its closest font for these styles, and the system synthesizes the rest.
    return TextTypefaces(
        regular = regular,
        bold = Typeface.create(regular, bold, false),
        italic = Typeface.create(regular, normal, true),
        boldItalic = Typeface.create(regular, bold, true),
    )
}

/** The Compose font family of this font. */
internal fun TerminalFont.System.toFontFamily(): FontFamily =
    GENERIC_FAMILIES[family]
        ?: FontFamily(
            STYLES.map { (weight, style) -> Font(DeviceFontFamilyName(family), weight, style) }
        )

/** The Compose font family of these fonts. */
internal fun TerminalFont.Resources.toFontFamily(): FontFamily =
    FontFamily(
        listOfNotNull(
            Font(regular),
            bold?.let { Font(it, FontWeight.Bold) },
            italic?.let { Font(it, FontWeight.Normal, FontStyle.Italic) },
            boldItalic?.let { Font(it, FontWeight.Bold, FontStyle.Italic) },
        )
    )

private val GENERIC_FAMILIES =
    mapOf(
        "monospace" to FontFamily.Monospace,
        "sans-serif" to FontFamily.SansSerif,
        "serif" to FontFamily.Serif,
        "cursive" to FontFamily.Cursive,
    )

private val STYLES =
    listOf(
        FontWeight.Normal to FontStyle.Normal,
        FontWeight.Bold to FontStyle.Normal,
        FontWeight.Normal to FontStyle.Italic,
        FontWeight.Bold to FontStyle.Italic,
    )
