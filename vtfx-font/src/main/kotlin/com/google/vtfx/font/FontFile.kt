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

import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.SanitizedFont

/** Reads what a font file holds, for an app to show before it sanitizes a face. */
public object FontFile {
    /**
     * Lists the faces of a font file: one for a single font, and one for each font of a collection
     * (TTC or OTC). Only the table directories and the names are read, so a face listed here can
     * still be rejected by [sanitize].
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

    // The canonical form and why only it is drawn are in docs/decisions/0040.
    /**
     * Reads face [faceIndex] of a font file, TrueType or OpenType with TrueType or CFF outlines
     * (CFF2 is not read), or a collection of them, and writes it again in the canonical form that
     * [SanitizedFont] holds.
     *
     * This takes time, up to seconds for a CJK font on a phone: call it on a background thread,
     * once when the user imports a font, and save the result with [SanitizedFont.toByteArray]. It
     * stops with [InterruptedException] if the thread is interrupted.
     *
     * @param faceIndex The index of the face, from [inspect]; 0 for a single font.
     * @throws InvalidFontException if the font is larger than [limits] allow, is malformed, or uses
     *   formats that vtfx does not read.
     * @throws IllegalArgumentException if the file has no face [faceIndex].
     */
    @Throws(InvalidFontException::class, InterruptedException::class)
    public fun sanitize(input: ByteArray, faceIndex: Int, limits: FontLimits): SanitizedFont {
        if (input.size > limits.maxInputBytes) tooLarge("font of ${input.size} bytes")
        val faces = Sfnt.faceOffsets(input)
        require(faceIndex in faces.indices) { "face $faceIndex of ${faces.size}" }
        val parts = FaceParts.read(Sfnt.face(input, faces[faceIndex]), limits)
        // The verifier of vtfx-font-gate, inside fromCanonical, decides whether these bytes reach
        // native code; nothing here needs to be trusted for that.
        return SanitizedFont.fromCanonical(CanonicalWriter.write(parts, limits), limits)
    }
}
