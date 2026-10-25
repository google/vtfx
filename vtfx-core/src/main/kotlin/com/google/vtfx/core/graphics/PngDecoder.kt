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

import java.util.zip.CRC32
import kotlin.math.abs

/**
 * An image decoded to 32-bit pixels. [pixels] holds [width] * [height] colors as 0xAARRGGBB, not
 * premultiplied, row by row from the top.
 */
internal class DecodedImage(val width: Int, val height: Int, val pixels: IntArray)

/** The size of a PNG image in pixels, as its IHDR chunk gives it. */
internal class PngSize(val width: Int, val height: Int)

/**
 * Decodes PNG images (ISO/IEC 15948, the W3C PNG specification) in pure Kotlin.
 *
 * The images come from the host and are untrusted (docs/decisions/0081). The platform's image
 * decoders are not used, so that no native code parses them and decoding is deterministic and can
 * be fuzzed on the JVM. No input throws: anything wrong is [Result.Malformed]. The size is checked
 * against the caller's limit from the IHDR chunk before anything large is allocated, and the only
 * large allocations are the inflated image data, exactly the size the header implies, and the
 * decoded pixels.
 *
 * All color types, bit depths, filter types, and Adam7 interlacing are supported. Colors are taken
 * as sRGB: gAMA, cHRM, iCCP, sRGB, and the other ancillary chunks are ignored, as are the chunks of
 * animated PNG, so an APNG decodes to its default image. 16-bit samples are reduced to their high
 * byte. tRNS gives transparency to the color types without an alpha channel. Errors in the critical
 * chunks make the image malformed; a tRNS chunk that is not valid for the image is ignored, as
 * libpng does. A palette index beyond the palette gives transparent black, so that a slightly
 * broken image still shows.
 */
internal object PngDecoder {
    /** The outcome of [decode]. */
    sealed interface Result {
        /** The image decoded. */
        class Decoded(val image: DecodedImage) : Result

        /** The image has more pixels than the limit, or than an array can hold. */
        data object TooLarge : Result

        /** The data is not a valid PNG image. */
        data object Malformed : Result
    }

    /**
     * Reads the width and height from the IHDR chunk without decoding the image. Returns null if
     * the [length] bytes of [data] from [offset] do not start with the PNG signature and a valid
     * IHDR chunk.
     */
    fun readSize(data: ByteArray, offset: Int, length: Int): PngSize? {
        checkRange(data, offset, length)
        val header = readHeader(data, offset, length) ?: return null
        return PngSize(header.width, header.height)
    }

    /**
     * Decodes the PNG image in [length] bytes of [data] from [offset]. Returns [Result.TooLarge] if
     * it has more than [maxPixels] pixels, which is checked from the IHDR chunk before the image
     * data is looked at, and [Result.Malformed] if the data is not a valid PNG image.
     */
    fun decode(data: ByteArray, offset: Int, length: Int, maxPixels: Long): Result {
        checkRange(data, offset, length)
        val header = readHeader(data, offset, length) ?: return Result.Malformed
        val pixelCount = header.width.toLong() * header.height
        if (pixelCount > maxPixels || pixelCount > MAX_ARRAY_SIZE) return Result.TooLarge
        val chunks =
            readChunks(data, offset + HEADER_END, offset + length, header)
                ?: return Result.Malformed
        val expectedSize = header.passes().sumOf { pass -> pass.dataSize(header.bitsPerPixel) }
        if (expectedSize > MAX_ARRAY_SIZE) return Result.TooLarge
        val raw =
            inflateIdat(data, chunks.firstIdat, expectedSize.toInt()) ?: return Result.Malformed
        if (raw.size != expectedSize.toInt()) return Result.Malformed
        val converter =
            Converter(header, chunks.palette, data, chunks.trnsOffset, chunks.trnsLength)
        val pixels = IntArray(pixelCount.toInt())
        var position = 0
        for (pass in header.passes()) {
            if (pass.columns == 0L || pass.rows == 0L) continue
            val columns = pass.columns.toInt()
            val rows = pass.rows.toInt()
            val rowBytes = pass.rowBytes(header.bitsPerPixel).toInt()
            if (!unfilter(raw, position, rows, rowBytes, header.filterBytesPerPixel)) {
                return Result.Malformed
            }
            for (row in 0 until rows) {
                val outRow = pass.y + row * pass.dy
                converter.convertRow(
                    raw,
                    position + 1,
                    columns,
                    pixels,
                    outRow * header.width + pass.x,
                    pass.dx,
                )
                position += rowBytes + 1
            }
        }
        return Result.Decoded(DecodedImage(header.width, header.height, pixels))
    }

    private fun checkRange(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "range $offset+$length out of ${data.size}"
        }
    }

    /** The largest array size that the JVM reliably allocates. */
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8L

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** The length of the signature and the IHDR chunk, which must come first. */
    private const val HEADER_END = 8 + 12 + 13

    private const val IHDR = 0x49484452
    private const val PLTE = 0x504c5445
    private const val IDAT = 0x49444154
    private const val IEND = 0x49454e44
    private const val TRNS = 0x74524e53

    private const val GRAY = 0
    private const val RGB = 2
    private const val PALETTE = 3
    private const val GRAY_ALPHA = 4
    private const val RGBA = 6

    /** The fields of the IHDR chunk, validated. */
    private class Header(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        val interlaced: Boolean,
    ) {
        val channels =
            when (colorType) {
                RGB -> 3
                GRAY_ALPHA -> 2
                RGBA -> 4
                else -> 1
            }
        val bitsPerPixel = channels * bitDepth

        /**
         * The distance in bytes to the corresponding byte of the pixel on the left, for filters.
         */
        val filterBytesPerPixel = maxOf(1, bitsPerPixel / 8)

        fun passes(): List<Pass> =
            if (interlaced) {
                ADAM7.map { (x, y, dx, dy) -> Pass(width, height, x, y, dx, dy) }
            } else {
                listOf(Pass(width, height, 0, 0, 1, 1))
            }
    }

    /** The Adam7 passes as x, y, dx, dy: the pixels at x + i * dx, y + j * dy. */
    private val ADAM7 =
        listOf(
            listOf(0, 0, 8, 8),
            listOf(4, 0, 8, 8),
            listOf(0, 4, 4, 8),
            listOf(2, 0, 4, 4),
            listOf(0, 2, 2, 4),
            listOf(1, 0, 2, 2),
            listOf(0, 1, 1, 2),
        )

    /** A reduced image of an interlaced image, or the whole of one that is not interlaced. */
    private class Pass(width: Int, height: Int, val x: Int, val y: Int, val dx: Int, val dy: Int) {
        val columns = if (width > x) (width.toLong() - x + dx - 1) / dx else 0L
        val rows = if (height > y) (height.toLong() - y + dy - 1) / dy else 0L

        fun rowBytes(bitsPerPixel: Int): Long = (columns * bitsPerPixel + 7) / 8

        /** The size of the pass in the inflated data: its rows, each with a filter type byte. */
        fun dataSize(bitsPerPixel: Int): Long =
            if (columns == 0L) 0L else rows * (1 + rowBytes(bitsPerPixel))
    }

    /** Reads the signature and the IHDR chunk, or returns null if they are not valid. */
    private fun readHeader(data: ByteArray, offset: Int, length: Int): Header? {
        if (length < HEADER_END) return null
        for (i in SIGNATURE.indices) {
            if (data[offset + i] != SIGNATURE[i]) return null
        }
        val chunk = offset + 8
        if (readInt(data, chunk) != 13 || readInt(data, chunk + 4) != IHDR) return null
        if (!crcMatches(data, chunk, 13)) return null
        val width = readInt(data, chunk + 8)
        val height = readInt(data, chunk + 12)
        val bitDepth = data[chunk + 16].toInt()
        val colorType = data[chunk + 17].toInt()
        val compression = data[chunk + 18].toInt()
        val filter = data[chunk + 19].toInt()
        val interlace = data[chunk + 20].toInt()
        // A negative value is above 2^31 - 1, which the specification does not allow.
        if (width <= 0 || height <= 0) return null
        val depthValid =
            when (colorType) {
                GRAY ->
                    bitDepth == 1 ||
                        bitDepth == 2 ||
                        bitDepth == 4 ||
                        bitDepth == 8 ||
                        bitDepth == 16
                PALETTE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8
                RGB,
                GRAY_ALPHA,
                RGBA -> bitDepth == 8 || bitDepth == 16
                else -> false
            }
        if (!depthValid || compression != 0 || filter != 0 || (interlace != 0 && interlace != 1)) {
            return null
        }
        return Header(width, height, bitDepth, colorType, interlace == 1)
    }

    /** What [readChunks] found in the chunks after IHDR. */
    private class Chunks(
        /** The offset of the first IDAT chunk. The IDAT chunks are consecutive. */
        val firstIdat: Int,
        /** The palette as opaque 0xFFRRGGBB colors, for color type 3 only. */
        val palette: IntArray?,
        /** The offset of the data of the tRNS chunk, or -1 if there is none. */
        val trnsOffset: Int,
        val trnsLength: Int,
    )

    /**
     * Reads the chunks from [start] up to IEND and checks their structure, or returns null if they
     * are malformed. The IDAT data is not inflated yet.
     */
    private fun readChunks(data: ByteArray, start: Int, end: Int, header: Header): Chunks? {
        var position = start
        var firstIdat = -1
        var idatEnded = false
        var plteSeen = false
        var palette: IntArray? = null
        var trnsOffset = -1
        var trnsLength = 0
        while (true) {
            if (end - position < 12) return null
            val length = readInt(data, position)
            if (length < 0 || length > end - position - 12) return null
            val type = readInt(data, position + 4)
            for (i in 4 until 8) {
                val c = data[position + i].toInt() or 0x20
                if (c < 'a'.code || c > 'z'.code) return null
            }
            if (!crcMatches(data, position, length)) return null
            val body = position + 8
            if (type == IDAT) {
                if (firstIdat < 0) firstIdat = position else if (idatEnded) return null
            } else {
                if (firstIdat >= 0) idatEnded = true
                when (type) {
                    IHDR -> return null
                    PLTE -> {
                        if (plteSeen || firstIdat >= 0) return null
                        if (header.colorType == GRAY || header.colorType == GRAY_ALPHA) return null
                        if (length == 0 || length % 3 != 0 || length > 256 * 3) return null
                        plteSeen = true
                        // For RGB and RGBA, the palette only suggests colors to show the image
                        // with on displays with fewer colors.
                        if (header.colorType == PALETTE) {
                            palette =
                                IntArray(length / 3) { i ->
                                    val p = body + i * 3
                                    (0xff shl 24) or
                                        ((data[p].toInt() and 0xff) shl 16) or
                                        ((data[p + 1].toInt() and 0xff) shl 8) or
                                        (data[p + 2].toInt() and 0xff)
                                }
                        }
                    }
                    TRNS ->
                        // tRNS must come after PLTE and before IDAT, at most once.
                        if (
                            trnsOffset < 0 &&
                                firstIdat < 0 &&
                                (header.colorType != PALETTE || plteSeen)
                        ) {
                            trnsOffset = body
                            trnsLength = length
                        }
                    IEND -> {
                        if (length != 0) return null
                        break
                    }
                    else ->
                        // Bit 5 of the first byte of the type is clear in critical chunks, which a
                        // decoder must understand.
                        if (data[position + 4].toInt() and 0x20 == 0) return null
                }
            }
            position = body + length + 4
        }
        if (firstIdat < 0) return null
        if (header.colorType == PALETTE && palette == null) return null
        return Chunks(firstIdat, palette, trnsOffset, trnsLength)
    }

    /**
     * Inflates the data of the consecutive IDAT chunks from [firstIdat], which [readChunks] has
     * checked, to at most [expectedSize] bytes.
     */
    private fun inflateIdat(data: ByteArray, firstIdat: Int, expectedSize: Int): ByteArray? {
        var position = firstIdat
        return Zlib.inflate(expectedSize) { inflater ->
            if (readInt(data, position + 4) != IDAT) {
                false
            } else {
                val length = readInt(data, position)
                inflater.setInput(data, position + 8, length)
                position += length + 12
                true
            }
        }
    }

    /**
     * Reverses the filters of the [rows] rows of [rowBytes] bytes, each after its filter type byte,
     * from [start] in [raw], in place. Returns false if a filter type is unknown.
     */
    private fun unfilter(raw: ByteArray, start: Int, rows: Int, rowBytes: Int, bpp: Int): Boolean {
        val stride = rowBytes + 1
        for (row in 0 until rows) {
            val line = start + row * stride + 1
            val previous = line - stride
            when (raw[line - 1].toInt()) {
                0 -> {}
                1 ->
                    for (i in bpp until rowBytes) {
                        raw[line + i] = (raw[line + i] + raw[line + i - bpp]).toByte()
                    }
                2 ->
                    if (row > 0) {
                        for (i in 0 until rowBytes) {
                            raw[line + i] = (raw[line + i] + raw[previous + i]).toByte()
                        }
                    }
                3 ->
                    for (i in 0 until rowBytes) {
                        val left = if (i >= bpp) raw[line + i - bpp].toInt() and 0xff else 0
                        val up = if (row > 0) raw[previous + i].toInt() and 0xff else 0
                        raw[line + i] = (raw[line + i] + ((left + up) ushr 1)).toByte()
                    }
                4 ->
                    for (i in 0 until rowBytes) {
                        val left = if (i >= bpp) raw[line + i - bpp].toInt() and 0xff else 0
                        val up = if (row > 0) raw[previous + i].toInt() and 0xff else 0
                        val upLeft =
                            if (i >= bpp && row > 0) raw[previous + i - bpp].toInt() and 0xff else 0
                        raw[line + i] = (raw[line + i] + paeth(left, up, upLeft)).toByte()
                    }
                else -> return false
            }
        }
        return true
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Converts unfiltered rows of samples to 0xAARRGGBB colors. */
    private class Converter(
        header: Header,
        palette: IntArray?,
        data: ByteArray,
        trnsOffset: Int,
        trnsLength: Int,
    ) {
        private val colorType = header.colorType
        private val bitDepth = header.bitDepth

        /** The size of a sample in bytes, for bit depths 8 and 16. */
        private val sampleBytes = if (bitDepth == 16) 2 else 1

        /**
         * The colors of all 256 indices for color type 3, with the alpha of tRNS. The indices
         * beyond the palette are transparent black.
         */
        private val colors = IntArray(256)

        /** The transparent gray, red, green, and blue samples of tRNS, or -1 if there are none. */
        private var transparentGray = -1
        private var transparentRed = -1
        private var transparentGreen = -1
        private var transparentBlue = -1

        init {
            palette?.copyInto(colors)
            val hasTrns = trnsOffset >= 0
            when (colorType) {
                PALETTE ->
                    if (hasTrns && palette != null && trnsLength <= palette.size) {
                        for (i in 0 until trnsLength) {
                            val alpha = data[trnsOffset + i].toInt() and 0xff
                            colors[i] = (alpha shl 24) or (colors[i] and 0xffffff)
                        }
                    }
                GRAY ->
                    if (hasTrns && trnsLength == 2) {
                        transparentGray = readShort(data, trnsOffset)
                    }
                RGB ->
                    if (hasTrns && trnsLength == 6) {
                        transparentRed = readShort(data, trnsOffset)
                        transparentGreen = readShort(data, trnsOffset + 2)
                        transparentBlue = readShort(data, trnsOffset + 4)
                    }
            }
        }

        /**
         * Converts the [count] pixels of the row at [line] in [raw] to [pixels], from [out] at
         * intervals of [step].
         */
        fun convertRow(
            raw: ByteArray,
            line: Int,
            count: Int,
            pixels: IntArray,
            out: Int,
            step: Int,
        ) {
            if (bitDepth < 8) {
                convertPackedRow(raw, line, count, pixels, out, step)
                return
            }
            val s = sampleBytes
            var p = line
            var o = out
            when (colorType) {
                GRAY ->
                    for (i in 0 until count) {
                        val gray = sample(raw, p)
                        val alpha = if (gray == transparentGray) 0 else 0xff
                        pixels[o] = (alpha shl 24) or (high(raw, p) * 0x010101)
                        p += s
                        o += step
                    }
                RGB ->
                    for (i in 0 until count) {
                        val transparent =
                            sample(raw, p) == transparentRed &&
                                sample(raw, p + s) == transparentGreen &&
                                sample(raw, p + 2 * s) == transparentBlue
                        val alpha = if (transparent) 0 else 0xff
                        pixels[o] =
                            (alpha shl 24) or
                                (high(raw, p) shl 16) or
                                (high(raw, p + s) shl 8) or
                                high(raw, p + 2 * s)
                        p += 3 * s
                        o += step
                    }
                PALETTE ->
                    for (i in 0 until count) {
                        pixels[o] = colors[raw[p].toInt() and 0xff]
                        p++
                        o += step
                    }
                GRAY_ALPHA ->
                    for (i in 0 until count) {
                        pixels[o] = (high(raw, p + s) shl 24) or (high(raw, p) * 0x010101)
                        p += 2 * s
                        o += step
                    }
                RGBA ->
                    for (i in 0 until count) {
                        pixels[o] =
                            (high(raw, p + 3 * s) shl 24) or
                                (high(raw, p) shl 16) or
                                (high(raw, p + s) shl 8) or
                                high(raw, p + 2 * s)
                        p += 4 * s
                        o += step
                    }
            }
        }

        /** Converts a row of gray or palette samples of 1, 2, or 4 bits, packed from the MSB. */
        private fun convertPackedRow(
            raw: ByteArray,
            line: Int,
            count: Int,
            pixels: IntArray,
            out: Int,
            step: Int,
        ) {
            val mask = (1 shl bitDepth) - 1
            // Scales the largest sample to 255: 255, 85, or 17 times the sample.
            val scale = 255 / mask
            var p = line
            var shift = 8 - bitDepth
            var o = out
            for (i in 0 until count) {
                val value = (raw[p].toInt() shr shift) and mask
                pixels[o] =
                    if (colorType == PALETTE) {
                        colors[value]
                    } else {
                        val alpha = if (value == transparentGray) 0 else 0xff
                        (alpha shl 24) or (value * scale * 0x010101)
                    }
                shift -= bitDepth
                if (shift < 0) {
                    shift = 8 - bitDepth
                    p++
                }
                o += step
            }
        }

        /** The sample at [p] at its full precision. */
        private fun sample(raw: ByteArray, p: Int): Int =
            if (sampleBytes == 2) readShort(raw, p) else raw[p].toInt() and 0xff

        /** The sample at [p] reduced to 8 bits. */
        private fun high(raw: ByteArray, p: Int): Int = raw[p].toInt() and 0xff
    }

    private fun readInt(data: ByteArray, p: Int): Int =
        ((data[p].toInt() and 0xff) shl 24) or
            ((data[p + 1].toInt() and 0xff) shl 16) or
            ((data[p + 2].toInt() and 0xff) shl 8) or
            (data[p + 3].toInt() and 0xff)

    private fun readShort(data: ByteArray, p: Int): Int =
        ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)

    /**
     * Whether the CRC at the end of the chunk at [chunk] with [length] bytes of data matches its
     * type and data.
     */
    private fun crcMatches(data: ByteArray, chunk: Int, length: Int): Boolean {
        val crc = CRC32()
        crc.update(data, chunk + 4, length + 4)
        return crc.value.toInt() == readInt(data, chunk + 8 + length)
    }
}
