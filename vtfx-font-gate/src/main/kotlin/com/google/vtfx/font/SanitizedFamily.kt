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

/**
 * A family of sanitized fonts, which the terminal draws in turn with other families. Without
 * [bold], [italic], or [boldItalic], the system synthesizes them from the closest font of the
 * family.
 *
 * Immutable and thread-safe.
 */
public class SanitizedFamily(
    /** The regular font. */
    public val regular: SanitizedFont,
    /** The bold font, or null to synthesize it. */
    public val bold: SanitizedFont? = null,
    /** The italic font, or null to synthesize it. */
    public val italic: SanitizedFont? = null,
    /** The bold italic font, or null to synthesize it. */
    public val boldItalic: SanitizedFont? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is SanitizedFamily &&
            other.regular == regular &&
            other.bold == bold &&
            other.italic == italic &&
            other.boldItalic == boldItalic

    override fun hashCode(): Int = listOf(regular, bold, italic, boldItalic).hashCode()

    override fun toString(): String = "SanitizedFamily($regular, $bold, $italic, $boldItalic)"
}
