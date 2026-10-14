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

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Tests for the features in the scope of docs/decisions/0009, grouped by feature. */
class EmulationCoverageTest {
    private val events = mutableListOf<TerminalEvent>()

    private fun terminal(
        columns: Int = 20,
        rows: Int = 3,
        limits: TerminalLimits = TerminalLimits(),
    ) = Terminal(columns, rows, limits) { events += it }

    private fun Terminal.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun Terminal.line(row: Int) = snapshot().line(row)

    private fun replies() = events.filterIsInstance<TerminalEvent.ReplyRequested>().map { it.query }

    @Nested
    inner class CharacterSets {
        @Test
        fun decSpecialGraphicsDrawsLines() {
            val t = terminal()
            t.write("\u001b(0lqk\r\nx x\r\nmqj\u001b(Bq")
            assertEquals("┌─┐", t.line(0).text().trimEnd())
            assertEquals("│ │", t.line(1).text().trimEnd())
            assertEquals("└─┘q", t.line(2).text().trimEnd())
        }

        @Test
        fun shiftOutInvokesG1() {
            val t = terminal()
            t.write("\u001b)0a\u000eq\u000fq")
            assertEquals("a─q", t.line(0).text().trimEnd())
        }

        @Test
        fun unitedKingdomReplacesHash() {
            val t = terminal()
            t.write("\u001b(A#\u001b(B#")
            assertEquals("£#", t.line(0).text().trimEnd())
        }

        @Test
        fun nonAsciiIsNotMapped() {
            val t = terminal()
            t.write("\u001b(0あq")
            assertEquals("あ─", t.line(0).text().trimEnd())
        }

        @Test
        fun saveCursorKeepsTheCharacterSets() {
            val t = terminal()
            t.write("\u001b(0\u001b7\u001b(B\u001b8q")
            assertEquals("─", t.line(0).text().trimEnd())
        }

        @Test
        fun resetsRestoreAscii() {
            val t = terminal()
            t.write("\u001b(0\u001b[!pq\u001b(0\u001bcq")
            assertEquals("q", t.line(0).text().trimEnd())
        }
    }

    @Nested
    inner class CursorStyle {
        @Test
        fun decscusrSetsShapeAndBlinking() {
            val t = terminal()
            for ((ps, expected) in
                listOf(
                    1 to Pair(CursorShape.Block, true),
                    2 to Pair(CursorShape.Block, false),
                    3 to Pair(CursorShape.Underline, true),
                    4 to Pair(CursorShape.Underline, false),
                    5 to Pair(CursorShape.Bar, true),
                    6 to Pair(CursorShape.Bar, false),
                )) {
                t.write("\u001b[$ps q")
                assertEquals(expected, Pair(t.modes.cursorShape, t.modes.cursorBlinking), "Ps=$ps")
            }
        }

        @Test
        fun appDefaultIsShownUntilTheHostSetsAStyle() {
            val t = terminal()
            assertTrue(t.setDefaultCursorStyle(CursorShape.Bar, blinking = true))
            assertEquals(
                Pair(CursorShape.Bar, true),
                Pair(t.modes.cursorShape, t.modes.cursorBlinking),
            )
            t.write("\u001b[4 q")
            assertFalse(t.setDefaultCursorStyle(CursorShape.Block, blinking = false))
            assertEquals(
                Pair(CursorShape.Underline, false),
                Pair(t.modes.cursorShape, t.modes.cursorBlinking),
            )
            t.write("\u001b[0 q")
            assertEquals(
                Pair(CursorShape.Block, false),
                Pair(t.modes.cursorShape, t.modes.cursorBlinking),
            )
            t.write("\u001b[?12h")
            assertFalse(t.setDefaultCursorStyle(CursorShape.Bar, blinking = false))
            assertEquals(
                Pair(CursorShape.Block, true),
                Pair(t.modes.cursorShape, t.modes.cursorBlinking),
            )
            t.write("\u001bc")
            assertEquals(
                Pair(CursorShape.Bar, false),
                Pair(t.modes.cursorShape, t.modes.cursorBlinking),
            )
        }

        @Test
        fun mode12TogglesBlinking() {
            val t = terminal()
            t.write("\u001b[?12h")
            assertTrue(t.modes.cursorBlinking)
            t.write("\u001b[?12l")
            assertFalse(t.modes.cursorBlinking)
        }
    }

    @Nested
    inner class Queries {
        @Test
        fun decrqmReportsKnownModes() {
            val t = terminal()
            t.write("\u001b[?2004h\u001b[?2004\$p\u001b[?25\$p\u001b[?9999\$p\u001b[4\$p")
            assertEquals(
                listOf(
                    TerminalQuery.ModeStatus(2004, true, ModeState.Set),
                    TerminalQuery.ModeStatus(25, true, ModeState.Set),
                    TerminalQuery.ModeStatus(9999, true, ModeState.NotRecognized),
                    TerminalQuery.ModeStatus(4, false, ModeState.Reset),
                ),
                replies(),
            )
        }

        @Test
        fun decrqmReportsModesThatAreAlwaysOff() {
            val t = terminal()
            t.write("\u001b[?69\$p\u001b[?60\$p\u001b[2\$p\u001b[13\$p\u001b[?66h\u001b[?66\$p")
            assertEquals(
                listOf(
                    TerminalQuery.ModeStatus(69, true, ModeState.Reset),
                    TerminalQuery.ModeStatus(60, true, ModeState.PermanentlyReset),
                    TerminalQuery.ModeStatus(2, false, ModeState.Reset),
                    TerminalQuery.ModeStatus(13, false, ModeState.PermanentlyReset),
                    TerminalQuery.ModeStatus(66, true, ModeState.Set),
                ),
                replies(),
            )
            assertTrue(t.modes.applicationKeypad)
        }

        @Test
        fun decidIsPrimaryDeviceAttributes() {
            terminal().write("\u001bZ")
            assertEquals(listOf<TerminalQuery>(TerminalQuery.PrimaryDeviceAttributes), replies())
        }

        @Test
        fun decrqssReportsSettingsFromTheState() {
            val t = terminal(rows = 10)
            t.write("\u001b[1;31;58;5;99m\u001b[2;5r\u001b[4 q")
            t.write(
                "\u001bP\$qm\u001b\\\u001bP\$qr\u001b\\\u001bP\$q q\u001b\\\u001bP\$qrm -rf\u001b\\"
            )
            val sgr = replies()[0] as TerminalQuery.SgrStatus
            assertTrue(sgr.style.isBold)
            assertEquals(TerminalColor.indexed(1), sgr.style.foreground)
            // The underline color is reported even without an underline, as SGR set it.
            assertEquals(TerminalColor.indexed(99), sgr.underlineColor)
            assertEquals(
                listOf(
                    TerminalQuery.ScrollingRegionStatus(2, 5),
                    TerminalQuery.CursorStyleStatus(CursorShape.Underline, false),
                    TerminalQuery.UnsupportedStatus,
                ),
                replies().drop(1),
            )
        }

        @Test
        fun versionAndTextAreaSize() {
            val t = terminal(columns = 30, rows = 7)
            t.write("\u001b[>q\u001b[18t\u001b[14t")
            assertEquals(
                listOf(TerminalQuery.TerminalVersion, TerminalQuery.TextAreaSize(7, 30)),
                replies(),
            )
        }

        @Test
        fun colorQueries() {
            val t = terminal()
            t.write(
                "\u001b]10;?\u0007\u001b]11;?\u001b\\\u001b]12;?\u0007\u001b]4;1;?;300;?;2;?\u0007\u001b]10;#ffffff\u0007"
            )
            assertEquals(
                listOf(
                    TerminalQuery.Color(ColorTarget.Foreground),
                    TerminalQuery.Color(ColorTarget.Background),
                    TerminalQuery.Color(ColorTarget.Cursor),
                    TerminalQuery.Color(ColorTarget.Palette(1)),
                    TerminalQuery.Color(ColorTarget.Palette(2)),
                ),
                replies(),
            )
        }
    }

    @Nested
    inner class Unicode {
        @Test
        fun charactersBeyondTheBmpDoNotEndSequencesAsAscii() {
            val t = terminal()
            t.write("keep")
            // U+1006D ('m' in its low 16 bits) after SGR 31, U+1005A ('Z', DECID), and U+10063
            // ('c', RIS).
            t.write("\u001b[31\uD800\uDC6Dx\u001b\uD800\uDC5A\u001b\uD800\uDC63")
            assertEquals("keepx", t.line(0).text().trimEnd())
            assertEquals(TerminalColor.Default, t.line(0).styleAt(4).foreground)
            assertEquals(emptyList(), replies())
        }

        @Test
        fun widthsFollowTheGeneratedTables() {
            assertEquals("16.0.0", CharWidth.UNICODE_VERSION)
            val cases =
                mapOf(
                    'a'.code to 1,
                    0x00AD to 1,
                    0x0301 to 0,
                    0x200B to 0,
                    0x200D to 0,
                    0xFE0F to 0,
                    0x1160 to 0,
                    0xD7B0 to 0,
                    0x3042 to 2,
                    0xFF21 to 2,
                    0x1F600 to 2,
                    0x2764 to 1,
                    0x3FFFD to 2,
                    0x25CB to 1,
                    0x1F1EF to 1,
                    0xE0100 to 0,
                )
            for ((codePoint, width) in cases) assertEquals(
                width,
                CharWidth.of(codePoint),
                "U+%04X".format(codePoint),
            )
        }

        @Test
        fun ambiguousCharactersCanBeWide() {
            for (codePoint in listOf(0x25CB, 0x203B, 0x03B1, 0x0416, 0x00A7)) {
                assertEquals(1, CharWidth.of(codePoint), "U+%04X".format(codePoint))
                assertEquals(
                    2,
                    CharWidth.of(codePoint, AmbiguousWidth.Wide),
                    "U+%04X".format(codePoint),
                )
            }
            assertEquals(1, CharWidth.of('a'.code, AmbiguousWidth.Wide))
            val narrow = terminal()
            narrow.write("○x")
            assertEquals(2, narrow.cursor.column)
            val wide = Terminal(20, 3, ambiguousWidth = AmbiguousWidth.Wide)
            wide.write("○x")
            assertEquals(3, wide.cursor.column)
            assertEquals(2, wide.snapshot().line(0).widthAt(0))
        }

        @Test
        fun regionalIndicatorsPairIntoAFlag() {
            val t = terminal()
            // JP, then a third indicator that starts a new pair.
            t.write("a\uD83C\uDDEF\uD83C\uDDF5\uD83C\uDDFAb")
            val line = t.line(0)
            assertEquals("\uD83C\uDDEF\uD83C\uDDF5", line.textAt(1))
            assertEquals(2, line.widthAt(1))
            assertEquals(0, line.widthAt(2))
            assertEquals("\uD83C\uDDFA", line.textAt(3))
            assertEquals("b", line.textAt(4))
            // The same cells as wcwidth() counts: 1 + 1 + 1 + 1 + 1.
            assertEquals(5, t.cursor.column)
        }

        @Test
        fun regionalIndicatorsDoNotPairAcrossAWrap() {
            val t = terminal(columns = 3)
            t.write("ab\uD83C\uDDEF\uD83C\uDDF5")
            assertEquals("\uD83C\uDDEF", t.line(0).textAt(2))
            assertEquals("\uD83C\uDDF5", t.line(1).textAt(0))
        }

        @Test
        fun emojiZwjSequencesKeepOneCellPerEmoji() {
            // As wcwidth() counts: each emoji is two cells; ZWJ joins the previous cell.
            val t = terminal()
            t.write("\uD83D\uDC68\u200D\uD83D\uDC69")
            assertEquals(4, t.cursor.column)
            assertEquals("\uD83D\uDC68\u200D", t.line(0).textAt(0))
        }
    }

    @Nested
    inner class Hyperlinks {
        @Test
        fun cellsCarryTheLink() {
            val t = terminal()
            t.write("a\u001b]8;id=x;https://example.com\u001b\\link\u001b]8;;\u001b\\b")
            val line = t.line(0)
            assertNull(line.hyperlinkAt(0))
            assertEquals(Hyperlink("https://example.com", "x"), line.hyperlinkAt(1))
            assertEquals(Hyperlink("https://example.com", "x"), line.hyperlinkAt(4))
            assertNull(line.hyperlinkAt(5))
        }

        @Test
        fun erasingRemovesTheLink() {
            val t = terminal()
            t.write("\u001b]8;;https://a\u0007ab\u001b]8;;\u0007\u001b[1G\u001b[K")
            assertNull(t.line(0).hyperlinkAt(0))
        }

        @Test
        fun linksSurviveInTheScrollback() {
            val t = terminal(rows = 1)
            t.write("\u001b]8;;https://a\u0007x\u001b]8;;\u0007\r\ny")
            assertEquals("https://a", t.scrollbackLine(0).hyperlinkAt(0)?.uri)
        }

        @Test
        fun tooLongUrisAreDropped() {
            val t = terminal()
            t.write("\u001b]8;;https://${"a".repeat(3000)}\u0007x")
            assertNull(t.line(0).hyperlinkAt(0))
        }

        @Test
        fun tooLongIdsAreDropped() {
            // Otherwise 4096 links with ids as long as an OSC string would take gigabytes.
            val t = terminal()
            val longest = "i".repeat(Emulator.MAX_LINK_ID_LENGTH)
            t.write("\u001b]8;id=$longest;https://example.com\u0007x")
            t.write("\u001b]8;id=${longest}i;https://example.com\u0007y\u001b]8;;\u0007")
            assertEquals(longest, t.line(0).hyperlinkAt(0)?.id)
            assertNull(t.line(0).hyperlinkAt(1))
        }

        @Test
        fun unusedLinksAreForgottenWhenTheTableIsFull() {
            val t = terminal(columns = 2, rows = 1, limits = TerminalLimits(scrollbackLines = 0))
            // More distinct links than the table holds, each overwriting the previous one.
            repeat(5000) { t.write("\r\u001b]8;;https://example.com/$it\u0007x\u001b]8;;\u0007") }
            assertEquals("https://example.com/4999", t.line(0).hyperlinkAt(0)?.uri)
        }

        @Test
        fun linksInUseAreNotSearchedForEveryNewLink() {
            // Fill the table with links that the scrollback keeps in use, on long lines.
            val t =
                terminal(columns = 200, rows = 1, limits = TerminalLimits(scrollbackLines = 5000))
            val text = "x".repeat(199)
            repeat(Emulator.MAX_HYPERLINKS) { t.write("\u001b]8;;https://a/$it\u0007$text\r\n") }
            // Each new link, used by no cell, must not make the terminal visit all the cells again:
            // that would be about 10^5 sequences times 8 * 10^5 cells.
            val flood = buildString { repeat(100_000) { append("\u001b]8;;https://b/$it\u0007") } }
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                t.write(flood.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Nested
    inner class SavedTitles {
        private fun titles() =
            events.filterIsInstance<TerminalEvent.TitleChanged>().map { it.title }

        private fun Terminal.setTitle(title: String) = write("\u001b]2;$title\u0007")

        @Test
        fun poppingRestoresThePushedTitle() {
            val t = terminal()
            t.setTitle("shell")
            t.write("\u001b[22;2t")
            t.setTitle("vim")
            t.write("\u001b[23;2t")
            assertEquals(listOf("shell", "vim", "shell"), titles())
        }

        @Test
        fun titlesArePoppedInReverseOrder() {
            val t = terminal()
            t.setTitle("a")
            t.write("\u001b[22;2t")
            t.setTitle("b")
            t.write("\u001b[22;2t")
            t.setTitle("z")
            events.clear()
            t.write("\u001b[23;2t\u001b[23;2t")
            assertEquals(listOf("b", "a"), titles())
        }

        @Test
        fun bothTitlesAndTheDefaultSaveTheTitle() {
            val t = terminal()
            t.setTitle("a")
            t.write("\u001b[22;0t")
            t.setTitle("b")
            t.write("\u001b[22t")
            t.setTitle("z")
            events.clear()
            t.write("\u001b[23t\u001b[23;0t")
            assertEquals(listOf("b", "a"), titles())
        }

        @Test
        fun theIconNameAloneIsIgnored() {
            // vim saves the title and the icon name separately, and restores them in either order.
            for (restore in listOf("\u001b[23;1t\u001b[23;2t", "\u001b[23;2t\u001b[23;1t")) {
                val t = terminal()
                t.setTitle("shell")
                t.write("\u001b[22;2t\u001b[22;1t")
                t.setTitle("vim")
                events.clear()
                t.write(restore)
                assertEquals(listOf("shell"), titles())
            }
        }

        @Test
        fun poppingWithNothingSavedDoesNothing() {
            val t = terminal()
            t.setTitle("a")
            t.write("\u001b[22;1t")
            events.clear()
            t.write("\u001b[23;2t\u001b[23;0t")
            assertEquals(emptyList(), titles())
        }

        @Test
        fun theTitleBeforeAnyIsEmpty() {
            val t = terminal()
            t.write("\u001b[22;2t")
            t.setTitle("a")
            t.write("\u001b[23;2t")
            assertEquals(listOf("a", ""), titles())
        }

        @Test
        fun anIndexIntoTheStackIsIgnored() {
            val t = terminal()
            t.setTitle("a")
            t.write("\u001b[22;0;1t")
            t.write("\u001b[22;2t")
            t.setTitle("b")
            events.clear()
            t.write("\u001b[23;0;1t")
            assertEquals(emptyList(), titles())
            t.write("\u001b[23;2t\u001b[23;2t")
            assertEquals(listOf("a"), titles())
        }

        @Test
        fun resetKeepsTheSavedTitles() {
            val t = terminal()
            t.setTitle("a")
            t.write("\u001b[22;2t")
            t.setTitle("b")
            t.write("\u001bc\u001b[23;2t")
            assertEquals(listOf("a", "b", "a"), titles())
        }

        @Test
        fun theOldestTitlesAreDroppedBeyondTheDepth() {
            val t = terminal()
            repeat(Emulator.MAX_SAVED_TITLES + 1) {
                t.setTitle("t$it")
                t.write("\u001b[22;2t")
            }
            events.clear()
            repeat(Emulator.MAX_SAVED_TITLES + 1) { t.write("\u001b[23;2t") }
            assertEquals((Emulator.MAX_SAVED_TITLES downTo 1).map { "t$it" }, titles())
        }

        @Test
        fun theSavedTitlesTogetherAreNoLongerThanAString() {
            // Each title fits in the string limit, but the two of them together do not.
            val t = terminal(limits = TerminalLimits(maxStringLength = 100))
            t.setTitle("a".repeat(60))
            t.write("\u001b[22;2t")
            t.setTitle("b".repeat(60))
            t.write("\u001b[22;2t")
            t.setTitle("z")
            events.clear()
            t.write("\u001b[23;2t\u001b[23;2t")
            assertEquals(listOf("b".repeat(60)), titles())
        }
    }
}
