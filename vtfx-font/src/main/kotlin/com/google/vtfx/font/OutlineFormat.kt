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

// A value class rather than an enum, since vtfx returns it and may add formats
// (docs/decisions/0087).
/** The format of the outlines of a face. More formats may be added. */
@JvmInline
public value class OutlineFormat private constructor(private val value: Int) {
    override fun toString(): String =
        when (this) {
            TrueType -> "TrueType"
            Cff -> "Cff"
            Cff2 -> "Cff2"
            None -> "None"
            else -> "OutlineFormat($value)"
        }

    public companion object {
        /** TrueType outlines, in the `glyf` table. */
        public val TrueType: OutlineFormat = OutlineFormat(0)

        /** PostScript outlines, in the `CFF ` table. */
        public val Cff: OutlineFormat = OutlineFormat(1)

        /** PostScript outlines of variable fonts, in the `CFF2` table. */
        public val Cff2: OutlineFormat = OutlineFormat(2)

        /** No outlines, such as a face of bitmap glyphs only, or more than one kind of them. */
        public val None: OutlineFormat = OutlineFormat(3)
    }
}
