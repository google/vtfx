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

/** Reads what a font file holds, for an app to show before it sanitizes a face. */
public object FontFile {
    /**
     * Lists the faces of a font file: one for a single font, and one for each font of a collection
     * (TTC or OTC). Only the table directories and the names are read, so a face listed here can
     * still be rejected by [SanitizedFont.sanitize].
     *
     * @throws InvalidFontException if [input] is larger than [FontLimits.maxInputBytes], is not a
     *   TrueType or OpenType font or collection (WOFF is not read), or is malformed.
     */
    public fun inspect(input: ByteArray, limits: FontLimits): List<FaceInfo> {
        if (input.size > limits.maxInputBytes) tooLarge("font of ${input.size} bytes")
        return Sfnt.faceOffsets(input).mapIndexed { index, offset ->
            val face = Sfnt.face(input, offset)
            val names = Names.read(face.table(Tag.NAME))
            FaceInfo(index, names.family, names.style, face.outlineFormat)
        }
    }
}
