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

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import com.google.vtfx.font.SanitizedFont
import java.io.IOException

// This is the only file of vtfx that makes typefaces from font data (config/boundaries.toml), and
// it is under security review (config/protected-paths.toml). It takes only SanitizedFont, whose
// bytes passed CanonicalVerifier, so the platform's native font code reads no font file from
// outside the app as it is (docs/decisions/0040).

/** The typefaces of [font]: its families in turn, then the system's monospace font. */
internal fun sanitizedTypefaces(font: TerminalFont.Sanitized): TextTypefaces {
    val families = font.families.map(::platformFamily)
    val builder = Typeface.CustomFallbackBuilder(families.first())
    for (family in families.drop(1)) builder.addCustomFallback(family)
    val regular =
        builder
            .setSystemFallback("monospace")
            .setStyle(FontStyle(FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    // Each family gives its closest font for these styles, and the system synthesizes the rest.
    return TextTypefaces(
        regular = regular,
        bold = Typeface.create(regular, FontStyle.FONT_WEIGHT_BOLD, false),
        italic = Typeface.create(regular, FontStyle.FONT_WEIGHT_NORMAL, true),
        boldItalic = Typeface.create(regular, FontStyle.FONT_WEIGHT_BOLD, true),
    )
}

private fun platformFamily(family: SanitizedFamily): FontFamily {
    val builder =
        FontFamily.Builder(
            platformFont(family.regular, FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT)
        )
    val bold = family.bold
    if (bold != null) {
        builder.addFont(
            platformFont(bold, FontStyle.FONT_WEIGHT_BOLD, FontStyle.FONT_SLANT_UPRIGHT)
        )
    }
    val italic = family.italic
    if (italic != null) {
        builder.addFont(
            platformFont(italic, FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_ITALIC)
        )
    }
    val boldItalic = family.boldItalic
    if (boldItalic != null) {
        builder.addFont(
            platformFont(boldItalic, FontStyle.FONT_WEIGHT_BOLD, FontStyle.FONT_SLANT_ITALIC)
        )
    }
    return builder.build()
}

/**
 * The platform's font of [font], which reads the font's bytes in place. A font that it rejects is a
 * bug in the canonical form, not a bad file, so it fails loudly rather than falling back.
 */
private fun platformFont(font: SanitizedFont, weight: Int, slant: Int): Font =
    try {
        Font.Builder(font.toByteBuffer()).setWeight(weight).setSlant(slant).build()
    } catch (e: IOException) {
        throw IllegalStateException("the platform rejected a sanitized font: $font", e)
    }
