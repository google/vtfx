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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes PNG images for the tests of [PngDecoder], so that every color type, bit depth, filter
 * type, and interlacing can be tested, and broken images can be made chunk by chunk. Written
 * separately from the decoder, following the specification.
 */
internal object PngTestEncoder {
    val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    const val GRAY = 0
    const val RGB = 2
    const val PALETTE = 3
    const val GRAY_ALPHA = 4
    const val RGBA = 6

    /** The valid pairs of color type and bit depth. */
    val VALID_FORMATS =
        listOf(
            GRAY to 1,
            GRAY to 2,
            GRAY to 4,
            GRAY to 8,
            GRAY to 16,
            RGB to 8,
            RGB to 16,
            PALETTE to 1,
            PALETTE to 2,
            PALETTE to 4,
            PALETTE to 8,
            GRAY_ALPHA to 8,
            GRAY_ALPHA to 16,
            RGBA to 8,
            RGBA to 16,
        )

    fun channels(colorType: Int): Int =
        when (colorType) {
            RGB -> 3
            GRAY_ALPHA -> 2
            RGBA -> 4
            else -> 1
        }

    /** A chunk with its length, [type], [data], and CRC. */
    fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc.value.toInt())
            .array()
    }

    fun ihdr(
        width: Int,
        height: Int,
        bitDepth: Int,
        colorType: Int,
        interlace: Int = 0,
        compression: Int = 0,
        filter: Int = 0,
    ): ByteArray =
        chunk(
            "IHDR",
            ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put(bitDepth.toByte())
                .put(colorType.toByte())
                .put(compression.toByte())
                .put(filter.toByte())
                .put(interlace.toByte())
                .array(),
        )

    /** The signature followed by [chunks]. */
    fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        for (chunk in chunks) out.write(chunk)
        return out.toByteArray()
    }

    fun deflate(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Recomputes the CRC of every chunk whose length fits in [png], after it was changed. */
    fun fixCrcs(png: ByteArray) {
        var position = 8
        while (png.size - position >= 12) {
            val length = ByteBuffer.wrap(png, position, 4).int
            if (length < 0 || length > png.size - position - 12) return
            val crc = CRC32()
            crc.update(png, position + 4, length + 4)
            ByteBuffer.wrap(png).putInt(position + 8 + length, crc.value.toInt())
            position += 12 + length
        }
    }

    /** The Adam7 passes as x, y, dx, dy. */
    private val ADAM7 =
        listOf(
            intArrayOf(0, 0, 8, 8),
            intArrayOf(4, 0, 8, 8),
            intArrayOf(0, 4, 4, 8),
            intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4),
            intArrayOf(1, 0, 2, 2),
            intArrayOf(0, 1, 1, 2),
        )

    /**
     * An image to encode. [samples] holds the samples of each pixel, [channels] per pixel, row by
     * row, each below 2^[bitDepth]. [filter] chooses the filter type of each scanline, numbered
     * from 0 across all passes.
     */
    class Image(
        val width: Int,
        val height: Int,
        val colorType: Int,
        val bitDepth: Int,
        val samples: IntArray,
        val interlaced: Boolean = false,
        val filter: (Int) -> Int = { 0 },
    ) {
        val channels = channels(colorType)

        init {
            require(samples.size == width * height * channels)
        }

        /** The filtered scanlines, as the zlib stream holds them. */
        fun scanlines(): ByteArray {
            val out = ByteArrayOutputStream()
            val passes = if (interlaced) ADAM7 else listOf(intArrayOf(0, 0, 1, 1))
            var scanline = 0
            for ((x0, y0, dx, dy) in passes) {
                val xs = (x0 until width step dx).toList()
                if (xs.isEmpty()) continue
                var previous: ByteArray? = null
                for (y in y0 until height step dy) {
                    val row = packRow(xs.map { x -> y * width + x })
                    val filterType = filter(scanline++)
                    out.write(filterType)
                    out.write(filterRow(filterType, row, previous))
                    previous = row
                }
            }
            return out.toByteArray()
        }

        private fun packRow(pixels: List<Int>): ByteArray {
            val out = ByteArrayOutputStream()
            var bits = 0
            var bitCount = 0
            for (pixel in pixels) {
                for (c in 0 until channels) {
                    val sample = samples[pixel * channels + c]
                    when (bitDepth) {
                        16 -> {
                            out.write(sample ushr 8)
                            out.write(sample and 0xff)
                        }
                        8 -> out.write(sample)
                        else -> {
                            bits = (bits shl bitDepth) or sample
                            bitCount += bitDepth
                            if (bitCount == 8) {
                                out.write(bits)
                                bits = 0
                                bitCount = 0
                            }
                        }
                    }
                }
            }
            if (bitCount > 0) out.write(bits shl (8 - bitCount))
            return out.toByteArray()
        }

        private fun filterRow(type: Int, row: ByteArray, previous: ByteArray?): ByteArray {
            val bpp = maxOf(1, channels * bitDepth / 8)
            return ByteArray(row.size) { i ->
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xff else 0
                val b = if (previous != null) previous[i].toInt() and 0xff else 0
                val c = if (previous != null && i >= bpp) previous[i - bpp].toInt() and 0xff else 0
                val predictor =
                    when (type) {
                        0 -> 0
                        1 -> a
                        2 -> b
                        3 -> (a + b) / 2
                        4 -> {
                            val p = a + b - c
                            val pa = Math.abs(p - a)
                            val pb = Math.abs(p - b)
                            val pc = Math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        }
                        else -> error("filter type $type")
                    }
                ((row[i].toInt() and 0xff) - predictor).toByte()
            }
        }
    }

    /**
     * Encodes [image] with [palette] (RGB triplets) and [trns] if given, its zlib stream split into
     * [idatCount] IDAT chunks, and [ancillary] chunks before the first IDAT.
     */
    fun encode(
        image: Image,
        palette: ByteArray? = null,
        trns: ByteArray? = null,
        idatCount: Int = 1,
        ancillary: List<ByteArray> = emptyList(),
    ): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        chunks +=
            ihdr(
                image.width,
                image.height,
                image.bitDepth,
                image.colorType,
                if (image.interlaced) 1 else 0,
            )
        if (palette != null) chunks += chunk("PLTE", palette)
        if (trns != null) chunks += chunk("tRNS", trns)
        chunks += ancillary
        chunks += idatChunks(deflate(image.scanlines()), idatCount)
        chunks += chunk("IEND")
        return png(*chunks.toTypedArray())
    }

    /** [zlib] split into [count] IDAT chunks of about the same size. */
    fun idatChunks(zlib: ByteArray, count: Int): List<ByteArray> =
        (0 until count).map { i ->
            chunk("IDAT", zlib.copyOfRange(zlib.size * i / count, zlib.size * (i + 1) / count))
        }

    /**
     * The color that the specification gives the pixel at [index] of [image] as 0xAARRGGBB, with
     * 16-bit samples reduced to their high byte.
     */
    fun expectedColor(image: Image, index: Int, palette: ByteArray?, trns: ByteArray?): Int {
        val depth = image.bitDepth
        val s = IntArray(image.channels) { c -> image.samples[index * image.channels + c] }
        fun to8(v: Int) = if (depth == 16) v ushr 8 else v * 255 / ((1 shl depth) - 1)
        fun u16(bytes: ByteArray, i: Int) =
            ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
        return when (image.colorType) {
            GRAY -> {
                val transparent = trns != null && trns.size == 2 && s[0] == u16(trns, 0)
                argb(if (transparent) 0 else 255, to8(s[0]), to8(s[0]), to8(s[0]))
            }
            RGB -> {
                val transparent =
                    trns != null &&
                        trns.size == 6 &&
                        s[0] == u16(trns, 0) &&
                        s[1] == u16(trns, 2) &&
                        s[2] == u16(trns, 4)
                argb(if (transparent) 0 else 255, to8(s[0]), to8(s[1]), to8(s[2]))
            }
            PALETTE -> {
                val i = s[0]
                if (i >= palette!!.size / 3) {
                    0
                } else {
                    val alpha = if (trns != null && i < trns.size) trns[i].toInt() and 0xff else 255
                    argb(
                        alpha,
                        palette[i * 3].toInt() and 0xff,
                        palette[i * 3 + 1].toInt() and 0xff,
                        palette[i * 3 + 2].toInt() and 0xff,
                    )
                }
            }
            GRAY_ALPHA -> argb(to8(s[1]), to8(s[0]), to8(s[0]), to8(s[0]))
            RGBA -> argb(to8(s[3]), to8(s[0]), to8(s[1]), to8(s[2]))
            else -> error("color type ${image.colorType}")
        }
    }
}
