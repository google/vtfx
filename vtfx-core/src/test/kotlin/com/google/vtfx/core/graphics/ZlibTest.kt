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

import com.google.vtfx.core.graphics.PngTestEncoder.deflate
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

class ZlibTest {
    private fun inflate(data: ByteArray, maxOutput: Int) =
        Zlib.inflate(data, 0, data.size, maxOutput)

    @Test
    fun `round trips data of many sizes`() {
        val random = Random(1)
        for (size in listOf(0, 1, 100, 4095, 4096, 4097, 100_000, 1 shl 20)) {
            // Half random, half repeated, so that both stored and compressed blocks occur.
            val data = ByteArray(size) { i -> if (i < size / 2) random.nextInt().toByte() else 7 }
            assertContentEquals(data, inflate(deflate(data), size), "size $size")
            assertContentEquals(data, inflate(deflate(data), size + 1000), "size $size, more room")
        }
    }

    @Test
    fun `inflates a range of the array`() {
        val zlib = deflate("hello".toByteArray())
        val padded = byteArrayOf(1, 2, 3) + zlib + byteArrayOf(4, 5)
        assertContentEquals("hello".toByteArray(), Zlib.inflate(padded, 3, zlib.size, 100))
    }

    @Test
    fun `returns null when the output exceeds the limit`() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val zlib = deflate(data)
        assertNull(inflate(zlib, 9_999))
        assertNull(inflate(zlib, 4096))
        assertNull(inflate(zlib, 0))
    }

    @Test
    fun `stops a zlib bomb at the limit`() {
        // 256 MiB of zeros compress to about 256 KiB.
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream()
        val zeros = ByteArray(1 shl 20)
        val buffer = ByteArray(1 shl 16)
        repeat(256) {
            deflater.setInput(zeros)
            while (!deflater.needsInput()) out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val bomb = out.toByteArray()
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { assertNull(inflate(bomb, 1 shl 20)) }
    }

    @Test
    fun `returns null for malformed data`() {
        assertNull(inflate(ByteArray(0), 100))
        assertNull(inflate(byteArrayOf(0x78), 100))
        assertNull(inflate("not zlib at all".toByteArray(), 100))
        // A header with a bad check value.
        assertNull(inflate(byteArrayOf(0x78, 0x9d.toByte(), 3, 0), 100))
    }

    @Test
    fun `returns null for truncated data`() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val zlib = deflate(data)
        for (length in 0 until zlib.size) {
            assertNull(Zlib.inflate(zlib, 0, length, data.size), "length $length")
        }
    }

    @Test
    fun `returns null when the Adler-32 checksum does not match`() {
        val zlib = deflate("hello, world".toByteArray())
        zlib[zlib.size - 1] = (zlib[zlib.size - 1] + 1).toByte()
        assertNull(inflate(zlib, 100))
    }

    @Test
    fun `returns null when a preset dictionary is needed`() {
        val deflater = Deflater()
        deflater.setDictionary("hello".toByteArray())
        deflater.setInput("hello hello".toByteArray())
        deflater.finish()
        val buffer = ByteArray(100)
        val size = deflater.deflate(buffer)
        deflater.end()
        assertNull(inflate(buffer.copyOf(size), 100))
    }

    @Test
    fun `inflates an empty stream with no room`() {
        assertContentEquals(ByteArray(0), inflate(deflate(ByteArray(0)), 0))
    }

    @Test
    fun `ignores bytes after the end of the stream`() {
        val zlib = deflate("hello".toByteArray()) + byteArrayOf(1, 2, 3)
        assertContentEquals("hello".toByteArray(), inflate(zlib, 5))
    }

    @Test
    fun `inflates input given in pieces`() {
        val data = ByteArray(50_000) { (it % 13).toByte() }
        val zlib = deflate(data, Deflater.NO_COMPRESSION)
        // Includes empty pieces.
        val cuts = listOf(0, 0, 1, 2, 2, 100, 5000, zlib.size)
        var piece = 0
        val inflated =
            Zlib.inflate(data.size) { inflater ->
                if (piece == cuts.size - 1) {
                    false
                } else {
                    inflater.setInput(zlib, cuts[piece], cuts[piece + 1] - cuts[piece])
                    piece++
                    true
                }
            }
        assertContentEquals(data, inflated)
    }
}
