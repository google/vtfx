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

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/**
 * Checks [PngDecoder] against the JDK's PNG encoder and decoder, an implementation written
 * independently of the one in the tests. ImageIO is only available to the tests: the decoder runs
 * on Android, which has no java.awt.
 */
class PngImageIoTest {
    private fun write(image: BufferedImage, interlaced: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam
            if (interlaced) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun decode(png: ByteArray): DecodedImage {
        val result = PngDecoder.decode(png, 0, png.size, 1L shl 20)
        assertIs<PngDecoder.Result.Decoded>(result)
        return result.image
    }

    /**
     * Writes [image] with ImageIO, and checks that the decoder gives the colors that ImageIO reads
     * back, as [expected] takes them from ImageIO's image.
     */
    private fun check(
        image: BufferedImage,
        expected: (BufferedImage, Int, Int) -> Int = { read, x, y -> read.getRGB(x, y) },
    ) {
        for (interlaced in listOf(false, true)) {
            val png = write(image, interlaced)
            val read = ImageIO.read(ByteArrayInputStream(png))
            val decoded = decode(png)
            assertEquals(image.width, decoded.width)
            assertEquals(image.height, decoded.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val want = expected(read, x, y)
                    val got = decoded.pixels[y * image.width + x]
                    if (want != got) {
                        throw AssertionError(
                            "type ${image.type} interlaced $interlaced pixel $x,$y: expected " +
                                "%08x, got %08x".format(want, got)
                        )
                    }
                }
            }
        }
    }

    /** Images of sizes that are and are not multiples of the Adam7 grid. */
    private val sizes = listOf(1 to 1, 7 to 3, 8 to 8, 37 to 23, 64 to 50)

    private fun forEachRandomImage(type: Int, block: (BufferedImage) -> Unit) {
        val random = Random(type)
        for ((width, height) in sizes) {
            val image = BufferedImage(width, height, type)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Some smooth areas too, so that the encoder chooses various filters.
                    val color =
                        if (x < width / 2) random.nextInt() else (x * 0x030507 + y * 0x070301)
                    image.setRGB(x, y, color)
                }
            }
            block(image)
        }
    }

    @Test
    fun `matches ImageIO for RGBA images`() {
        forEachRandomImage(BufferedImage.TYPE_INT_ARGB) { image ->
            check(image)
            // Not premultiplied: the colors set are the colors decoded.
            val decoded = decode(write(image, false))
            assertEquals(image.getRGB(0, 0), decoded.pixels[0])
        }
    }

    @Test
    fun `matches ImageIO for RGB images`() {
        forEachRandomImage(BufferedImage.TYPE_3BYTE_BGR) { image -> check(image) }
        forEachRandomImage(BufferedImage.TYPE_INT_RGB) { image -> check(image) }
    }

    // BufferedImage.getRGB converts gray from a linear color space, so the gray images are
    // compared with the samples instead.

    @Test
    fun `matches ImageIO for 8-bit gray images`() {
        forEachRandomImage(BufferedImage.TYPE_BYTE_GRAY) { image ->
            check(image) { read, x, y ->
                0xff000000.toInt() or (read.raster.getSample(x, y, 0) * 0x010101)
            }
        }
    }

    @Test
    fun `matches ImageIO for 16-bit gray images`() {
        val random = Random(16)
        for ((width, height) in sizes) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY)
            for (y in 0 until height) {
                for (x in 0 until width) image.raster.setSample(x, y, 0, random.nextInt(65536))
            }
            check(image) { read, x, y ->
                0xff000000.toInt() or ((read.raster.getSample(x, y, 0) ushr 8) * 0x010101)
            }
        }
    }

    @Test
    fun `matches ImageIO for palette images`() {
        forEachRandomImage(BufferedImage.TYPE_BYTE_INDEXED) { image -> check(image) }
    }

    @Test
    fun `matches ImageIO for palette images of low bit depths with transparency`() {
        val random = Random(4)
        for (bits in listOf(1, 2, 4)) {
            val entries = 1 shl bits
            val colors = IntArray(entries) { random.nextInt() }
            colors[0] = 0x00123456
            val model =
                IndexColorModel(
                    bits,
                    entries,
                    colors,
                    0,
                    true,
                    -1,
                    java.awt.image.DataBuffer.TYPE_BYTE,
                )
            for ((width, height) in sizes) {
                val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY, model)
                for (y in 0 until height) {
                    for (x in 0 until width) image.raster.setSample(
                        x,
                        y,
                        0,
                        random.nextInt(entries),
                    )
                }
                check(image)
            }
        }
    }
}
