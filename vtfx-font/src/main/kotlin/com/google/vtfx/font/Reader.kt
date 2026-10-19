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
 * Big-endian reads from [length] bytes of [data] starting at [start], as fonts store their numbers.
 * Positions are relative to [start]. A read outside the range is a malformed font, never an
 * exception of the JVM, so that every way a font can be wrong ends in [InvalidFontException].
 *
 * The readers of the input use this; [CanonicalVerifier] does not, so that a bug here cannot hide
 * one there.
 */
internal class Reader(private val data: ByteArray, private val start: Int, val length: Int) {
    init {
        check(start >= 0 && length >= 0 && start <= data.size - length) {
            "range $start+$length out of ${data.size}"
        }
    }

    constructor(data: ByteArray) : this(data, 0, data.size)

    fun u8(at: Int): Int {
        checkRange(at, 1)
        return data[start + at].toInt() and 0xFF
    }

    fun u16(at: Int): Int {
        checkRange(at, 2)
        val i = start + at
        return ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
    }

    fun s16(at: Int): Int = u16(at).toShort().toInt()

    fun u24(at: Int): Int = (u8(at) shl 16) or u16(at + 1)

    /** An unsigned 32-bit number, as a Long so that it is never negative. */
    fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

    fun s32(at: Int): Int = u32(at).toInt()

    /**
     * An unsigned 32-bit offset or length, which must fit in an Int: anything larger points beyond
     * any font that the limits let in.
     */
    fun index32(at: Int): Int {
        val value = u32(at)
        if (value > Int.MAX_VALUE) malformed("offset $value out of range")
        return value.toInt()
    }

    /** The [length] bytes from [at], as a reader of their own. */
    fun sub(at: Int, length: Int): Reader {
        checkRange(at, length)
        return Reader(data, start + at, length)
    }

    /** The bytes from [at] to the end. */
    fun from(at: Int): Reader {
        if (at < 0 || at > length) malformed("offset $at out of $length")
        return Reader(data, start + at, length - at)
    }

    private fun checkRange(at: Int, count: Int) {
        if (at < 0 || count < 0 || at > length - count) {
            malformed("read of $count at $at out of $length")
        }
    }
}
