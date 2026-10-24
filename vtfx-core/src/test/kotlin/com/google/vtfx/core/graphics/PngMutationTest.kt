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

import com.google.vtfx.core.graphics.PngTestEncoder.GRAY
import com.google.vtfx.core.graphics.PngTestEncoder.GRAY_ALPHA
import com.google.vtfx.core.graphics.PngTestEncoder.Image
import com.google.vtfx.core.graphics.PngTestEncoder.PALETTE
import com.google.vtfx.core.graphics.PngTestEncoder.RGB
import com.google.vtfx.core.graphics.PngTestEncoder.RGBA
import com.google.vtfx.core.graphics.PngTestEncoder.chunk
import com.google.vtfx.core.graphics.PngTestEncoder.deflate
import com.google.vtfx.core.graphics.PngTestEncoder.encode
import com.google.vtfx.core.graphics.PngTestEncoder.fixCrcs
import com.google.vtfx.core.graphics.PngTestEncoder.ihdr
import com.google.vtfx.core.graphics.PngTestEncoder.png
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Feeds [PngDecoder] mutations of valid images, with fixed seeds, and checks that it neither throws
 * nor exceeds its limits.
 *
 * The fuzz target of the module (docs/design/testing.md section 7) covers the terminal; Jazzer
 * fuzzes only one target per module, so the decoder gets this deterministic test instead. Most
 * mutations recompute the CRCs, so that they reach past the CRC check into the chunk structure, the
 * image data, and the filters.
 */
class PngMutationTest {
    private val maxPixels = 1L shl 16

    private val seeds: List<ByteArray> = buildList {
        val random = Random(0)
        fun samples(
            width: Int,
            height: Int,
            colorType: Int,
            bitDepth: Int,
            limit: Int = 1 shl bitDepth,
        ) = IntArray(width * height * PngTestEncoder.channels(colorType)) { random.nextInt(limit) }
        add(encode(Image(9, 7, RGBA, 8, samples(9, 7, RGBA, 8), interlaced = true) { it % 5 }))
        add(
            encode(
                Image(5, 5, RGB, 16, samples(5, 5, RGB, 16)) { it % 5 },
                trns = ByteArray(6),
                idatCount = 3,
            )
        )
        add(
            encode(
                Image(17, 3, GRAY, 1, samples(17, 3, GRAY, 1), interlaced = true),
                trns = byteArrayOf(0, 1),
            )
        )
        add(encode(Image(6, 6, GRAY_ALPHA, 16, samples(6, 6, GRAY_ALPHA, 16)) { 4 }))
        add(
            encode(
                Image(
                    10,
                    4,
                    PALETTE,
                    4,
                    samples(10, 4, PALETTE, 4, limit = 12),
                    interlaced = true,
                ) {
                    it % 5
                },
                palette = random.nextBytes(12 * 3),
                trns = random.nextBytes(5),
            )
        )
        add(encode(Image(3, 11, GRAY, 8, samples(3, 11, GRAY, 8)) { 3 }, idatCount = 2))
    }

    @Test
    fun `survives mutated images`() {
        val random = Random(42)
        val outcomes = IntArray(3)
        repeat(20_000) { iteration ->
            val seed = seeds[random.nextInt(seeds.size)]
            val mutated = mutate(seed, random)
            val result =
                try {
                    PngDecoder.decode(mutated, 0, mutated.size, maxPixels)
                } catch (e: Throwable) {
                    throw AssertionError("iteration $iteration threw on ${mutated.toHex()}", e)
                }
            when (result) {
                is PngDecoder.Result.Decoded -> {
                    val image = result.image
                    assertTrue(image.width > 0 && image.height > 0)
                    assertTrue(image.width.toLong() * image.height <= maxPixels)
                    assertEquals(image.width * image.height, image.pixels.size)
                    outcomes[0]++
                }
                PngDecoder.Result.TooLarge -> outcomes[1]++
                PngDecoder.Result.Malformed -> outcomes[2]++
            }
            try {
                val unused = PngDecoder.readSize(mutated, 0, mutated.size)
            } catch (e: Throwable) {
                throw AssertionError("iteration $iteration: readSize threw", e)
            }
        }
        // The mutations reach every outcome, so they are not all rejected at the first check.
        assertTrue(outcomes.all { it > 0 }, "outcomes ${outcomes.toList()}")
    }

    private fun mutate(png: ByteArray, random: Random): ByteArray =
        when (random.nextInt(7)) {
            0 -> flipBits(png.copyOf(), random)
            1 -> flipBits(png.copyOf(), random).also { fixCrcs(it) }
            2 -> png.copyOf(random.nextInt(png.size))
            3 -> changeChunkLength(png.copyOf(), random).also { fixCrcs(it) }
            4 -> mutateImageData(png, random)
            5 -> mutateHeader(png.copyOf(), random).also { fixCrcs(it) }
            else -> spliceBytes(png, random).also { fixCrcs(it) }
        }

    private fun flipBits(png: ByteArray, random: Random): ByteArray {
        repeat(1 + random.nextInt(8)) {
            val i = random.nextInt(png.size)
            png[i] = (png[i].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        return png
    }

    /** The offsets of the chunks of [png]. */
    private fun chunkOffsets(png: ByteArray): List<Int> = buildList {
        var position = 8
        while (png.size - position >= 12) {
            add(position)
            val length = ByteBuffer.wrap(png, position, 4).int
            if (length < 0 || length > png.size - position - 12) break
            position += 12 + length
        }
    }

    private fun changeChunkLength(png: ByteArray, random: Random): ByteArray {
        val offsets = chunkOffsets(png)
        val offset = offsets[random.nextInt(offsets.size)]
        val buffer = ByteBuffer.wrap(png)
        val length = buffer.getInt(offset)
        val newLength =
            when (random.nextInt(3)) {
                0 -> length + random.nextInt(-4, 5)
                1 -> random.nextInt()
                else -> random.nextInt(png.size)
            }
        buffer.putInt(offset, newLength)
        return png
    }

    /** Changes the inflated IDAT data, filter type bytes included, and compresses it again. */
    private fun mutateImageData(png: ByteArray, random: Random): ByteArray {
        val offsets = chunkOffsets(png)
        val buffer = ByteBuffer.wrap(png)
        val idats = offsets.filter { buffer.getInt(it + 4) == 0x49444154 }
        val zlib =
            idats
                .map { png.copyOfRange(it + 8, it + 8 + buffer.getInt(it)) }
                .reduce(ByteArray::plus)
        var data = Zlib.inflate(zlib, 0, zlib.size, 1 shl 20)!!
        when (random.nextInt(3)) {
            0 ->
                repeat(1 + random.nextInt(4)) {
                    data[random.nextInt(data.size)] = random.nextInt(256).toByte()
                }
            1 -> data = data.copyOf(maxOf(0, data.size + random.nextInt(-3, 4)))
            else -> data[0] = random.nextInt(256).toByte()
        }
        val before = png.copyOf(idats.first())
        val after = png.copyOfRange(idats.last() + 12 + buffer.getInt(idats.last()), png.size)
        return before + chunk("IDAT", deflate(data)) + after
    }

    private fun mutateHeader(png: ByteArray, random: Random): ByteArray {
        val buffer = ByteBuffer.wrap(png)
        when (random.nextInt(6)) {
            0 -> buffer.putInt(16, buffer.getInt(16) + random.nextInt(-2, 3))
            1 -> buffer.putInt(20, buffer.getInt(20) + random.nextInt(-2, 3))
            2 -> buffer.putInt(16 + 4 * random.nextInt(2), random.nextInt())
            3 -> png[24] = listOf(1, 2, 4, 8, 16)[random.nextInt(5)].toByte()
            4 -> png[25] = listOf(0, 2, 3, 4, 6)[random.nextInt(5)].toByte()
            else -> png[28] = (png[28].toInt() xor 1).toByte()
        }
        return png
    }

    private fun spliceBytes(png: ByteArray, random: Random): ByteArray {
        val at = 8 + random.nextInt(png.size - 8)
        return if (random.nextBoolean()) {
            png.copyOf(at) +
                random.nextBytes(1 + random.nextInt(16)) +
                png.copyOfRange(at, png.size)
        } else {
            png.copyOf(at) + png.copyOfRange(minOf(png.size, at + 1 + random.nextInt(16)), png.size)
        }
    }

    @Test
    fun `survives every single-byte change of the header with the CRC fixed`() {
        val png = seeds[0]
        for (i in 8 until 33) {
            for (value in 0..255) {
                val mutated = png.copyOf()
                mutated[i] = value.toByte()
                fixCrcs(mutated)
                val unused = PngDecoder.decode(mutated, 0, mutated.size, maxPixels)
            }
        }
        // And a header alone, at every valid format, with no image data.
        for ((colorType, bitDepth) in PngTestEncoder.VALID_FORMATS) {
            val header = png(ihdr(3, 3, bitDepth, colorType))
            val unused = PngDecoder.decode(header, 0, header.size, maxPixels)
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
