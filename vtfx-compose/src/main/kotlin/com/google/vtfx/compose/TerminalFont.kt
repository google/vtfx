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

package com.google.vtfx.compose

import androidx.annotation.FontRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// The fonts that are accepted are chosen in docs/decisions/0020.
/**
 * The font of a terminal: a font family of the system, or fonts bundled in the app's resources. It
 * should be monospaced: the cell size is measured from it.
 *
 * Fonts are parsed by native code in the app's process, where a malicious font file could take over
 * the app and what it holds, such as keys and connections. So only fonts that come with the system
 * or with the app's own signed package are accepted, never files, URIs, or downloaded fonts.
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
}

/** The Compose font family of this font. */
internal fun TerminalFont.toFontFamily(): FontFamily =
    when (this) {
        is TerminalFont.System ->
            GENERIC_FAMILIES[family]
                ?: FontFamily(
                    STYLES.map { (weight, style) ->
                        Font(DeviceFontFamilyName(family), weight, style)
                    }
                )
        is TerminalFont.Resources ->
            FontFamily(
                listOfNotNull(
                    Font(regular),
                    bold?.let { Font(it, FontWeight.Bold) },
                    italic?.let { Font(it, FontWeight.Normal, FontStyle.Italic) },
                    boldItalic?.let { Font(it, FontWeight.Bold, FontStyle.Italic) },
                )
            )
    }

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
