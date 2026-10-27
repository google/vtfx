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

import java.util.Random
import kotlin.test.assertEquals
import org.junit.Test

class Utf8DecoderTest {
    private fun decode(vararg chunks: ByteArray): List<Int> {
        val decoder = Utf8Decoder()
        val result = mutableListOf<Int>()
        for (chunk in chunks) decoder.decode(chunk, 0, chunk.size) { result += it }
        return result
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val fffd = 0xFFFD

    @Test
    fun decodesAllLengths() {
        assertEquals(listOf(0x41, 0xE9, 0x3042, 0x1F600), decode("Aéあ😀".toByteArray()))
    }

    @Test
    fun decodesSequenceSplitAcrossChunks() {
        assertEquals(listOf(0x1F600), decode(bytes(0xF0, 0x9F), bytes(0x98), bytes(0x80)))
    }

    @Test
    fun replacesUnexpectedContinuationByte() {
        assertEquals(listOf(fffd, 0x41), decode(bytes(0x80, 0x41)))
    }

    @Test
    fun replacesTruncatedSequenceAndKeepsTheNextByte() {
        assertEquals(listOf(fffd, 0x41), decode(bytes(0xE3, 0x81, 0x41)))
        assertEquals(listOf(fffd, 0x3042), decode(bytes(0xE3, 0xE3, 0x81, 0x82)))
    }

    @Test
    fun rejectsOverlongEncodings() {
        assertEquals(listOf(fffd, fffd), decode(bytes(0xC0, 0xAF)))
        assertEquals(listOf(fffd, fffd, fffd), decode(bytes(0xE0, 0x80, 0xAF)))
    }

    @Test
    fun rejectsSurrogatesAndOutOfRange() {
        assertEquals(listOf(fffd, fffd, fffd), decode(bytes(0xED, 0xA0, 0x80)))
        assertEquals(listOf(fffd, fffd, fffd, fffd), decode(bytes(0xF4, 0x90, 0x80, 0x80)))
        assertEquals(listOf(fffd), decode(bytes(0xFF)))
    }

    @Test
    fun decodesBoundaryCodePoints() {
        assertEquals(
            listOf(0x7F, 0x80, 0x7FF, 0x800, 0xFFFF, 0x10000, 0x10FFFF),
            decode(
                bytes(
                    0x7F,
                    0xC2,
                    0x80,
                    0xDF,
                    0xBF,
                    0xE0,
                    0xA0,
                    0x80,
                    0xEF,
                    0xBF,
                    0xBF,
                    0xF0,
                    0x90,
                    0x80,
                    0x80,
                    0xF4,
                    0x8F,
                    0xBF,
                    0xBF,
                )
            ),
        )
    }

    @Test
    fun decodesTheSameInOneCallAsOneByteAtATime() {
        // Whole sequences within a call are decoded at once; one byte per call goes through the
        // states. Bytes from a small set, so that valid and broken sequences of every kind occur.
        val pieces =
            intArrayOf(0x41, 0x7F, 0x80, 0xBF, 0xC0, 0xC2, 0xDF, 0xE0, 0xE1, 0xE3, 0xED, 0xEF)
        val more = intArrayOf(0x81, 0x9F, 0xA0, 0xF0, 0xF4, 0xF5, 0xFF)
        val random = Random(20261027L)
        repeat(5000) { iteration ->
            val input =
                ByteArray(random.nextInt(12)) {
                    val set = if (random.nextInt(4) == 0) more else pieces
                    set[random.nextInt(set.size)].toByte()
                }
            val oneByteAtATime = decode(*Array(input.size) { byteArrayOf(input[it]) })
            assertEquals(oneByteAtATime, decode(input), "iteration $iteration")
        }
    }
}
