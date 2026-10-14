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

package com.google.vtfx.compose

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSelectionTest {
    private fun snapshot(columns: Int, rows: Int, output: String) =
        Terminal(columns, rows).apply { write(output.toByteArray()) }.snapshot()

    @Test
    fun textAcrossLinesDropsTrailingBlanks() {
        val s = snapshot(10, 3, "first  \r\nsecond\r\nthird")
        assertEquals("rst\nsecond\nth", TerminalSelection(Cell(2, 0), Cell(1, 2)).text(s))
    }

    @Test
    fun selectionCanBeBackwards() {
        val s = snapshot(10, 2, "abc\r\ndef")
        assertEquals("bc\nde", TerminalSelection(Cell(1, 1), Cell(1, 0)).text(s))
    }

    @Test
    fun wrappedLinesAreJoined() {
        val s = snapshot(5, 2, "abcdefgh")
        assertEquals("abcdefgh", TerminalSelection(Cell(0, 0), Cell(4, 1)).text(s))
    }

    @Test
    fun cellsBeyondTheLinesAreNotRead() {
        // A selection made on a wider display, for example before a resize.
        val s = snapshot(5, 2, "abc\r\ndef")
        assertEquals("", TerminalSelection(Cell(7, 0), Cell(9, 0)).text(s))
        assertEquals("\nde", TerminalSelection(Cell(7, 0), Cell(1, 1)).text(s))
        assertEquals("abc\ndef", TerminalSelection(Cell(0, 0), Cell(9, 1)).text(s))
    }

    @Test
    fun selectingTheWholeScreenCopiesTheLinesWritten() {
        // Random lines of letters, spaces, and wide characters, wrapped at random widths.
        for (seed in 0 until 300) {
            val random = Random(seed)
            val lines =
                List(random.nextInt(1, 6)) {
                    buildString {
                        repeat(random.nextInt(0, 40)) {
                            append(listOf('あ', '漢', ' ', 'a' + random.nextInt(26)).random(random))
                        }
                    }
                        .trimEnd()
                }
            val columns = random.nextInt(3, 30)
            // Tall enough that no line scrolls off.
            val s = snapshot(columns, ROWS, lines.joinToString("\r\n"))
            val text = TerminalSelection(Cell(0, 0), Cell(columns - 1, ROWS - 1)).text(s)
            assertEquals("seed $seed", lines.joinToString("\n").trimEnd('\n'), text.trimEnd('\n'))
        }
    }

    @Test
    fun theCellLeftBlankBeforeAWrappedWideCharacterIsNotCopied() {
        // "あ" does not fit in the last column, so autowrap leaves it blank. Copying used to
        // include that cell as a space.
        val s = snapshot(10, 2, "aaaaaaaaaあb")
        assertEquals("aaaaaaaaaあb", TerminalSelection(Cell(0, 0), Cell(2, 1)).text(s))
        // A selection that starts on that cell.
        assertEquals("あ", TerminalSelection(Cell(9, 0), Cell(1, 1)).text(s))
        // A space that the host wrote there is text.
        assertEquals(
            "aaaaaaaaa あ",
            TerminalSelection(Cell(0, 0), Cell(1, 1)).text(snapshot(10, 2, "aaaaaaaaa あ")),
        )
        // A cell erased there before a narrow character is a space, as in the middle of a line.
        assertEquals(
            "aaaaaaaaa b",
            TerminalSelection(Cell(0, 0), Cell(0, 1))
                .text(snapshot(10, 2, "aaaaaaaaaxb\u001b[1;10H\u001b[K")),
        )
    }

    @Test
    fun wideCharactersAreIncludedWhole() {
        val s = snapshot(10, 1, "aあいb")
        // From the right half of あ to the left half of い.
        assertEquals("あい", TerminalSelection(Cell(2, 0), Cell(3, 0)).text(s))
    }

    @Test
    fun wordIsTheRunOfNonBlankCharacters() {
        val s = snapshot(20, 1, "ls -la /tmp/日本 x")
        assertEquals("/tmp/日本", TerminalSelection.word(s, Cell(9, 0)).text(s))
        assertEquals("-la", TerminalSelection.word(s, Cell(3, 0)).text(s))
        assertEquals("", TerminalSelection.word(s, Cell(2, 0)).text(s))
    }

    private companion object {
        const val ROWS = 200
    }
}
