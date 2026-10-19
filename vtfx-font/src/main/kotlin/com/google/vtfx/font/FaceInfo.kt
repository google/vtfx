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
 * One face of a font file, as [FontFile.inspect] describes it, for an app to let users choose a
 * face of a collection.
 *
 * The names come from the file, so they are untrusted text: they are cut to 64 characters and
 * stripped of control characters and of the characters that change the direction of text, but they
 * may still say anything.
 */
public class FaceInfo
internal constructor(
    /** The index of the face in the file, which [SanitizedFont.sanitize] takes. */
    public val index: Int,
    /** The family name, such as "Noto Sans Mono CJK JP", or null if the face has none. */
    public val familyName: String?,
    /** The style name, such as "Regular" or "Bold", or null if the face has none. */
    public val styleName: String?,
    /** The format of the outlines. */
    public val outlineFormat: OutlineFormat,
) {
    /** Whether [SanitizedFont.sanitize] reads the outlines of this face. */
    public val isSupported: Boolean
        get() = outlineFormat == OutlineFormat.TrueType || outlineFormat == OutlineFormat.Cff

    override fun equals(other: Any?): Boolean =
        other is FaceInfo &&
            other.index == index &&
            other.familyName == familyName &&
            other.styleName == styleName &&
            other.outlineFormat == outlineFormat

    override fun hashCode(): Int = listOf(index, familyName, styleName, outlineFormat).hashCode()

    override fun toString(): String =
        "FaceInfo(index=$index, familyName=$familyName, styleName=$styleName, " +
            "outlineFormat=$outlineFormat)"
}
