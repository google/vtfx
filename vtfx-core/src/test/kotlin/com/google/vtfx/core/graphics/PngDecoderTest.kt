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
import com.google.vtfx.core.graphics.PngTestEncoder.Image
import com.google.vtfx.core.graphics.PngTestEncoder.PALETTE
import com.google.vtfx.core.graphics.PngTestEncoder.RGB
import com.google.vtfx.core.graphics.PngTestEncoder.RGBA
import com.google.vtfx.core.graphics.PngTestEncoder.VALID_FORMATS
import com.google.vtfx.core.graphics.PngTestEncoder.chunk
import com.google.vtfx.core.graphics.PngTestEncoder.deflate
import com.google.vtfx.core.graphics.PngTestEncoder.encode
import com.google.vtfx.core.graphics.PngTestEncoder.expectedColor
import com.google.vtfx.core.graphics.PngTestEncoder.ihdr
import com.google.vtfx.core.graphics.PngTestEncoder.png
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

class PngDecoderTest {
    private val maxPixels = 1L shl 20

    private fun decode(png: ByteArray, maxPixels: Long = this.maxPixels) =
        PngDecoder.decode(png, 0, png.size, maxPixels)

    private fun decodeImage(png: ByteArray): DecodedImage {
        val result = decode(png)
        assertIs<PngDecoder.Result.Decoded>(result)
        return result.image
    }

    private fun assertMalformed(png: ByteArray, message: String? = null) {
        assertSame(PngDecoder.Result.Malformed, decode(png), message)
    }

    /** Random samples for [width] x [height] pixels, each below [limit]. */
    private fun randomImage(
        width: Int,
        height: Int,
        colorType: Int,
        bitDepth: Int,
        seed: Int,
        interlaced: Boolean = false,
        limit: Int = 1 shl bitDepth,
        filter: (Int) -> Int = { it % 5 },
    ): Image {
        val random = Random(seed)
        val samples =
            IntArray(width * height * PngTestEncoder.channels(colorType)) { random.nextInt(limit) }
        return Image(width, height, colorType, bitDepth, samples, interlaced, filter)
    }

    private fun randomPalette(entries: Int, seed: Int) = Random(seed).nextBytes(entries * 3)

    /** Encodes [image], decodes it, and checks every pixel against the specification. */
    private fun assertRoundTrip(
        image: Image,
        palette: ByteArray? = null,
        trns: ByteArray? = null,
        idatCount: Int = 1,
        message: String = "",
    ) {
        val decoded = decodeImage(encode(image, palette, trns, idatCount))
        assertEquals(image.width, decoded.width, message)
        assertEquals(image.height, decoded.height, message)
        for (i in 0 until image.width * image.height) {
            val expected = expectedColor(image, i, palette, trns)
            if (decoded.pixels[i] != expected) {
                throw AssertionError(
                    "$message: pixel ${i % image.width},${i / image.width}: expected " +
                        "%08x, got %08x".format(expected, decoded.pixels[i])
                )
            }
        }
    }

    private fun Image.description() =
        "type $colorType depth $bitDepth ${width}x$height interlaced $interlaced"

    @Test
    fun `decodes every color type and bit depth, interlaced or not`() {
        val sizes = listOf(1 to 1, 3 to 5, 9 to 9, 17 to 3, 2 to 17, 33 to 2, 8 to 8, 16 to 16)
        var seed = 0
        for ((colorType, bitDepth) in VALID_FORMATS) {
            for (interlaced in listOf(false, true)) {
                for ((width, height) in sizes) {
                    val palette =
                        if (colorType == PALETTE) randomPalette(1 shl bitDepth, seed) else null
                    val image = randomImage(width, height, colorType, bitDepth, seed++, interlaced)
                    assertRoundTrip(image, palette, message = image.description())
                }
            }
        }
    }

    @Test
    fun `reverses each filter type`() {
        for (filterType in 0..4) {
            for ((colorType, bitDepth) in VALID_FORMATS) {
                for (interlaced in listOf(false, true)) {
                    val palette =
                        if (colorType == PALETTE) randomPalette(1 shl bitDepth, filterType)
                        else null
                    val image =
                        randomImage(23, 11, colorType, bitDepth, filterType, interlaced) {
                            filterType
                        }
                    assertRoundTrip(
                        image,
                        palette,
                        message = "filter $filterType " + image.description(),
                    )
                }
            }
        }
    }

    @Test
    fun `reverses filters on smooth images`() {
        // Gradients make the predictors matter, unlike random samples.
        for (filterType in 0..4) {
            val width = 40
            val height = 30
            val samples =
                IntArray(width * height * 4) { i ->
                    val pixel = i / 4
                    val x = pixel % width
                    val y = pixel / width
                    when (i % 4) {
                        0 -> x * 6
                        1 -> y * 8
                        2 -> (x + y) * 3
                        else -> 255 - x
                    }
                }
            assertRoundTrip(
                Image(width, height, RGBA, 8, samples) { filterType },
                message = "filter $filterType",
            )
        }
    }

    @Test
    fun `decodes palette images with fewer entries than the bit depth allows`() {
        for (bitDepth in listOf(1, 2, 4, 8)) {
            val entries = maxOf(1, (1 shl bitDepth) - 1)
            val image = randomImage(13, 7, PALETTE, bitDepth, bitDepth, limit = entries)
            assertRoundTrip(image, randomPalette(entries, bitDepth), message = "depth $bitDepth")
        }
    }

    @Test
    fun `gives indices beyond the palette transparent black`() {
        val image = Image(4, 1, PALETTE, 8, intArrayOf(0, 1, 2, 255))
        val palette = byteArrayOf(1, 2, 3, 4, 5, 6)
        val decoded = decodeImage(encode(image, palette))
        assertEquals(listOf(0xff010203.toInt(), 0xff040506.toInt(), 0, 0), decoded.pixels.toList())
    }

    @Test
    fun `applies tRNS to palette images`() {
        for (bitDepth in listOf(1, 2, 4, 8)) {
            val entries = 1 shl bitDepth
            val image = randomImage(11, 5, PALETTE, bitDepth, bitDepth, interlaced = bitDepth == 2)
            // Fewer alpha values than palette entries: the rest are opaque.
            val trns = Random(bitDepth).nextBytes(maxOf(1, entries / 2))
            assertRoundTrip(
                image,
                randomPalette(entries, bitDepth),
                trns,
                message = "depth $bitDepth",
            )
        }
    }

    @Test
    fun `applies tRNS to gray images`() {
        for (bitDepth in listOf(1, 2, 4, 8, 16)) {
            val image =
                randomImage(10, 10, GRAY, bitDepth, bitDepth, limit = minOf(1 shl bitDepth, 4))
            val transparent = image.samples[0]
            val trns = byteArrayOf((transparent ushr 8).toByte(), transparent.toByte())
            assertRoundTrip(image, trns = trns, message = "depth $bitDepth")
            val decoded = decodeImage(encode(image, trns = trns))
            assertEquals(0, decoded.pixels[0] ushr 24)
        }
    }

    @Test
    fun `applies tRNS to RGB images`() {
        for (bitDepth in listOf(8, 16)) {
            val image = randomImage(10, 10, RGB, bitDepth, bitDepth, interlaced = true, limit = 2)
            val trns = ByteArray(6) { i -> if (i % 2 == 1) image.samples[i / 2].toByte() else 0 }
            assertRoundTrip(image, trns = trns, message = "depth $bitDepth")
            val decoded = decodeImage(encode(image, trns = trns))
            assertEquals(0, decoded.pixels[0] ushr 24)
        }
    }

    @Test
    fun `compares tRNS of 16-bit images at full precision`() {
        // The two pixels differ only in the low byte.
        val image = Image(2, 1, GRAY, 16, intArrayOf(0x1234, 0x1235))
        val decoded = decodeImage(encode(image, trns = byteArrayOf(0x12, 0x34)))
        assertEquals(listOf(0x00121212, 0xff121212.toInt()), decoded.pixels.toList())
    }

    @Test
    fun `reduces 16-bit samples to their high byte`() {
        val image = Image(1, 1, RGBA, 16, intArrayOf(0x12ff, 0x3400, 0x5680, 0x78ff))
        assertEquals(0x78123456, decodeImage(encode(image)).pixels[0])
    }

    @Test
    fun `scales low bit depths to 8 bits`() {
        val image = Image(4, 1, GRAY, 2, intArrayOf(0, 1, 2, 3))
        val expected = listOf(0xff000000, 0xff555555, 0xffaaaaaa, 0xffffffff).map { it.toInt() }
        assertEquals(expected, decodeImage(encode(image)).pixels.toList())
    }

    @Test
    fun `ignores invalid tRNS chunks`() {
        val gray = randomImage(4, 4, GRAY, 8, 1)
        // tRNS with the wrong length, or for a color type with alpha.
        val withBadTrns = encode(gray, trns = byteArrayOf(0, 0, 0))
        assertEquals(
            decodeImage(encode(gray)).pixels.toList(),
            decodeImage(withBadTrns).pixels.toList(),
        )
        val rgba = randomImage(4, 4, RGBA, 8, 2)
        val rgbaWithTrns = encode(rgba, trns = byteArrayOf(0, 1))
        assertEquals(
            decodeImage(encode(rgba)).pixels.toList(),
            decodeImage(rgbaWithTrns).pixels.toList(),
        )
        // More alpha values than palette entries.
        val indexed = randomImage(4, 4, PALETTE, 2, 3, limit = 2)
        val palette = randomPalette(2, 3)
        val opaque = decodeImage(encode(indexed, palette)).pixels.toList()
        assertEquals(opaque, decodeImage(encode(indexed, palette, ByteArray(3))).pixels.toList())
    }

    @Test
    fun `concatenates multiple IDAT chunks`() {
        val image = randomImage(30, 20, RGB, 8, 5, interlaced = true)
        for (count in listOf(2, 3, 50)) assertRoundTrip(
            image,
            idatCount = count,
            message = "$count chunks",
        )
        // Empty IDAT chunks are allowed too.
        val zlib = deflate(image.scanlines())
        val png =
            png(
                ihdr(30, 20, 8, RGB, interlace = 1),
                chunk("IDAT"),
                chunk("IDAT", zlib.copyOf(10)),
                chunk("IDAT"),
                chunk("IDAT", zlib.copyOfRange(10, zlib.size)),
                chunk("IEND"),
            )
        assertEquals(decodeImage(encode(image)).pixels.toList(), decodeImage(png).pixels.toList())
    }

    @Test
    fun `ignores ancillary chunks, including those of APNG`() {
        val image = randomImage(5, 5, RGBA, 8, 6)
        val ancillary =
            listOf(
                chunk("gAMA", byteArrayOf(0, 0, -79, -113)),
                chunk("sRGB", byteArrayOf(0)),
                chunk("acTL", ByteArray(8)),
                chunk("fcTL", ByteArray(26)),
                chunk("zzZz", ByteArray(3)),
            )
        val decoded = decodeImage(encode(image, ancillary = ancillary))
        assertEquals(decodeImage(encode(image)).pixels.toList(), decoded.pixels.toList())
        // fdAT after IDAT, and a text chunk after it.
        val zlib = deflate(image.scanlines())
        val apng =
            png(
                ihdr(5, 5, 8, RGBA),
                chunk("acTL", ByteArray(8)),
                chunk("IDAT", zlib),
                chunk("fcTL", ByteArray(26)),
                chunk("fdAT", ByteArray(4) + deflate(ByteArray(30))),
                chunk("tEXt", "a\u0000b".toByteArray()),
                chunk("IEND"),
            )
        assertEquals(decoded.pixels.toList(), decodeImage(apng).pixels.toList())
    }

    @Test
    fun `decodes from an offset into the array`() {
        val png = encode(randomImage(3, 3, RGB, 8, 7))
        val padded = ByteArray(5) + png + ByteArray(7) { -1 }
        val result = PngDecoder.decode(padded, 5, png.size, maxPixels)
        assertIs<PngDecoder.Result.Decoded>(result)
        assertEquals(decodeImage(png).pixels.toList(), result.image.pixels.toList())
        assertEquals(3, PngDecoder.readSize(padded, 5, png.size)?.width)
        // The bytes beyond the range are not read: the end of the range truncates the file.
        assertSame(
            PngDecoder.Result.Malformed,
            PngDecoder.decode(padded, 5, png.size - 1, maxPixels),
        )
    }

    @Test
    fun `reads the size without decoding`() {
        val png = png(ihdr(640, 480, 8, RGBA))
        val size = assertNotNull(PngDecoder.readSize(png, 0, png.size))
        assertEquals(640, size.width)
        assertEquals(480, size.height)
        assertNull(PngDecoder.readSize(png, 0, png.size - 1))
        assertNull(PngDecoder.readSize(png(ihdr(640, 480, 3, RGBA)), 0, png.size))
        assertNull(PngDecoder.readSize(png(ihdr(0, 480, 8, RGBA)), 0, png.size))
        assertNull(PngDecoder.readSize("GIF89a".toByteArray() + ByteArray(40), 0, 46))
    }

    // Hostile and malformed input.

    private val validPng = encode(randomImage(7, 5, RGBA, 8, 8, interlaced = true), idatCount = 2)

    @Test
    fun `rejects the file truncated at every length`() {
        for (length in 0 until validPng.size) {
            assertSame(
                PngDecoder.Result.Malformed,
                PngDecoder.decode(validPng, 0, length, maxPixels),
                "length $length",
            )
        }
    }

    @Test
    fun `rejects a corrupted CRC in every chunk`() {
        var position = 8
        while (position < validPng.size) {
            val length = java.nio.ByteBuffer.wrap(validPng, position, 4).int
            val corrupted = validPng.copyOf()
            corrupted[position + 8 + length] = (corrupted[position + 8 + length] + 1).toByte()
            assertMalformed(corrupted, "chunk at $position")
            position += 12 + length
        }
    }

    @Test
    fun `rejects a bad signature`() {
        val corrupted = validPng.copyOf()
        corrupted[0] = 0
        assertMalformed(corrupted)
    }

    @Test
    fun `reports huge images as too large without decoding them`() {
        val idat = chunk("IDAT", deflate(ByteArray(100)))
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertSame(
                PngDecoder.Result.TooLarge,
                decode(png(ihdr(65535, 65535, 8, RGBA), idat, chunk("IEND"))),
            )
            // The image data is not even looked at.
            assertSame(PngDecoder.Result.TooLarge, decode(png(ihdr(65535, 65535, 16, RGBA))))
            // Too large for an array whatever the limit.
            val huge = png(ihdr(Int.MAX_VALUE, Int.MAX_VALUE, 8, GRAY), idat, chunk("IEND"))
            assertSame(PngDecoder.Result.TooLarge, decode(huge, Long.MAX_VALUE))
            val wide = png(ihdr(Int.MAX_VALUE, 1, 8, GRAY), idat, chunk("IEND"))
            assertSame(PngDecoder.Result.TooLarge, decode(wide, Long.MAX_VALUE))
        }
    }

    @Test
    fun `checks the limit on the pixel count exactly`() {
        val png = encode(randomImage(10, 10, RGB, 8, 9))
        assertIs<PngDecoder.Result.Decoded>(decode(png, 100))
        assertSame(PngDecoder.Result.TooLarge, decode(png, 99))
    }

    @Test
    fun `reports images whose data would not fit an array as too large`() {
        // 2^28 pixels of 16-bit RGBA are 2 GiB of image data, within the pixel limit.
        val png =
            png(
                ihdr(1 shl 14, 1 shl 14, 16, RGBA),
                chunk("IDAT", deflate(ByteArray(1))),
                chunk("IEND"),
            )
        assertSame(PngDecoder.Result.TooLarge, decode(png, 1L shl 28))
    }

    @Test
    fun `rejects a zlib bomb`() {
        // 16 x 16 RGBA is 1040 bytes of image data; the stream inflates to 64 MiB.
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream()
        val zeros = ByteArray(1 shl 20)
        val buffer = ByteArray(1 shl 16)
        repeat(64) {
            deflater.setInput(zeros)
            while (!deflater.needsInput()) out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val png = png(ihdr(16, 16, 8, RGBA), chunk("IDAT", out.toByteArray()), chunk("IEND"))
        assertTimeoutPreemptively(Duration.ofSeconds(2)) { assertMalformed(png) }
    }

    @Test
    fun `rejects image data of the wrong size`() {
        val image = randomImage(8, 8, RGB, 8, 10)
        val scanlines = image.scanlines()
        fun withData(data: ByteArray) =
            png(ihdr(8, 8, 8, RGB), chunk("IDAT", deflate(data)), chunk("IEND"))
        assertIs<PngDecoder.Result.Decoded>(decode(withData(scanlines)))
        assertMalformed(withData(scanlines.copyOf(scanlines.size - 1)), "one byte short")
        assertMalformed(withData(scanlines + 0), "one byte more")
        assertMalformed(withData(ByteArray(0)), "empty")
        // Interlaced images have more filter type bytes than the same image not interlaced.
        val interlaced =
            png(ihdr(8, 8, 8, RGB, interlace = 1), chunk("IDAT", deflate(scanlines)), chunk("IEND"))
        assertMalformed(interlaced, "not interlaced data")
    }

    @Test
    fun `rejects an unknown filter type`() {
        for (filterType in listOf(5, 6, 0x80, 0xff)) {
            val image = randomImage(4, 4, GRAY, 8, 11) { row -> if (row == 2) 0 else 1 }
            val scanlines = image.scanlines()
            scanlines[2 * 5] = filterType.toByte()
            val png = png(ihdr(4, 4, 8, GRAY), chunk("IDAT", deflate(scanlines)), chunk("IEND"))
            assertMalformed(png, "filter type $filterType")
        }
    }

    @Test
    fun `rejects invalid pairs of color type and bit depth`() {
        val valid = VALID_FORMATS.toSet()
        for (colorType in 0..8) {
            for (bitDepth in 0..17) {
                if (colorType to bitDepth in valid) continue
                val png =
                    png(
                        ihdr(1, 1, bitDepth, colorType),
                        chunk("IDAT", deflate(ByteArray(9))),
                        chunk("IEND"),
                    )
                assertMalformed(png, "type $colorType depth $bitDepth")
                assertNull(PngDecoder.readSize(png, 0, png.size))
            }
        }
    }

    @Test
    fun `rejects invalid IHDR fields`() {
        val idat = chunk("IDAT", deflate(ByteArray(5)))
        val end = chunk("IEND")
        assertMalformed(png(ihdr(1, 1, 8, RGBA, compression = 1), idat, end), "compression")
        assertMalformed(png(ihdr(1, 1, 8, RGBA, filter = 1), idat, end), "filter method")
        assertMalformed(png(ihdr(1, 1, 8, RGBA, interlace = 2), idat, end), "interlace method")
        assertMalformed(png(ihdr(0, 1, 8, RGBA), idat, end), "zero width")
        assertMalformed(png(ihdr(1, 0, 8, RGBA), idat, end), "zero height")
        assertMalformed(png(ihdr(-1, 1, 8, RGBA), idat, end), "width above 2^31 - 1")
        assertMalformed(png(ihdr(1, Int.MIN_VALUE, 8, RGBA), idat, end), "height above 2^31 - 1")
    }

    @Test
    fun `rejects misplaced and missing critical chunks`() {
        val gray = deflate(ByteArray(2))
        val idat = chunk("IDAT", gray)
        val end = chunk("IEND")
        val header = ihdr(1, 1, 8, GRAY)
        assertIs<PngDecoder.Result.Decoded>(decode(png(header, idat, end)))
        assertMalformed(png(idat, header, end), "IHDR not first")
        assertMalformed(png(header, header, idat, end), "two IHDR")
        assertMalformed(png(header, idat, header, end), "IHDR after IDAT")
        assertMalformed(png(header, end), "no IDAT")
        assertMalformed(png(header, idat), "no IEND")
        assertMalformed(png(header, idat, chunk("IEND", byteArrayOf(0))), "IEND with data")
        assertMalformed(
            png(
                header,
                chunk("IDAT", gray.copyOf(3)),
                chunk("tEXt", byteArrayOf(0)),
                chunk("IDAT", gray.copyOfRange(3, gray.size)),
                end,
            ),
            "IDAT not consecutive",
        )
        assertMalformed(
            png(header, chunk("CRIT", ByteArray(4)), idat, end),
            "unknown critical chunk",
        )
        assertMalformed(png(header, chunk("PLTE", ByteArray(3)), idat, end), "PLTE in a gray image")
        assertMalformed(png(header, chunk("I@AT", ByteArray(0)), idat, end), "invalid chunk type")
    }

    @Test
    fun `rejects invalid palettes`() {
        val idat = chunk("IDAT", deflate(ByteArray(2)))
        val end = chunk("IEND")
        val header = ihdr(1, 1, 8, PALETTE)
        assertIs<PngDecoder.Result.Decoded>(
            decode(png(header, chunk("PLTE", ByteArray(3)), idat, end))
        )
        assertMalformed(png(header, idat, end), "no PLTE")
        assertMalformed(png(header, idat, chunk("PLTE", ByteArray(3)), end), "PLTE after IDAT")
        assertMalformed(
            png(header, chunk("PLTE", ByteArray(3)), chunk("PLTE", ByteArray(3)), idat, end),
            "two PLTE",
        )
        assertMalformed(png(header, chunk("PLTE", ByteArray(0)), idat, end), "empty PLTE")
        assertMalformed(png(header, chunk("PLTE", ByteArray(4)), idat, end), "PLTE not of triplets")
        assertMalformed(png(header, chunk("PLTE", ByteArray(257 * 3)), idat, end), "PLTE too long")
        // A suggested palette in an RGB image is ignored.
        val rgb =
            png(
                ihdr(1, 1, 8, RGB),
                chunk("PLTE", ByteArray(3)),
                chunk("IDAT", deflate(ByteArray(4))),
                end,
            )
        assertIs<PngDecoder.Result.Decoded>(decode(rgb))
    }

    @Test
    fun `rejects chunk lengths beyond the data`() {
        val header = ihdr(1, 1, 8, GRAY)
        val idat = chunk("IDAT", deflate(ByteArray(2)))
        for (length in listOf(idat.size - 11, 1000, Int.MAX_VALUE, -1, Int.MIN_VALUE)) {
            val png = png(header, idat, chunk("IEND"))
            java.nio.ByteBuffer.wrap(png).putInt(33, length)
            PngTestEncoder.fixCrcs(png)
            assertMalformed(png, "length $length")
        }
    }

    @Test
    fun `decodes images up to the limit`() {
        val png = encode(randomImage(1024, 1024, RGBA, 8, 12, interlaced = true))
        val image = decodeImage(png)
        assertEquals(1024 * 1024, image.pixels.size)
    }
}
