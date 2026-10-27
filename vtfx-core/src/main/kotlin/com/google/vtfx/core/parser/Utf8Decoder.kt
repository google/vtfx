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

package com.google.vtfx.core.parser

/** Receives the code points that [Utf8Decoder] decodes. */
internal fun interface CodePointSink {
    fun accept(codePoint: Int)
}

/**
 * A streaming UTF-8 decoder. A sequence may be split across calls to [decode].
 *
 * Invalid input is replaced with U+FFFD following the "maximal subpart" practice of the Unicode
 * standard (section 3.9) and the WHATWG Encoding standard, so that a malformed byte never swallows
 * the valid bytes after it. Overlong encodings, surrogates, and code points above U+10FFFF are
 * invalid.
 */
internal class Utf8Decoder {
    private var codePoint = 0
    private var bytesNeeded = 0
    private var lowerBoundary = 0x80
    private var upperBoundary = 0xBF

    /** Whether no sequence is in progress, so that the next byte starts a character. */
    val isIdle: Boolean
        get() = bytesNeeded == 0

    fun decode(bytes: ByteArray, offset: Int, length: Int, sink: CodePointSink) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val byte = bytes[i].toInt() and 0xFF
            if (bytesNeeded == 0) {
                // A whole sequence of two or three bytes within this call, whose first byte does
                // not
                // narrow the range of the next (as E0 and ED do), is decoded at once: the bulk of
                // text beyond ASCII. Anything else goes through the states below.
                if (byte in 0xC2..0xDF && i + 1 < end) {
                    val second = bytes[i + 1].toInt()
                    if (second and 0xC0 == 0x80) {
                        sink.accept(((byte and 0x1F) shl 6) or (second and 0x3F))
                        i += 2
                        continue
                    }
                } else if (byte in 0xE1..0xEF && byte != 0xED && i + 2 < end) {
                    val second = bytes[i + 1].toInt()
                    val third = bytes[i + 2].toInt()
                    if (second and 0xC0 == 0x80 && third and 0xC0 == 0x80) {
                        sink.accept(
                            ((byte and 0x0F) shl 12) or
                                ((second and 0x3F) shl 6) or
                                (third and 0x3F)
                        )
                        i += 3
                        continue
                    }
                }
                i++
                when (byte) {
                    in 0x00..0x7F -> sink.accept(byte)
                    in 0xC2..0xDF -> start(1, byte and 0x1F)
                    in 0xE0..0xEF -> {
                        if (byte == 0xE0) lowerBoundary = 0xA0
                        if (byte == 0xED) upperBoundary = 0x9F
                        start(2, byte and 0x0F)
                    }
                    in 0xF0..0xF4 -> {
                        if (byte == 0xF0) lowerBoundary = 0x90
                        if (byte == 0xF4) upperBoundary = 0x8F
                        start(3, byte and 0x07)
                    }
                    else -> sink.accept(REPLACEMENT_CHARACTER)
                }
                continue
            }
            if (byte !in lowerBoundary..upperBoundary) {
                // The byte is not consumed: it may start the next sequence.
                reset()
                sink.accept(REPLACEMENT_CHARACTER)
                continue
            }
            i++
            lowerBoundary = 0x80
            upperBoundary = 0xBF
            codePoint = (codePoint shl 6) or (byte and 0x3F)
            bytesNeeded--
            if (bytesNeeded == 0) {
                val decoded = codePoint
                codePoint = 0
                sink.accept(decoded)
            }
        }
    }

    private fun start(continuationBytes: Int, initialBits: Int) {
        bytesNeeded = continuationBytes
        codePoint = initialBits
    }

    /** Drops a sequence in progress, so that the next byte starts a character. */
    fun reset() {
        codePoint = 0
        bytesNeeded = 0
        lowerBoundary = 0x80
        upperBoundary = 0xBF
    }

    companion object {
        const val REPLACEMENT_CHARACTER: Int = 0xFFFD
    }
}
