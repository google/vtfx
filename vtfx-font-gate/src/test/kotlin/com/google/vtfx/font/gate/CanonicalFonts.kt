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

/**
 * The canonical fonts of the resources directory `canonical/`, which `CanonicalFixturesTest` in
 * vtfx-font keeps what `FontFile.sanitize` writes. This module cannot depend on vtfx-font, which
 * depends on it, so its tests take the fonts that vtfx-font writes from these files.
 */
object CanonicalFonts {
    /** Every font, in the order that the input of [VerifierFuzzTest] refers to them by. */
    val names: List<String> =
        listOf("variations.ttf", "dual-width.ttf", "cff.ttf", "fewer-points.ttf")

    fun read(name: String): ByteArray {
        val stream =
            checkNotNull(CanonicalFonts::class.java.getResourceAsStream("canonical/$name")) {
                "no canonical font $name"
            }
        return stream.use { it.readBytes() }
    }
}

/** The tags of the tables of the canonical form, in the order of the table directory. */
val TABLE_TAGS: List<String> =
    listOf("OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post")

/** The size of the header and the table directory of a canonical font. */
const val DIRECTORY_END: Int = 12 + 16 * 10

private const val HEAD = 3
private const val CHECKSUM_MAGIC = -0x4E4F5046 // 0xB1B0AFBA

/** The offset of table [tag] in [font], as its directory says. */
fun tableOffset(font: ByteArray, tag: String): Int =
    u32At(font, 12 + TABLE_TAGS.indexOf(tag) * 16 + 8).toInt()

/** The length of table [tag] in [font], as its directory says. */
fun tableLength(font: ByteArray, tag: String): Int =
    u32At(font, 12 + TABLE_TAGS.indexOf(tag) * 16 + 12).toInt()

fun u32At(bytes: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

fun putU16(bytes: ByteArray, at: Int, value: Int) {
    for (i in 0 until 2) bytes[at + i] = (value ushr (8 - i * 8)).toByte()
}

fun putU32(bytes: ByteArray, at: Int, value: Int) {
    for (i in 0 until 4) bytes[at + i] = (value ushr (24 - i * 8)).toByte()
}

/**
 * [font], at least [DIRECTORY_END] bytes long, with the checksums of its tables and
 * `head.checksumAdjustment` made right again after a change, as a writer computes them. A table
 * that the directory places outside the file, which the verifier rejects anyway, keeps its
 * checksum, and so does everything if `head` is outside the file.
 */
fun withChecksums(font: ByteArray): ByteArray {
    val out = font.copyOf()
    val head = u32At(out, 12 + HEAD * 16 + 8)
    if (head > out.size - 12) return out
    // The adjustment is left out of every checksum, which is the same as counting it as zero.
    putU32(out, head.toInt() + 8, 0)
    for (i in TABLE_TAGS.indices) {
        val offset = u32At(out, 12 + i * 16 + 8)
        val length = u32At(out, 12 + i * 16 + 12)
        if (offset + length > out.size) continue
        putU32(out, 12 + i * 16 + 4, checksum(out, offset.toInt(), length.toInt()))
    }
    putU32(out, head.toInt() + 8, CHECKSUM_MAGIC - checksum(out, 0, out.size))
    return out
}

/**
 * The sum of the big-endian words of [length] bytes of [bytes] from [offset], padded with zeros.
 */
private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    for (i in 0 until length step 4) {
        var word = 0
        for (j in 0 until 4) {
            val at = offset + i + j
            word = (word shl 8) or (if (i + j < length) bytes[at].toInt() and 0xFF else 0)
        }
        sum += word
    }
    return sum
}
