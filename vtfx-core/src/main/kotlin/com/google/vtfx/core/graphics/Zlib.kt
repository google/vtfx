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

package com.google.vtfx.core.graphics

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Inflates zlib data (RFC 1950) from the host with a limit on the output.
 *
 * The data is untrusted (docs/decisions/0002): a few kilobytes can inflate to gigabytes. The output
 * buffer starts small and grows geometrically up to the limit, so that a large limit costs nothing
 * for small data, and inflating stops as soon as the output would exceed the limit. The largest
 * buffer is the limit itself.
 */
internal object Zlib {
    /** The size of the first output buffer, unless the limit is smaller. */
    private const val INITIAL_BUFFER_SIZE = 4096

    /**
     * Inflates the zlib stream in [length] bytes of [data] from [offset]. Returns null if the
     * stream is malformed or truncated, fails its Adler-32 check, needs a preset dictionary, or
     * inflates to more than [maxOutput] bytes. Bytes after the end of the stream are ignored.
     */
    fun inflate(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "range $offset+$length out of ${data.size}"
        }
        var given = false
        return inflate(maxOutput) { inflater ->
            if (given) {
                false
            } else {
                inflater.setInput(data, offset, length)
                given = true
                true
            }
        }
    }

    /**
     * Inflates a zlib stream whose input comes in pieces, such as the IDAT chunks of a PNG. Each
     * call of [feed] either sets the next piece on the inflater with `setInput` and returns true,
     * or returns false if there is no more input. Otherwise the same as the other overload.
     */
    fun inflate(maxOutput: Int, feed: (Inflater) -> Boolean): ByteArray? {
        require(maxOutput >= 0) { "maxOutput $maxOutput" }
        val inflater = Inflater()
        try {
            var buffer = ByteArray(minOf(maxOutput, INITIAL_BUFFER_SIZE))
            var count = 0
            // When the buffer is full at the limit, any further byte means the limit is exceeded.
            val probe = ByteArray(1)
            while (true) {
                if (count == buffer.size && buffer.size < maxOutput) {
                    val size = maxOf(buffer.size * 2L, INITIAL_BUFFER_SIZE.toLong())
                    buffer = buffer.copyOf(minOf(size, maxOutput.toLong()).toInt())
                }
                val read = inflater.bytesRead
                val inflated =
                    if (count < buffer.size) {
                        inflater.inflate(buffer, count, buffer.size - count)
                    } else {
                        inflater.inflate(probe)
                    }
                if (count == buffer.size && inflated > 0) return null
                count += inflated
                if (inflater.finished()) break
                if (inflated == 0) {
                    when {
                        inflater.needsDictionary() -> return null
                        inflater.needsInput() -> if (!feed(inflater)) return null
                        // Neither output nor progress with input and room for output. zlib does
                        // not do this, but a loop must not depend on it.
                        inflater.bytesRead == read -> return null
                    }
                }
            }
            return if (count == buffer.size) buffer else buffer.copyOf(count)
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
