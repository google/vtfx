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

import java.nio.Buffer
import java.nio.ByteBuffer
import java.security.MessageDigest

// The canonical form and why only it is drawn are in docs/decisions/0040.
/**
 * A font that vtfx wrote itself from a font file, in a simple form that it has checked, and that
 * the platform's font code can therefore read without trusting the original file.
 *
 * The only ways to get one are [sanitize], which reads a font file, and [fromCanonical], which
 * loads the bytes of one saved with [toByteArray]; both end in the same check, so holding a
 * `SanitizedFont` proves that its bytes passed it.
 *
 * What the original font had beyond outlines, metrics, and characters is gone: hinting, ligatures
 * and other OpenType layout, color and bitmap glyphs, variations (a variable font becomes its
 * default instance), and its names. Variation sequences, such as the forms of CJK ideographs that
 * names use, are kept.
 *
 * Immutable and thread-safe. Two are equal when their bytes are.
 */
public class SanitizedFont
private constructor(
    private val buffer: ByteBuffer,
    private val digest: ByteArray,
    /** How the advance widths of the font's characters compare. */
    public val widthClass: WidthClass,
) {
    /** The size of the font in bytes. */
    public val size: Int
        get() = buffer.capacity()

    /** A copy of the bytes of the font, for an app to save and load later with [fromCanonical]. */
    public fun toByteArray(): ByteArray {
        val bytes = ByteArray(buffer.capacity())
        buffer.duplicate().get(bytes)
        return bytes
    }

    /** The bytes of the font, read-only and direct, as the platform's font code takes them. */
    public fun toByteBuffer(): ByteBuffer = buffer.duplicate()

    override fun equals(other: Any?): Boolean =
        other is SanitizedFont && other.digest.contentEquals(digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String =
        "SanitizedFont(size=$size, sha256=${digest.joinToString("") { "%02x".format(it) }})"

    public companion object {
        /**
         * Reads face [faceIndex] of a font file, TrueType or OpenType with TrueType or CFF outlines
         * (CFF2 is not read), or a collection of them, and writes it again in the canonical form.
         *
         * This takes time, up to seconds for a CJK font on a phone: call it on a background thread,
         * once when the user imports a font, and save the result with [toByteArray]. It stops with
         * [InterruptedException] if the thread is interrupted.
         *
         * @param faceIndex The index of the face, from [FontFile.inspect]; 0 for a single font.
         * @throws InvalidFontException if the font is larger than [limits] allow, is malformed, or
         *   uses formats that vtfx does not read.
         * @throws IllegalArgumentException if the file has no face [faceIndex].
         */
        @Throws(InvalidFontException::class, InterruptedException::class)
        public fun sanitize(input: ByteArray, faceIndex: Int, limits: FontLimits): SanitizedFont {
            if (input.size > limits.maxInputBytes) tooLarge("font of ${input.size} bytes")
            val faces = Sfnt.faceOffsets(input)
            require(faceIndex in faces.indices) { "face $faceIndex of ${faces.size}" }
            val parts = FaceParts.read(Sfnt.face(input, faces[faceIndex]), limits)
            return verified(CanonicalWriter.write(parts, limits), limits)
        }

        /**
         * Loads a font saved with [toByteArray]. The bytes are checked again, as they were when the
         * font was sanitized, so they may come from storage that the app does not fully trust. This
         * takes one pass over the bytes, much less time than [sanitize].
         *
         * @throws InvalidFontException if the bytes are not a sanitized font, or are larger than
         *   [FontLimits.maxOutputBytes].
         */
        @Throws(InvalidFontException::class)
        public fun fromCanonical(canonical: ByteArray, limits: FontLimits): SanitizedFont =
            // Copied first, so that the app cannot change the bytes after they are checked.
            verified(canonical.copyOf(), limits)

        /** A font of [bytes], which no one else holds, if they pass [CanonicalVerifier]. */
        private fun verified(bytes: ByteArray, limits: FontLimits): SanitizedFont {
            val verified = CanonicalVerifier.verify(bytes, limits)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
            // Through Buffer: ByteBuffer's own flip, which returns a ByteBuffer, came with Java 9
            // and is missing from older Android versions.
            val plain: Buffer = buffer
            plain.flip()
            return SanitizedFont(buffer.asReadOnlyBuffer(), digest, verified.widthClass)
        }
    }
}
