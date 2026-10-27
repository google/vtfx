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

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TerminalTest {
    private val events = mutableListOf<TerminalEvent>()

    private fun terminal(columns: Int, rows: Int, limits: TerminalLimits = TerminalLimits()) =
        Terminal(columns, rows, limits) { events += it }

    private fun Terminal.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun Terminal.lines(): List<String> {
        val snapshot = snapshot()
        return List(snapshot.rows) { snapshot.line(it).text() }
    }

    @Test
    fun overwritingHalfOfAWideCharacterErasesTheOtherHalf() {
        val t = terminal(6, 1)
        t.write("あいう\u001b[2GX")
        assertEquals(" Xいう", t.lines()[0])
        t.write("\u001b[4GY")
        assertEquals(" X Yう", t.lines()[0])
    }

    @Test
    fun cellsOutsideTheLineAreAProgrammingErrorWithOrWithoutLinks() {
        val t = terminal(4, 2)
        t.write("a\r\n\u001b]8;;https://a\u0007b\u001b]8;;\u0007")
        for (row in 0..1) {
            val line = t.snapshot().line(row)
            for (column in listOf(-1, 4)) {
                assertFailsWith<IndexOutOfBoundsException> { line.textAt(column) }
                assertFailsWith<IndexOutOfBoundsException> { line.widthAt(column) }
                assertFailsWith<IndexOutOfBoundsException> { line.styleAt(column) }
                assertFailsWith<IndexOutOfBoundsException> { line.hyperlinkAt(column) }
            }
        }
        assertFailsWith<IndexOutOfBoundsException> { t.snapshot().line(2) }
    }

    @Test
    fun aRangeOutsideTheBytesIsAProgrammingError() {
        val t = terminal(4, 1)
        val bytes = "abcd".toByteArray()
        // The last two would overflow as offset + length.
        for ((offset, length) in
            listOf(-1 to 1, 0 to -1, 2 to 3, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException>("$offset, $length") {
                t.write(bytes, offset, length)
            }
        }
        t.write(bytes, 1, 3)
        assertEquals("bcd ", t.lines()[0])
    }

    @Test
    fun wideCharacterReportsWidths() {
        val t = terminal(4, 1)
        t.write("aあ")
        val line = t.snapshot().line(0)
        assertEquals(listOf(1, 2, 0, 1), List(4) { line.widthAt(it) })
        assertEquals("あ", line.textAt(1))
        assertNull(line.textAt(2))
    }

    @Test
    fun combiningCharactersJoinThePreviousCell() {
        val t = terminal(5, 1)
        t.write("éx")
        val line = t.snapshot().line(0)
        assertEquals("é", line.textAt(0))
        assertEquals("x", line.textAt(1))
        assertEquals(2, t.cursor.column)
    }

    @Test
    fun combiningCharacterAfterPendingWrapJoinsTheLastCell() {
        val t = terminal(2, 2)
        t.write("ab́")
        assertEquals("b́", t.snapshot().line(0).textAt(1))
    }

    @Test
    fun combiningCharactersAreBounded() {
        val t = terminal(2, 1)
        t.write("e" + "́".repeat(1000))
        assertEquals(Line.MAX_CLUSTER_LENGTH, t.snapshot().line(0).textAt(0)!!.length)
    }

    @Test
    fun supplementaryCombiningCharactersAreBounded() {
        // Found by fuzzing: a cluster of 31 UTF-16 units took a tag character, which takes two.
        val t = terminal(2, 1)
        t.write("e" + "\uDB40\uDC41".repeat(20))
        assertEquals(1 + 15 * 2, t.snapshot().line(0).textAt(0)!!.length)
    }

    @Test
    fun aWideCharacterOnAScreenOfOneColumnIsDroppedWithoutWrapping() {
        // Printed, repeated, and repeated in insert mode, each after a pending wrap.
        for (output in listOf("bあ", "bあ\u001b[3b", "b\u001b[4hあ\u001b[3b")) {
            val t = terminal(1, 2)
            t.write("a\r\n")
            t.write(output)
            assertEquals(0, t.scrollbackSize, output)
            assertEquals(listOf("a", "b"), t.lines(), output)
        }
    }

    @Test
    fun wrappedLinesAreMarked() {
        val t = terminal(3, 2)
        t.write("abcd")
        assertTrue(t.snapshot().line(0).isWrapped)
        assertFalse(t.snapshot().line(1).isWrapped)
    }

    @Test
    fun deleteCharactersSplittingAWideCharacterErasesIt() {
        val t = terminal(6, 1)
        t.write("aあb\u001b[2G\u001b[P")
        assertEquals("a b   ", t.lines()[0])
    }

    @Test
    fun deleteCharactersKeepsAWideCharacterAtTheRightEdge() {
        val t = terminal(5, 1)
        t.write("aあい\r\u001b[P")
        assertEquals(listOf("あ", null, "い", null, null), t.cellTexts(0))
        assertEquals(listOf(2, 0, 2, 0, 1), t.widths(0))
    }

    @Test
    fun deleteCharactersKeepsTheShiftedText() {
        val t = terminal(5, 1)
        t.write("xyあb\r\u001b[2P")
        assertEquals(listOf("あ", null, "b", null, null), t.cellTexts(0))
    }

    @Test
    fun insertBlanksPushingAWideCharacterOffTheEdgeDropsIt() {
        val t = terminal(4, 1)
        t.write("abあ\u001b[1G\u001b[@")
        assertEquals(" ab ", t.lines()[0])
    }

    private fun Terminal.widths(row: Int): List<Int> {
        val line = snapshot().line(row)
        return List(columns) { line.widthAt(it) }
    }

    private fun Terminal.cellTexts(row: Int): List<String?> {
        val line = snapshot().line(row)
        return List(columns) { line.textAt(it) }
    }

    @Test
    fun insertBlanksAtAWideCharacterShiftsItWhole() {
        for (count in 1..2) {
            val t = terminal(6, 1)
            t.write("あい\r\u001b[${count}@")
            assertEquals(
                List(count) { null } + listOf("あ", null, "い", null),
                t.cellTexts(0).take(count + 4),
            )
            assertEquals(List(count) { 1 } + listOf(2, 0, 2, 0), t.widths(0).take(count + 4))
        }
    }

    @Test
    fun insertBlanksInTheRightHalfOfAWideCharacterErasesItWhole() {
        for (count in 1..2) {
            val t = terminal(5, 1)
            t.write("あb\u001b[2G\u001b[${count}@")
            assertEquals(List(count + 2) { null } + "b", t.cellTexts(0).take(count + 3))
            assertEquals(List(5) { 1 }, t.widths(0))
        }
    }

    @Test
    fun insertModeBeforeAWideCharacterShiftsItWhole() {
        val t = terminal(6, 1)
        t.write("あい\r\u001b[4hx")
        assertEquals(listOf("x", "あ", null, "い", null, null), t.cellTexts(0))
        assertEquals(listOf(1, 2, 0, 2, 0, 1), t.widths(0))
        t.write("\r\u001b[4hう")
        assertEquals(listOf("う", null, "x", "あ", null, null), t.cellTexts(0))
        assertEquals(listOf(2, 0, 1, 2, 0, 1), t.widths(0))
    }

    @Test
    fun insertModeInTheRightHalfOfAWideCharacterErasesIt() {
        val t = terminal(5, 1)
        t.write("あb\u001b[2G\u001b[4hx")
        assertEquals(listOf(null, "x", null, "b", null), t.cellTexts(0))
        assertEquals(List(5) { 1 }, t.widths(0))
    }

    /**
     * Writes "あ" at columns 2 and 3 with a hyperlink, a curly underline, an underline color, and a
     * red background, then runs [operation] with a blue background, and erases the last column with
     * it for comparison.
     */
    private fun splitAWideCharacter(operation: String): LineSnapshot {
        val t = terminal(8, 1)
        t.write(
            "\u001b[3G\u001b]8;;https://example.com\u001b\\" +
                "\u001b[4:3;58;5;2;41mあ\u001b[0m\u001b]8;;\u001b\\"
        )
        t.write("\u001b[44m$operation\u001b[8G\u001b[X\u001b[0m")
        return t.snapshot().line(0)
    }

    /** Asserts that [column] of [line] is erased, as the last column, which ECH erased. */
    private fun assertErasedLikeTheLastColumn(line: LineSnapshot, column: Int, message: String) {
        assertNull(line.textAt(column), message)
        assertEquals(1, line.widthAt(column), message)
        assertNull(line.hyperlinkAt(column), message)
        assertEquals(TerminalColor.Default, line.underlineColorAt(column), message)
        assertEquals(line.styleAt(7), line.styleAt(column), message)
    }

    @Test
    fun theOtherHalfOfASplitWideCharacterIsErasedWithTheBackgroundOfTheOperation() {
        // Printing over either half, erasing either half, and deleting cells up to the left half,
        // with the column where the other half is left.
        val cases =
            listOf(
                "\u001b[3Gx" to 3,
                "\u001b[4Gx" to 2,
                "\u001b[3G\u001b[X" to 3,
                "\u001b[4G\u001b[X" to 2,
                "\u001b[4G\u001b[K" to 2,
                "\u001b[3G\u001b[1K" to 3,
                "\u001b[2G\u001b[2P" to 1,
            )
        for ((operation, otherHalf) in cases) {
            assertErasedLikeTheLastColumn(splitAWideCharacter(operation), otherHalf, operation)
        }
    }

    @Test
    fun insertingCellsInTheRightHalfOfAWideCharacterErasesBothHalvesWithTheBackground() {
        val line = splitAWideCharacter("\u001b[4G\u001b[2@")
        // Both halves, and the cells inserted after the left half.
        for (column in 2..5) assertErasedLikeTheLastColumn(line, column, "column $column")
    }

    @Test
    fun linesScrolledOffTheTopGoToTheScrollback() {
        val t = terminal(3, 2)
        t.write("1\r\n2\r\n3\r\n4")
        assertEquals(2, t.scrollbackSize)
        assertEquals("1  ", t.scrollbackLine(0).text())
        assertEquals("2  ", t.scrollbackLine(1).text())
    }

    @Test
    fun theScrollbackKeepsTheBackgroundOfCellsErasedBeforeTheEndOfALine() {
        val t = terminal(10, 1)
        // ECH with a blue background, and EL with red after text, leaving the last cells default.
        t.write("\u001b[44m\u001b[4X\u001b[0m\r\n")
        t.write("ab\u001b[41m\u001b[K\u001b[0m\u001b[8G\u001b[K\r\n")
        val erased = t.scrollbackLine(0)
        assertEquals(
            List(4) { TerminalColor.indexed(4) },
            List(4) { erased.styleAt(it).background },
        )
        assertEquals(TerminalColor.Default, erased.styleAt(4).background)
        val afterText = t.scrollbackLine(1)
        assertEquals(
            List(5) { TerminalColor.indexed(1) } + List(3) { TerminalColor.Default },
            List(8) { afterText.styleAt(it + 2).background },
        )
    }

    private fun Terminal.lineChangesOf(text: String): Long {
        val before = lineChanges
        write(text)
        return lineChanges - before
    }

    @Test
    fun aLineFeedAtTheBottomChangesTheLine() {
        val t = terminal(4, 2)
        t.write("\u001b[2H")
        assertEquals(1L, t.lineChangesOf("ls\r\n"))
        assertEquals(1, t.cursor.row)
    }

    @Test
    fun autowrapDoesNotChangeTheLine() {
        val t = terminal(4, 2)
        t.write("\u001b[2H")
        assertEquals(0L, t.lineChangesOf("abcdefghij"))
        // Moving between the rows that autowrap joined stays on the line.
        assertEquals(0L, t.lineChangesOf("\u001b[A\u001b[B"))
        assertEquals(1L, t.lineChangesOf("\r\n"))
    }

    @Test
    fun movingToAnotherRowAndBackChangesTheLine() {
        val t = terminal(4, 3)
        assertEquals(2L, t.lineChangesOf("\u001b[2Hx\u001b[1H"))
        assertEquals(0L, t.lineChangesOf("\u001b[1;3H"))
    }

    @Test
    fun aResetChangesTheLineOnTheSameRow() {
        val t = terminal(4, 2)
        t.write("ls")
        assertEquals(1L, t.lineChangesOf("\u001bc"))
        assertEquals(CursorState(0, 0), t.cursor)
    }

    @Test
    fun switchingTheScreenChangesTheLine() {
        val t = terminal(4, 2)
        t.write("ls")
        // Without saving the cursor, it stays on its row; each switch shows another line.
        assertEquals(2L, t.lineChangesOf("\u001b[?47h\u001b[?47l"))
        assertEquals(2L, t.lineChangesOf("\u001b[?1049h\u001b[?1049l"))
        // Setting the mode that is already set does not switch.
        assertEquals(0L, t.lineChangesOf("\u001b[?47l"))
    }

    @Test
    fun resizingDoesNotChangeTheLine() {
        val t = terminal(3, 3)
        t.write("\u001b[?1049h\u001b[3H")
        val before = t.lineChanges
        t.resize(3, 1)
        assertEquals(CursorState(0, 0), t.cursor)
        assertEquals(before, t.lineChanges)
    }

    @Test
    fun scrollingTheCursorsRowWithSuSdIlOrDlChangesTheLine() {
        for (scroll in listOf("\u001b[S", "\u001b[T", "\u001b[L", "\u001b[M")) {
            val t = terminal(10, 4)
            t.write("line1\r\nline2")
            assertEquals(1L, t.lineChangesOf(scroll), scroll)
            assertEquals(1, t.cursor.row, scroll)
        }
    }

    @Test
    fun scrollingARegionWithoutTheCursorDoesNotChangeTheLine() {
        val t = terminal(10, 4)
        // The region is rows 3 and 4, and DECSTBM puts the cursor on the first row.
        t.write("\u001b[3;4r")
        for (scroll in listOf("\u001b[S", "\u001b[T", "\u001b[L", "\u001b[M")) {
            assertEquals(0L, t.lineChangesOf(scroll), scroll)
        }
    }

    @Test
    fun aReverseIndexChangesTheLine() {
        val t = terminal(4, 2)
        assertEquals(1L, t.lineChangesOf("\u001bM"))
        assertEquals(0, t.cursor.row)
    }

    @Test
    fun scrollbackPushesCountLinesDroppedForTheCapacity() {
        val t = terminal(3, 2, TerminalLimits(scrollbackLines = 2))
        t.write("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, t.scrollbackSize)
        assertEquals(3L, t.scrollbackPushes)
        t.write("\u001b[3J")
        assertEquals(0, t.scrollbackSize)
        assertEquals(3L, t.scrollbackPushes)
    }

    @Test
    fun snapshotCanShowTheScrollback() {
        val t = terminal(3, 2)
        t.write("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("3  ", "4  "), t.lines())
        val back = t.snapshot(scrollOffset = 1)
        assertEquals(1, back.scrollOffset)
        assertEquals(listOf("2  ", "3  "), List(2) { back.line(it).text() })
        val top = t.snapshot(scrollOffset = 99)
        assertEquals(2, top.scrollOffset)
        assertEquals(listOf("1  ", "2  "), List(2) { top.line(it).text() })
    }

    @Test
    fun alternateScreenHasNoScrollbackToShow() {
        val t = terminal(3, 1)
        t.write("1\r\n2\u001b[?1049hx")
        assertEquals(0, t.snapshot(scrollOffset = 1).scrollOffset)
        assertEquals(" x ", t.snapshot(scrollOffset = 1).line(0).text())
        assertEquals(listOf(" x "), t.lines(maxLines = 99).map { it.text() })
    }

    @Test
    fun linesAreTheLastOfTheScrollbackAndTheScreen() {
        val t = terminal(3, 2)
        t.write("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("1  ", "2  ", "3  ", "4  "), t.lines(maxLines = 99).map { it.text() })
        assertEquals(listOf("2  ", "3  ", "4  "), t.lines(maxLines = 3).map { it.text() })
        assertEquals(emptyList(), t.lines(maxLines = 0))
        assertFailsWith<IllegalArgumentException> { t.lines(maxLines = -1) }
        // At the current width, as the snapshot shows them.
        t.resize(2, 2)
        assertEquals(listOf(2), t.lines(maxLines = 99).map { it.columns }.distinct())
    }

    @Test
    fun scrollbackLinesAreShownAtTheCurrentWidth() {
        val t = terminal(4, 1)
        t.write("abcd\r\n")
        t.resize(2, 1)
        // Reflowed into two lines.
        assertEquals("cd", t.snapshot(scrollOffset = 1).line(0).text())
        assertEquals("ab", t.snapshot(scrollOffset = 2).line(0).text())
    }

    @Test
    fun scrollbackIsBounded() {
        val t = terminal(3, 1, TerminalLimits(scrollbackLines = 2))
        t.write("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("2  ", "3  "), List(t.scrollbackSize) { t.scrollbackLine(it).text() })
    }

    @Test
    fun scrollbackOfCombiningCharactersStaysWithinItsMemoryBudget() {
        // Every cell with as many combining characters as it keeps: the oldest lines go before
        // the line limit, so that the memory stays within 16 MiB for 10,000 lines of 120 columns
        // (docs/decisions/0089).
        val t = terminal(120, 24)
        val cell = "e" + "\u0301".repeat(Line.MAX_CLUSTER_LENGTH - 1)
        for (i in 0 until 10_024) t.write(cell.repeat(119) + "${i % 10}\r\n")
        assertTrue(t.estimatedScrollbackBytes() <= 16L * 1048576, "${t.estimatedScrollbackBytes()}")
        assertTrue(t.scrollbackSize < 10_000)
        // The newest lines are kept: the screen shows the last 23 lines and the empty one.
        assertEquals("${(10_024 - 24) % 10}", t.scrollbackLine(t.scrollbackSize - 1).textAt(119))
        assertEquals(cell, t.scrollbackLine(t.scrollbackSize - 1).textAt(0))
    }

    @Test
    fun scrollbackOfColorsInEveryCellKeepsAllItsLines() {
        // The worst case of docs/decisions/0089 before clusters, which the budget leaves whole.
        val t = terminal(120, 24)
        val line = buildString {
            for (column in 0 until 120) append("\u001b[38;2;$column;1;2mx")
            append("\r\n")
        }
        for (i in 0 until 10_024) t.write(line)
        assertEquals(10_000, t.scrollbackSize)
    }

    @Test
    fun scrollbackOfAClusterInEveryCellKeepsAllItsLines() {
        // As in Indic scripts: a base character and one combining mark in every cell.
        val t = terminal(120, 24)
        val line = "e\u0301".repeat(120) + "\r\n"
        for (i in 0 until 10_024) t.write(line)
        assertEquals(10_000, t.scrollbackSize)
    }

    @Test
    fun aLineOverTheMemoryBudgetIsStillKept() {
        val t = terminal(4, 1, TerminalLimits(scrollbackLines = 1))
        val cell = "e" + "\u0301".repeat(Line.MAX_CLUSTER_LENGTH - 1)
        t.write(cell.repeat(4) + "\r\n")
        assertEquals(1, t.scrollbackSize)
        assertEquals(cell, t.scrollbackLine(0).textAt(3))
    }

    @Test
    fun alternateScreenDoesNotFillTheScrollback() {
        val t = terminal(3, 1)
        t.write("\u001b[?1049h1\r\n2\r\n3")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun scrollingRegionDoesNotFillTheScrollbackUnlessAtTheTop() {
        val t = terminal(3, 3)
        t.write("\u001b[2;3r\u001b[3;1H\n\n")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun deleteLinesDoesNotFillTheScrollback() {
        val t = terminal(3, 2)
        t.write("a\u001b[H\u001b[M")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun eraseUsesTheCurrentBackground() {
        val t = terminal(3, 1)
        t.write("\u001b[41;1m\u001b[2K")
        val style = t.snapshot().line(0).styleAt(0)
        assertEquals(TerminalColor.indexed(1), style.background)
        assertFalse(style.isBold)
    }

    @Test
    fun sgrSetsAttributesAndColors() {
        val t = terminal(10, 1)
        t.write(
            "\u001b[1;3;4;31;48;5;200mA\u001b[38;2;1;2;3;22mB\u001b[38:2::4:5:6;4:3mC\u001b[0mD"
        )
        val line = t.snapshot().line(0)
        line.styleAt(0).let {
            assertTrue(it.isBold)
            assertTrue(it.isItalic)
            assertEquals(UnderlineStyle.Single, it.underline)
            assertEquals(TerminalColor.indexed(1), it.foreground)
            assertEquals(TerminalColor.indexed(200), it.background)
        }
        line.styleAt(1).let {
            assertFalse(it.isBold)
            assertEquals(TerminalColor.rgb(1, 2, 3), it.foreground)
        }
        line.styleAt(2).let {
            assertEquals(TerminalColor.rgb(4, 5, 6), it.foreground)
            assertEquals(UnderlineStyle.Curly, it.underline)
        }
        assertEquals(CellStyle.Default, line.styleAt(3))
    }

    @Test
    fun sgrSetsTheUnderlineColor() {
        val t = terminal(10, 1)
        // 2 and 3 are color components, not faint and italic.
        t.write(
            "\u001b[4m\u001b[58;2;1;2;3mA\u001b[58;5;3;1mB\u001b[58:2::4:5:6mC" +
                "\u001b[58:5:9mD\u001b[58:2:7:8:9mE\u001b[59mF"
        )
        val line = t.snapshot().line(0)
        val underlined = CellStyle.Default.withUnderline(UnderlineStyle.Single)
        assertEquals(underlined, line.styleAt(0))
        assertEquals(TerminalColor.rgb(1, 2, 3), line.underlineColorAt(0))
        assertEquals(underlined.withFlag(CellStyle.BOLD, true), line.styleAt(1))
        assertEquals(TerminalColor.indexed(3), line.underlineColorAt(1))
        assertEquals(TerminalColor.rgb(4, 5, 6), line.underlineColorAt(2))
        assertEquals(TerminalColor.indexed(9), line.underlineColorAt(3))
        assertEquals(TerminalColor.rgb(7, 8, 9), line.underlineColorAt(4))
        assertEquals(TerminalColor.Default, line.underlineColorAt(5))
        assertEquals(TerminalColor.Default, line.underlineColorAt(6))
        assertFailsWith<IndexOutOfBoundsException> { line.underlineColorAt(10) }
    }

    @Test
    fun underlineColorIsKeptOnlyWhileUnderlined() {
        val t = terminal(10, 1)
        // Wide characters take the color in both halves.
        t.write("\u001b[58;5;1mA\u001b[4mB\u001b[24mC\u001b[4:3m日\u001b[0;4mD")
        val line = t.snapshot().line(0)
        assertEquals(TerminalColor.Default, line.underlineColorAt(0))
        assertEquals(TerminalColor.indexed(1), line.underlineColorAt(1))
        assertEquals(TerminalColor.Default, line.underlineColorAt(2))
        assertEquals(TerminalColor.indexed(1), line.underlineColorAt(3))
        assertEquals(TerminalColor.indexed(1), line.underlineColorAt(4))
        // SGR 0 resets the color too.
        assertEquals(TerminalColor.Default, line.underlineColorAt(5))
    }

    @Test
    fun everyStyleOfUnderlineKeepsTheUnderlineColor() {
        val t = terminal(10, 1)
        // 4:0 is no underline; 4:1 to 4:5 are the styles of UnderlineStyle after None.
        t.write("\u001b[58;5;1m" + (0..5).joinToString("") { "\u001b[4:${it}mx" })
        val line = t.snapshot().line(0)
        assertEquals(TerminalColor.Default, line.underlineColorAt(0))
        for (column in 1..5) {
            assertEquals(UnderlineStyle.entries[column], line.styleAt(column).underline)
            assertEquals(TerminalColor.indexed(1), line.underlineColorAt(column), "column $column")
        }
    }

    @Test
    fun underlineColorFollowsTheCells() {
        val t = terminal(6, 2)
        t.write("\u001b[4;58;5;2mab\u001b[0mcd\r\u001b[2@")
        t.snapshot().line(0).let { line ->
            assertEquals("  abcd", line.text())
            assertEquals(TerminalColor.Default, line.underlineColorAt(0))
            assertEquals(TerminalColor.indexed(2), line.underlineColorAt(2))
            assertEquals(TerminalColor.indexed(2), line.underlineColorAt(3))
        }
        // Erasing, even with the color set, leaves cells without it, as it leaves them without
        // underline.
        t.write("\u001b[4;58;5;3m\u001b[2P\u001b[K")
        t.snapshot().line(0).let { line ->
            assertEquals("      ", line.text())
            for (column in 0 until 6) assertEquals(
                TerminalColor.Default,
                line.underlineColorAt(column),
            )
        }
    }

    @Test
    fun underlineColorIsKeptInTheScrollbackAndOnReflow() {
        val t = terminal(4, 1)
        t.write("\u001b[4;58;2;9;8;7mabcdef\u001b[0m\r\n")
        assertEquals(TerminalColor.rgb(9, 8, 7), t.scrollbackLine(0).underlineColorAt(3))
        t.resize(8, 2)
        val line = t.snapshot().line(0)
        assertEquals("abcdef  ", line.text())
        assertEquals(TerminalColor.rgb(9, 8, 7), line.underlineColorAt(5))
        assertEquals(TerminalColor.Default, line.underlineColorAt(6))
    }

    @Test
    fun underlineColorIsSavedWithTheCursorAndResetBySoftReset() {
        val t = terminal(10, 1)
        // B, after DECRC, overwrites A with the saved color; C comes after DECSTR.
        t.write("\u001b[4;58;5;4m\u001b7\u001b[59mA\u001b8B\u001b[!p\u001b[4mC")
        val line = t.snapshot().line(0)
        assertEquals("BC", line.text().trim())
        assertEquals(TerminalColor.indexed(4), line.underlineColorAt(0))
        assertEquals(TerminalColor.Default, line.underlineColorAt(1))
    }

    @Test
    fun underlineColorIsPartOfTheContent() {
        val t = terminal(4, 2)
        t.write("\u001b[4;58;5;1mab\r\n\u001b[58;5;2mab")
        val snapshot = t.snapshot()
        assertEquals(snapshot.line(0).styleAt(0), snapshot.line(1).styleAt(0))
        assertFalse(snapshot.line(0).contentEquals(snapshot.line(1)))
        t.write("\u001b[H\u001b[58;5;2mab")
        val after = t.snapshot()
        assertTrue(after.line(0).contentEquals(after.line(1)))
        assertEquals(after.line(0).contentHashCode(), after.line(1).contentHashCode())
    }

    @Test
    fun sgrIgnoresMalformedExtendedColors() {
        val t = terminal(10, 1)
        t.write("\u001b[38;5;999mA\u001b[38;2;1mB")
        val line = t.snapshot().line(0)
        assertTrue(line.styleAt(0).foreground.isDefault)
        assertTrue(line.styleAt(1).foreground.isDefault)
    }

    @Test
    fun tabStopsCanBeSetAndCleared() {
        val t = terminal(20, 1)
        t.write("\u001b[3g\u001b[4G\u001bH\u001b[1G\tX")
        assertEquals(4, t.cursor.column)
        t.write("\u001b[1G\u001b[I\u001b[2Z")
        assertEquals(0, t.cursor.column)
    }

    @Test
    fun repeatsTheLastCharacter() {
        val t = terminal(6, 1)
        t.write("ab\u001b[3b")
        assertEquals("abbbb ", t.lines()[0])
    }

    @Test
    fun repeatIsBoundedByTheScreenSize() {
        val t = terminal(3, 2, TerminalLimits(scrollbackLines = 100))
        t.write("a\u001b[65535b")
        // 1 + 6 characters fill the screen and scroll once.
        assertEquals(1, t.scrollbackSize)
    }

    @Test
    fun repeatInInsertModeIsTheSameAsPrintingEachCharacter() {
        val random = Random(1)
        repeat(2000) {
            val columns = 1 + random.nextInt(8)
            val rows = 1 + random.nextInt(3)
            val character = listOf("x", "あ", "\u0301").random(random)
            val count = random.nextInt(2 * columns * rows + 2)
            val setup =
                "a あb".repeat(4) +
                    "\u001b[${1 + random.nextInt(rows)};${1 + random.nextInt(columns)}H" +
                    (if (random.nextBoolean()) "\u001b[?7l" else "") +
                    "\u001b[4h$character"
            val repeated = terminal(columns, rows)
            repeated.write(setup + "\u001b[${count}b")
            val printed = terminal(columns, rows)
            printed.write(setup + character.repeat(maxOf(count, 1).coerceAtMost(columns * rows)))
            val case = "$columns x $rows, ${setup.replace("\u001b", "ESC")}, $count"
            assertEquals(printed.lines(), repeated.lines(), case)
            assertEquals(printed.cursor, repeated.cursor, case)
            repeated.write("Z")
            printed.write("Z")
            assertEquals(printed.lines(), repeated.lines(), "$case, then Z")
        }
    }

    @Test
    fun saveCursorRestoresStyleAndOriginMode() {
        val t = terminal(5, 3)
        t.write("\u001b[1m\u001b7\u001b[0m\u001b8X")
        assertTrue(t.snapshot().line(0).styleAt(0).isBold)
    }

    @Test
    fun reportsQueriesAsEvents() {
        val t = terminal(10, 5)
        t.write("\u001b[c\u001b[>c\u001b[5n\u001b[3;4H\u001b[6n\u001b[?6n\u0007")
        assertEquals(
            listOf(
                TerminalEvent.ReplyRequested(TerminalQuery.PrimaryDeviceAttributes),
                TerminalEvent.ReplyRequested(TerminalQuery.SecondaryDeviceAttributes),
                TerminalEvent.ReplyRequested(TerminalQuery.OperatingStatus),
                TerminalEvent.ReplyRequested(
                    TerminalQuery.CursorPosition(3, 4, isDecPrivate = false)
                ),
                TerminalEvent.ReplyRequested(
                    TerminalQuery.CursorPosition(3, 4, isDecPrivate = true)
                ),
                TerminalEvent.Bell,
            ),
            events,
        )
    }

    @Test
    fun cursorPositionReportIsRelativeToTheMarginInOriginMode() {
        val t = terminal(10, 5)
        t.write("\u001b[2;4r\u001b[?6h\u001b[2;1H\u001b[6n")
        assertEquals(
            TerminalEvent.ReplyRequested(TerminalQuery.CursorPosition(2, 1, false)),
            events.single(),
        )
    }

    @Test
    fun restoredCursorStaysInsideTheMarginsInOriginMode() {
        // A cursor saved inside the old margins is moved inside the new ones, as xterm does, so
        // that its position relative to the top margin is never negative (GateFuzzer found a
        // report of row -1, which the gate rejects).
        val t = terminal(10, 10)
        t.write("\u001b[3;8r\u001b[?6h\u001b[2;5H\u001b7\u001b[6;8r\u001b8\u001b[6n")
        t.write("\u001b[6;1H\u001b7\u001b[3;5r\u001b8\u001b[6n")
        assertEquals<List<TerminalEvent>>(
            listOf(
                TerminalEvent.ReplyRequested(TerminalQuery.CursorPosition(1, 5, false)),
                TerminalEvent.ReplyRequested(TerminalQuery.CursorPosition(3, 1, false)),
            ),
            events,
        )
    }

    @Test
    fun titleChangesAreReported() {
        val t = terminal(10, 1)
        t.write("\u001b]2;hello\u0007\u001b]1;icon\u0007")
        assertEquals(listOf<TerminalEvent>(TerminalEvent.TitleChanged("hello")), events)
    }

    @Test
    fun clipboardRequestsAreReported() {
        val t = terminal(10, 1)
        val hello = java.util.Base64.getEncoder().encodeToString("héllo\n".toByteArray())
        t.write("\u001b]52;c;$hello\u0007\u001b]52;;\u0007\u001b]52;p;?\u001b\\")
        assertEquals(
            listOf(
                TerminalEvent.ClipboardWriteRequested("c", "héllo\n"),
                TerminalEvent.ClipboardWriteRequested("s0", ""),
                TerminalEvent.ClipboardReadRequested("p"),
            ),
            events,
        )
    }

    @Test
    fun malformedClipboardRequestsAreIgnored() {
        val t = terminal(10, 1)
        t.write("\u001b]52;c;not base64!\u0007\u001b]52;x;?\u0007\u001b]52;c\u0007")
        assertEquals(emptyList(), events)
    }

    @Test
    fun modesAreTracked() {
        val t = terminal(10, 1)
        t.write("\u001b[?1h\u001b[?2004h\u001b[?1002h\u001b[?1006h\u001b[?25l\u001b=")
        assertEquals(
            TerminalModes(
                applicationCursorKeys = true,
                applicationKeypad = true,
                cursorVisible = false,
                bracketedPaste = true,
                mouseTracking = MouseTracking.ButtonEvent,
                mouseEncoding = MouseEncoding.Sgr,
            ),
            t.modes,
        )
        t.write("\u001bc")
        assertEquals(TerminalModes(), t.modes)
    }

    @Test
    fun resetClearsTheScreen() {
        val t = terminal(3, 2)
        t.write("ab\u001b[?1049hc\u001bc")
        assertFalse(t.isAlternateScreen)
        assertEquals(listOf("   ", "   "), t.lines())
        assertEquals(CursorState(0, 0), t.cursor)
    }

    @Test
    fun resetDropsAnUnfinishedStringWithoutActingOnIt() {
        // OSC, DCS, APC, and SOS strings, each followed by text and its terminator.
        for (start in
            listOf("\u001b]0;Before", "\u001bP\$qm", "\u001b_Ga=q,i=1;AAAA", "\u001bXx")) {
            val t = terminal(10, 1)
            t.write(start)
            t.reset()
            t.write("Hello\u0007\u001b\\")
            assertEquals("Hello     ", t.lines()[0], start)
        }
        assertEquals(emptyList(), events.filter { it !is TerminalEvent.Bell })
    }

    @Test
    fun resetDropsAnUnfinishedSequence() {
        val t = terminal(10, 1)
        // A control sequence.
        t.write("\u001b[3")
        t.reset()
        t.write("1mA")
        assertEquals("1mA       ", t.lines()[0])
        assertEquals(CellStyle.Default, t.snapshot().line(0).styleAt(2))
        // The first byte of a two-byte UTF-8 character.
        t.write(byteArrayOf(0xC3.toByte()))
        t.reset()
        t.write(byteArrayOf(0xA9.toByte()) + "B".toByteArray())
        assertEquals("\uFFFDB        ", t.lines()[0])
    }

    @Test
    fun narrowingRewrapsLinesAndKeepsTheCursorOnItsCharacter() {
        val t = terminal(6, 3)
        t.write("abcdef\r\n$ ls")
        t.resize(4, 3)
        assertEquals(listOf("abcd", "ef  ", "$ ls"), t.lines())
        assertTrue(t.snapshot().line(0).isWrapped)
        assertEquals(CursorState(3, 2), t.cursor)
    }

    @Test
    fun wideningJoinsWrappedLines() {
        val t = terminal(4, 3)
        t.write("abcdef\r\nx")
        assertEquals(listOf("abcd", "ef  ", "x   "), t.lines())
        t.resize(8, 3)
        assertEquals(listOf("abcdef  ", "x       ", "        "), t.lines())
        assertEquals(CursorState(1, 1), t.cursor)
    }

    @Test
    fun wideningKeepsTheInsertionPointAfterAFullRow() {
        val t = terminal(6, 3)
        t.write("abcdef")
        t.resize(8, 3)
        t.write("g")
        assertEquals("abcdefg ", t.lines()[0])
    }

    @Test
    fun wideningKeepsTheInsertionPointAfterAWideCharacterInTheLastColumns() {
        val t = terminal(6, 3)
        t.write("abcdあ")
        t.resize(8, 3)
        t.write("x")
        assertEquals("abcdあx ", t.lines()[0])
    }

    @Test
    fun narrowingKeepsTheInsertionPointAfterAFullRow() {
        val t = terminal(6, 3)
        t.write("abcdef")
        t.resize(4, 3)
        t.write("g")
        assertEquals(listOf("abcd", "efg ", "    "), t.lines())
    }

    @Test
    fun narrowingToTheContentWidthWrapsTheNextCharacter() {
        val t = terminal(6, 3)
        t.write("abc")
        t.resize(3, 3)
        t.write("d")
        assertEquals(listOf("abc", "d  ", "   "), t.lines())
        assertTrue(t.snapshot().line(0).isWrapped)
    }

    @Test
    fun changingOnlyTheHeightKeepsThePendingWrap() {
        val t = terminal(3, 2)
        t.write("abc")
        t.resize(3, 3)
        t.write("d")
        assertEquals(listOf("abc", "d  ", "   "), t.lines())
    }

    @Test
    fun theSavedMainCursorKeepsThePendingWrap() {
        val t = terminal(6, 2)
        t.write("abcdef\u001b[?1049h")
        t.resize(8, 2)
        t.write("\u001b[?1049lg")
        assertEquals("abcdefg ", t.lines()[0])
    }

    @Test
    fun aWideCharacterThatDoesNotFitMovesToTheNextRow() {
        val t = terminal(4, 3)
        t.write("aあb")
        t.resize(2, 3)
        assertEquals(listOf("a ", "あ", "b "), t.lines())
        assertTrue(t.snapshot().line(0).isWrapped)
    }

    @Test
    fun changingOnlyTheHeightKeepsTheCursorOnTheSecondHalfOfAWideCharacter() {
        val t = terminal(6, 3)
        t.write("ab界cd\u001b[1;4H")
        t.resize(6, 4)
        assertEquals(CursorState(3, 0), t.cursor)
        t.write("X")
        assertEquals("ab Xcd", t.lines()[0])
    }

    @Test
    fun narrowingKeepsTheCursorOnTheSecondHalfOfAWideCharacter() {
        val t = terminal(6, 3)
        t.write("ab界cd\u001b[1;4H")
        t.resize(3, 3)
        assertEquals(listOf("ab ", "界c", "d  "), t.lines())
        assertEquals(CursorState(1, 1), t.cursor)
        t.write("X")
        assertEquals(listOf("ab ", " Xc", "d  "), t.lines())
    }

    @Test
    fun wideningKeepsTheCursorOnTheSecondHalfOfAWideCharacter() {
        val t = terminal(4, 3)
        t.write("ab界cd\u001b[2;2H")
        assertEquals(listOf("ab界", "cd  ", "    "), t.lines())
        t.write("\u001b[1;4H")
        t.resize(8, 3)
        assertEquals(CursorState(3, 0), t.cursor)
        t.write("X")
        assertEquals("ab Xcd  ", t.lines()[0])
    }

    @Test
    fun theSavedMainCursorStaysOnTheSecondHalfOfAWideCharacter() {
        val t = terminal(6, 3)
        t.write("ab界cd\u001b[1;4H\u001b[?1049h")
        t.resize(6, 4)
        t.write("\u001b[?1049lX")
        assertEquals("ab Xcd", t.lines()[0])
    }

    @Test
    fun shrinkingMovesTopLinesToTheScrollbackToKeepTheCursor() {
        val t = terminal(3, 4)
        t.write("1\r\n2\r\n3\r\n4")
        t.resize(3, 2)
        assertEquals(listOf("3  ", "4  "), t.lines())
        assertEquals(listOf("1  ", "2  "), List(t.scrollbackSize) { t.scrollbackLine(it).text() })
        assertEquals(1, t.cursor.row)
        t.resize(3, 4)
        assertEquals(listOf("1  ", "2  ", "3  ", "4  "), t.lines())
        assertEquals(0, t.scrollbackSize)
        assertEquals(3, t.cursor.row)
    }

    @Test
    fun growingKeepsContentAtTheTopWithoutScrollback() {
        val t = terminal(3, 2)
        t.write("a")
        t.resize(3, 4)
        assertEquals(listOf("a  ", "   ", "   ", "   "), t.lines())
        assertEquals(CursorState(1, 0), t.cursor)
    }

    @Test
    fun alternateScreenIsNotReflowedButTheMainScreenIs() {
        val t = terminal(6, 2)
        t.write("abcdef\u001b[?1049hxyz")
        t.resize(3, 2)
        assertTrue(t.isAlternateScreen)
        // "xyz" followed the pending wrap after "abcdef" to the second row.
        assertEquals(listOf("   ", "xyz"), t.lines())
        t.write("\u001b[?1049l")
        assertEquals(listOf("abc", "def"), t.lines())
        // The saved cursor followed the reflow: it was after "f", at the pending wrap.
        assertEquals(1, t.cursor.row)
    }

    @Test
    fun theCellLeftBlankBeforeAWrappedWideCharacterIsNotReflowed() {
        // Autowrap leaves the last cell blank when "あ" does not fit, and that blank used to
        // become a space at another width.
        val t = terminal(20, 3)
        t.write("aaaaaaaaaあbbbbbbbbb")
        t.resize(10, 3)
        assertEquals(listOf("aaaaaaaaa ", "あbbbbbbbb", "b         "), t.lines())
        t.resize(20, 3)
        assertEquals(listOf("aaaaaaaaaあbbbbbbbbb", " ".repeat(20), " ".repeat(20)), t.lines())
        assertEquals(CursorState(19, 0), t.cursor)
        // Written at the narrow width, and in the scrollback.
        val scrolled = terminal(10, 2)
        scrolled.write("aaaaaaaaaあ\r\nx\r\ny")
        scrolled.resize(20, 2)
        assertEquals("aaaaaaaaaあ" + " ".repeat(9), scrolled.scrollbackLine(0).text())
        // A space that the host wrote there is text.
        val written = terminal(10, 2)
        written.write("aaaaaaaaa あ")
        written.resize(20, 2)
        assertEquals("aaaaaaaaa あ" + " ".repeat(8), written.lines()[0])
    }

    @Test
    fun resizingAndResizingBackKeepsTheLines() {
        // Random lines of letters and wide characters, through a few widths and back.
        for (seed in 0 until 300) {
            val random = Random(seed)
            val columns = random.nextInt(4, 30)
            val rows = random.nextInt(2, 10)
            val t = terminal(columns, rows, TerminalLimits(scrollbackLines = 10_000))
            t.write(
                buildString {
                    repeat(random.nextInt(1, 15)) {
                        repeat(random.nextInt(0, 60)) {
                            append(listOf('あ', '漢', 'a' + random.nextInt(26)).random(random))
                        }
                        if (random.nextInt(5) != 0) append("\r\n")
                    }
                }
            )
            val before = t.logicalLines()
            repeat(random.nextInt(1, 4)) { t.resize(random.nextInt(2, 40), random.nextInt(2, 10)) }
            t.resize(columns, rows)
            assertEquals(before, t.logicalLines(), "seed $seed")
        }
    }

    @Test
    fun outputAfterResizingAndResizingBackIsAsWithoutResizing() {
        // The cursor follows the reflow, so the next output lands where it would have.
        for (seed in 0 until 300) {
            val random = Random(seed)
            val columns = random.nextInt(4, 30)
            val rows = random.nextInt(2, 10)
            val output = buildString {
                repeat(random.nextInt(1, 12)) {
                    repeat(random.nextInt(0, 50)) {
                        append(listOf('あ', '漢', 'a' + random.nextInt(26)).random(random))
                    }
                    if (random.nextInt(4) != 0) append("\r\n")
                }
            }
            val resized = terminal(columns, rows, TerminalLimits(scrollbackLines = 10_000))
            resized.write(output)
            repeat(random.nextInt(1, 4)) {
                resized.resize(random.nextInt(2, 40), random.nextInt(2, 10))
            }
            resized.resize(columns, rows)
            resized.write("Z")
            val unresized = terminal(columns, rows, TerminalLimits(scrollbackLines = 10_000))
            unresized.write(output + "Z")
            assertEquals(unresized.lines(), resized.lines(), "seed $seed")
            assertEquals(unresized.cursor, resized.cursor, "seed $seed")
        }
    }

    /**
     * The text of each line of the scrollback and the screen, joining the rows that autowrap
     * joined, without the blank cell left before a wide character that did not fit, and without
     * trailing blanks and blank lines.
     */
    private fun Terminal.logicalLines(): List<String> {
        val snapshot = snapshot()
        val rows =
            List(scrollbackSize) { scrollbackLine(it) } + List(snapshot.rows) { snapshot.line(it) }
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for ((index, row) in rows.withIndex()) {
            val text = row.text()
            val next = rows.getOrNull(index + 1)
            val padding =
                row.isWrapped &&
                    next != null &&
                    row.textAt(row.columns - 1) == null &&
                    next.widthAt(0) == 2
            line.append(if (padding) text.dropLast(1) else text)
            if (!row.isWrapped) {
                lines += line.toString().trimEnd()
                line.clear()
            }
        }
        lines += line.toString().trimEnd()
        return lines.dropLastWhile { it.isEmpty() }
    }

    @Test
    fun theCursorOnTheCellLeftBlankBeforeAWrappedWideCharacterStaysThere() {
        val t = terminal(10, 2)
        t.write("aaaaaaaaaあ\u001b[1;10H")
        t.resize(20, 2)
        assertEquals("aaaaaaaaa あ" + " ".repeat(8), t.lines()[0])
        assertEquals(CursorState(9, 0), t.cursor)
    }

    @Test
    fun aCursorSavedWaitingToWrapDoesNotWaitAfterWidening() {
        val t = terminal(10, 3)
        // The alternate screen, which is not reflowed, and the last column.
        t.write("\u001b[?1049h0123456789\u001b7")
        t.resize(20, 3)
        t.write("\u001b8Z")
        assertEquals(listOf("012345678Z" + " ".repeat(10), " ".repeat(20)), t.lines().take(2))
        // Back at the width it was saved at, it waits to wrap again.
        val u = terminal(10, 3)
        u.write("\u001b[?1049h0123456789\u001b7")
        u.resize(20, 3)
        u.resize(10, 3)
        u.write("\u001b8Y")
        assertEquals(listOf("0123456789", "Y" + " ".repeat(9)), u.lines().take(2))
    }

    @Test
    fun aWideCharacterIsDroppedAtOneColumn() {
        val t = terminal(4, 1)
        t.write("aあ")
        t.resize(1, 1)
        assertEquals("a", t.snapshot(scrollOffset = 1).line(0).text())
    }

    @Test
    fun theCursorOnAWideCharacterDroppedAtOneColumnStaysOnItsLine() {
        val t = terminal(4, 3)
        t.write("ab\r\nあ\r")
        t.resize(1, 3)
        assertEquals(CursorState(0, 2), t.cursor)
        t.write("x")
        assertEquals(listOf("a", "b", "x"), t.lines())
    }

    @Test
    fun theCursorOnAWideCharacterDroppedAtOneColumnWaitsAfterTheTextBeforeIt() {
        val t = terminal(4, 3)
        t.write("aあ\u001b[2G")
        t.resize(1, 3)
        assertEquals(CursorState(0, 0), t.cursor)
        t.write("x")
        assertEquals(listOf("a", "x", " "), t.lines())
    }

    @Test
    fun listenerMustNotCallBack() {
        lateinit var t: Terminal
        t = Terminal(5, 1) { t.write(byteArrayOf()) }
        assertFailsWith<IllegalStateException> { t.write(byteArrayOf(7)) }
    }

    @Test
    fun rejectsInvalidSizes() {
        assertFailsWith<IllegalArgumentException> { Terminal(0, 1) }
        assertFailsWith<IllegalArgumentException> { Terminal(1, TerminalLimits.MAX_DIMENSION + 1) }
    }

    @Test
    fun erasingInLineOrDisplayCancelsPendingWrap() {
        for (sequence in
            listOf(
                "\u001b[K",
                "\u001b[1K",
                "\u001b[2K",
                "\u001b[?K",
                "\u001b[?1K",
                "\u001b[?2K",
                "\u001b[J",
                "\u001b[1J",
                "\u001b[2J",
                "\u001b[?J",
                "\u001b[?1J",
                "\u001b[?2J",
            )) {
            val t = terminal(4, 2)
            t.write("abcd${sequence}Z")
            assertEquals('Z', t.lines()[0][3], sequence.replace("\u001b", "ESC"))
            assertEquals("    ", t.lines()[1], sequence.replace("\u001b", "ESC"))
            assertEquals(CursorState(3, 0), t.cursor, sequence.replace("\u001b", "ESC"))
        }
        // ED 3 clears only the scrollback, and unrecognized parameters do nothing, so a pending
        // wrap remains.
        for (sequence in listOf("\u001b[3J", "\u001b[4J", "\u001b[3K")) {
            val t = terminal(4, 2)
            t.write("abcd${sequence}Z")
            assertEquals(listOf("abcd", "Z   "), t.lines(), sequence.replace("\u001b", "ESC"))
            assertEquals(CursorState(1, 1), t.cursor, sequence.replace("\u001b", "ESC"))
        }
    }

    @Test
    fun invalidUtf8IsDisplayedAsReplacementCharacter() {
        val t = terminal(3, 1)
        t.write(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        assertEquals("a�b", t.lines()[0])
    }
}
