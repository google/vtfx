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
import com.google.vtfx.core.TerminalSnapshot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSelectionTest {
    private fun snapshot(columns: Int, rows: Int, output: String) =
        Terminal(columns, rows).apply { write(output.toByteArray()) }.snapshot()

    /** The selected text of what [snapshot] shows. */
    private fun TerminalSelection.text(snapshot: TerminalSnapshot): String =
        text(List(snapshot.rows) { snapshot.line(it) }, snapshot.firstLine)

    @Test
    fun textAcrossLinesDropsTrailingBlanks() {
        val s = snapshot(10, 3, "first  \r\nsecond\r\nthird")
        assertEquals(
            "rst\nsecond\nth",
            TerminalSelection.onDisplay(s, Cell(2, 0), Cell(1, 2)).text(s),
        )
    }

    @Test
    fun selectionCanBeBackwards() {
        val s = snapshot(10, 2, "abc\r\ndef")
        assertEquals("bc\nde", TerminalSelection.onDisplay(s, Cell(1, 1), Cell(1, 0)).text(s))
    }

    @Test
    fun wrappedLinesAreJoined() {
        val s = snapshot(5, 2, "abcdefgh")
        assertEquals("abcdefgh", TerminalSelection.onDisplay(s, Cell(0, 0), Cell(4, 1)).text(s))
    }

    @Test
    fun cellsBeyondTheLinesAreNotRead() {
        // A selection made on a wider display, for example before a resize.
        val s = snapshot(5, 2, "abc\r\ndef")
        assertEquals("", TerminalSelection.onDisplay(s, Cell(7, 0), Cell(9, 0)).text(s))
        assertEquals("\nde", TerminalSelection.onDisplay(s, Cell(7, 0), Cell(1, 1)).text(s))
        assertEquals("abc\ndef", TerminalSelection.onDisplay(s, Cell(0, 0), Cell(9, 1)).text(s))
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
            val text =
                TerminalSelection.onDisplay(s, Cell(0, 0), Cell(columns - 1, ROWS - 1)).text(s)
            assertEquals("seed $seed", lines.joinToString("\n").trimEnd('\n'), text.trimEnd('\n'))
        }
    }

    @Test
    fun theCellLeftBlankBeforeAWrappedWideCharacterIsNotCopied() {
        // "あ" does not fit in the last column, so autowrap leaves it blank. Copying used to
        // include that cell as a space.
        val s = snapshot(10, 2, "aaaaaaaaaあb")
        assertEquals("aaaaaaaaaあb", TerminalSelection.onDisplay(s, Cell(0, 0), Cell(2, 1)).text(s))
        // A selection that starts on that cell.
        assertEquals("あ", TerminalSelection.onDisplay(s, Cell(9, 0), Cell(1, 1)).text(s))
        // A space that the host wrote there is text.
        val space = snapshot(10, 2, "aaaaaaaaa あ")
        assertEquals(
            "aaaaaaaaa あ",
            TerminalSelection.onDisplay(space, Cell(0, 0), Cell(1, 1)).text(space),
        )
        // A cell erased there before a narrow character is a space, as in the middle of a line.
        val erased = snapshot(10, 2, "aaaaaaaaaxb\u001b[1;10H\u001b[K")
        assertEquals(
            "aaaaaaaaa b",
            TerminalSelection.onDisplay(erased, Cell(0, 0), Cell(0, 1)).text(erased),
        )
    }

    @Test
    fun wideCharactersAreIncludedWhole() {
        val s = snapshot(10, 1, "aあいb")
        // From the right half of あ to the left half of い.
        assertEquals("あい", TerminalSelection.onDisplay(s, Cell(2, 0), Cell(3, 0)).text(s))
    }

    @Test
    fun wordIsTheRunOfNonBlankCharacters() {
        val s = snapshot(20, 1, "ls -la /tmp/日本 x")
        assertEquals("/tmp/日本", TerminalSelection.word(s, Cell(9, 0)).text(s))
        assertEquals("-la", TerminalSelection.word(s, Cell(3, 0)).text(s))
        assertEquals("", TerminalSelection.word(s, Cell(2, 0)).text(s))
    }

    @Test
    fun aBlankOrConcealedWideCharacterEndsAWord() {
        // An ideographic space (U+3000) at 0 and 1, "foo" at 2 to 4, concealed "秘密" at 6 to 9.
        val s = snapshot(20, 1, "\u3000foo \u001b[8m秘密\u001b[28mbar")
        assertEquals(
            TerminalSelection.onDisplay(s, Cell(2, 0), Cell(4, 0), SelectionUnit.Word),
            TerminalSelection.word(s, Cell(3, 0)),
        )
        assertEquals(
            TerminalSelection.onDisplay(s, Cell(10, 0), Cell(12, 0), SelectionUnit.Word),
            TerminalSelection.word(s, Cell(11, 0)),
        )
        // Either half of a blank wide character selects both of its halves alone.
        for (column in 0..1) {
            assertEquals(
                TerminalSelection.onDisplay(s, Cell(0, 0), Cell(1, 0), SelectionUnit.Word),
                TerminalSelection.word(s, Cell(column, 0)),
            )
        }
        assertEquals(
            TerminalSelection.onDisplay(s, Cell(8, 0), Cell(9, 0), SelectionUnit.Word),
            TerminalSelection.word(s, Cell(9, 0)),
        )
    }

    @Test
    fun withEndAt_start_movesTheStartAndKeepsTheEnd() {
        val s = snapshot(10, 2, "abc def\r\nghi")
        val selection = TerminalSelection.word(s, Cell(5, 0))

        val moved = selection.withEndAt(SelectionEnd.Start, s, Cell(1, 0))

        assertEquals(TerminalSelection.onDisplay(s, Cell(6, 0), Cell(1, 0)), moved)
    }

    @Test
    fun withEndAt_end_movesTheEndToAnotherLine() {
        val s = snapshot(10, 2, "abc def\r\nghi")
        val selection = TerminalSelection.word(s, Cell(1, 0))

        val moved = selection.withEndAt(SelectionEnd.End, s, Cell(1, 1))

        assertEquals(TerminalSelection.onDisplay(s, Cell(0, 0), Cell(1, 1)), moved)
    }

    @Test
    fun withEndAt_startPastTheEnd_stopsAtTheEnd() {
        val s = snapshot(10, 2, "abc def\r\nghi")
        val selection = TerminalSelection.word(s, Cell(1, 0))

        val moved = selection.withEndAt(SelectionEnd.Start, s, Cell(2, 1))

        assertEquals(TerminalSelection.onDisplay(s, Cell(2, 0), Cell(2, 0)), moved)
    }

    @Test
    fun withEndAt_endBeforeTheStart_stopsAtTheStart() {
        val s = snapshot(10, 2, "abc def\r\nghi")
        val selection = TerminalSelection.word(s, Cell(5, 0))

        val moved = selection.withEndAt(SelectionEnd.End, s, Cell(0, 0))

        assertEquals(TerminalSelection.onDisplay(s, Cell(4, 0), Cell(4, 0)), moved)
    }

    @Test
    fun withEndAt_startOnTheRightHalfOfAWideCharacter_takesItWhole() {
        val s = snapshot(10, 1, "aあb")
        val selection = TerminalSelection.onDisplay(s, Cell(3, 0), Cell(3, 0))

        val moved = selection.withEndAt(SelectionEnd.Start, s, Cell(2, 0))

        assertEquals(TerminalSelection.onDisplay(s, Cell(3, 0), Cell(1, 0)), moved)
    }

    @Test
    fun withEndAt_endOnTheLeftHalfOfAWideCharacter_takesItWhole() {
        val s = snapshot(10, 1, "aあb")
        val selection = TerminalSelection.onDisplay(s, Cell(0, 0), Cell(0, 0))

        val moved = selection.withEndAt(SelectionEnd.End, s, Cell(1, 0))

        assertEquals(TerminalSelection.onDisplay(s, Cell(0, 0), Cell(2, 0)), moved)
    }

    private companion object {
        const val ROWS = 200
    }
}
