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

@file:OptIn(InternalVtfxApi::class)

package com.google.vtfx.core

import com.google.vtfx.core.parser.Parser
import com.google.vtfx.core.parser.ParserHandler
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that [Emulator.printAscii], which writes plain text directly into the cells
 * (docs/decisions/0014), leaves the same state as printing each character with [Emulator.print].
 */
class PrintAsciiTest {
    /** Passes everything to [emulator], but prints ASCII one character at a time. */
    private class CharacterByCharacter(private val emulator: Emulator) : ParserHandler by emulator {
        override fun printAscii(bytes: ByteArray, from: Int, to: Int) {
            for (i in from until to) emulator.print(bytes[i].toInt())
        }
    }

    private class Model(columns: Int, rows: Int, bulk: Boolean) {
        val emulator =
            Emulator(columns, rows, TerminalLimits(scrollbackLines = 8), AmbiguousWidth.Narrow) {}
        private val parser = Parser(if (bulk) emulator else CharacterByCharacter(emulator), 4096)

        fun write(bytes: ByteArray) = parser.parse(bytes)
    }

    @Test
    fun bulkPrintingMatchesPrintingEachCharacter() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val columns = 1 + random.nextInt(12)
            val rows = 1 + random.nextInt(4)
            val bulk = Model(columns, rows, bulk = true)
            val single = Model(columns, rows, bulk = false)
            repeat(8) {
                val chunk = chunk(random)
                bulk.write(chunk)
                single.write(chunk)
            }
            assertSameState(bulk.emulator, single.emulator, "iteration $iteration")
        }
    }

    private fun chunk(random: Random): ByteArray {
        val text = StringBuilder()
        repeat(1 + random.nextInt(6)) {
            text.append(PIECES[random.nextInt(PIECES.size)])
            if (random.nextBoolean()) {
                repeat(random.nextInt(20)) { text.append(('!' + random.nextInt(94))) }
            }
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    private fun assertSameState(actual: Emulator, expected: Emulator, message: String) {
        assertEquals(expected.cursorX, actual.cursorX, "$message: cursor column")
        assertEquals(expected.cursorY, actual.cursorY, "$message: cursor row")
        assertEquals(expected.pendingWrap, actual.pendingWrap, "$message: pending wrap")
        for (row in 0 until expected.rows) {
            val line = actual.screen[row]
            assertTrue(line.contentEquals(expected.screen[row]), "$message: row $row")
            assertEquals(
                expected.screen[row].isWrapped,
                line.isWrapped,
                "$message: row $row wrapped",
            )
        }
        assertEquals(
            expected.scrollbackSize(),
            actual.scrollbackSize(),
            "$message: scrollback size",
        )
        for (i in 0 until expected.scrollbackSize()) {
            assertTrue(
                actual.scrollbackLine(i).contentEquals(expected.scrollbackLine(i)),
                "$message: scrollback $i",
            )
        }
    }

    private companion object {
        const val SEED = 20261012L
        const val ITERATIONS = 2000

        /** Output that interacts with plain text: wrapping, wide characters, modes, and styles. */
        val PIECES =
            listOf(
                "",
                "\r",
                "\n",
                "\b",
                "\t",
                "あ",
                "e\u0301",
                "\uD83C\uDDEF",
                "\u001b[1;31m",
                "\u001b[0m",
                "\u001b[4h",
                "\u001b[4l",
                "\u001b[?7l",
                "\u001b[?7h",
                "\u001b(0",
                "\u001b(B",
                "\u000e",
                "\u000f",
                "\u001b[2b",
                "\u001b[H",
                "\u001b[3G",
                "\u001b]8;;http://a\u0007",
                "\u001b]8;;\u0007",
                "\u001b[1;2r",
                "\u001b[r",
                "\u001b[?1049h",
                "\u001b[?1049l",
                "\u001b[2J",
                "\u001b[K",
            )
    }
}
