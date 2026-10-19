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

package com.google.vtfx.font.gate

import java.nio.Buffer
import java.nio.ByteBuffer
import java.security.MessageDigest

// The canonical form and why only it is drawn are in docs/decisions/0040.
/**
 * A font that vtfx wrote itself from a font file, in a simple form that it has checked, and that
 * the platform's font code can therefore read without trusting the original file.
 *
 * The only way to get one is [fromCanonical], which checks the bytes; `FontFile.sanitize` of
 * vtfx-font, which reads a font file, ends in it too. So holding a `SanitizedFont` proves that its
 * bytes passed the check.
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
         * Checks bytes in the canonical form and makes a font of them: the bytes that
         * `FontFile.sanitize` of vtfx-font wrote, or those of a font saved with [toByteArray]. The
         * bytes are checked every time, so they may come from storage that the app does not fully
         * trust. This takes one pass over the bytes, much less time than sanitizing a font file.
         *
         * @throws InvalidFontException if the bytes are not a sanitized font, or are larger than
         *   [FontLimits.maxOutputBytes].
         */
        @Throws(InvalidFontException::class)
        public fun fromCanonical(canonical: ByteArray, limits: FontLimits): SanitizedFont =
            // Copied first, so that the caller cannot change the bytes after they are checked.
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
