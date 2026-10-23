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

import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ReviewReproTest {
    private fun Terminal.writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun apc(control: String, payload: ByteArray? = null): String =
        "\u001b_G$control" +
            (payload?.let { ";" + Base64.getEncoder().encodeToString(it) } ?: "") +
            "\u001b\\"

    /**
     * CORE-1: Placing a full-screen image (`c=columns, r=rows`) at (0, 0) on the alternate screen
     * (or with `scrollbackLines = 0`, or at the top of a scrolling region `scrollTop > 0`) wraps
     * the cursor to `(0, rows)` and scrolls up by 1 line, which calls `Line.killAnchors()` on row 0
     * and immediately destroys the placement (and an un-ID'd image) even though `rows - 1` rows of
     * the image are still on the screen. Likewise, scrolling up by 1 line on the alternate screen
     * destroys any multi-row image anchored at row 0.
     */
    @Test
    fun reproCore1_alternateScreenFullScreenPlacementIsDestroyedImmediatey() {
        val terminal = Terminal(10, 5).apply { setCellSize(10, 20) }
        terminal.writeText("\u001b[?1049h\u001b[H")
        terminal.writeText(apc("a=T,i=1,f=24,s=1,v=1,c=10,r=5,q=2", byteArrayOf(0, 0, 0)))

        val placements = terminal.snapshot().imagePlacements
        assertEquals(1, placements.size, "4 of the 5 rows of the image remain on the screen")
    }

    /**
     * CORE-2: Placing an image taller than the screen (`rowsSpanned > rows`) with `C=0` calls
     * `ScreenBuffer.scrollUp` with `count = y - scrollBottom`, which clamps `n = minOf(count,
     * height)` to the screen height instead of scrolling by `y - scrollBottom` rows. The image's
     * anchor is not scrolled far enough into the scrollback, so the image overhangs past the bottom
     * of the screen and the cursor is not on the image's last row.
     */
    @Test
    fun reproCore2_imageTallerThanScreenDoesNotScrollFarEnough() {
        val terminal = Terminal(10, 5).apply { setCellSize(10, 20) }
        // Place a 2x12 image at (0, 0) on a 10x5 screen. Its last row is 11, which is 7 rows past
        // the bottom row (4), so 7 lines must scroll into the scrollback and the image must start
        // at row -7 so its bottom lands on the bottom of the 5-row screen (-7 + 12 = 5).
        terminal.writeText(apc("a=T,i=1,f=24,s=1,v=1,c=2,r=12,q=2", byteArrayOf(0, 0, 0)))

        val placement = terminal.snapshot().imagePlacements.single()
        assertEquals(7, terminal.scrollbackSize)
        assertEquals(-7, placement.row)
    }

    /**
     * CORE-3: `Line.breakWide` only clears `codes` (and `clusters` for the left half) when a wide
     * character is split by overwriting, erasing (`ECH`/`EL`/`ED`), inserting blanks (`ICH`), or
     * deleting characters (`DCH`). It leaves `links`, `underlineColors`, and (on erase/ICH/DCH)
     * `styles` intact on the orphaned blank cell.
     */
    @Test
    fun reproCore3_breakingWideCharacterLeavesHyperlinkUnderlineColorAndStyleOnBlankHalf() {
        val terminal = Terminal(10, 2)
        // Write a wide character 'あ' at columns 0..1 with an OSC 8 hyperlink, curly underline,
        // indexed underline color 2, and red background (41).
        terminal.writeText(
            "\u001b]8;;https://example.com\u001b\\\u001b[4:3;58;5;2;41mあ\u001b[0m\u001b]8;;\u001b\\"
        )
        // Erase column 0 with ECH 1 (`\e[1G\e[1X`) using default style. Column 1 (the right half of
        // 'あ') is also erased to a blank because wide characters cannot be split.
        terminal.writeText("\u001b[1G\u001b[1X")

        val line = terminal.snapshot().line(0)
        assertNull(line.textAt(1), "right half of split wide character is blank")
        assertNull(line.hyperlinkAt(1), "erased blank half must not keep OSC 8 hyperlink")
        assertEquals(
            TerminalColor.Default,
            line.underlineColorAt(1),
            "erased blank half must not keep underline color",
        )
        assertEquals(
            CellStyle.Default,
            line.styleAt(1),
            "erased blank half must not keep old underline/background style",
        )
    }

    /**
     * CORE-4: `Line.compact()` truncates `styles` at `contentEnd()` (the last non-blank code point)
     * and stores only `styles[columns - 1]` as `trailingStyle`. Blank cells before `columns - 1`
     * whose background color differs from `styles[columns - 1]` (e.g. from `ECH`, `EL 1`, `ED 1`,
     * or `EL 0` followed by erasing the last cell with another background) lose their background
     * color when the line scrolls into the scrollback.
     */
    @Test
    fun reproCore4_scrollbackCompactionLosesBackgroundOfBlankCellsBeforeEndOfLine() {
        val terminal = Terminal(10, 1)
        // Erase the first 4 cells of row 0 with blue background (44) using ECH 4 (`\e[4X`), leaving
        // cells 4..9 with the default background, then scroll the line into the scrollback.
        terminal.writeText("\u001b[44m\u001b[4X\u001b[0m\r\n")

        val scrolledLine = terminal.snapshot(scrollOffset = 1).line(0)
        assertEquals(
            TerminalColor.indexed(4),
            scrolledLine.styleAt(0).background,
            "blank cell erased with blue background must keep its background in the scrollback",
        )
    }

    /**
     * CORE-5: `Parser` does not end `CSI_IGNORE` or `DCS` sequences when a non-ASCII code point (`>
     * 0x7E`, including `U+FFFD` from a truncated UTF-8 sequence) arrives where a final byte is
     * expected. In `CSI_IGNORE`, non-ASCII text after a malformed CSI (`\e[??`) is swallowed until
     * an ASCII byte in `0x40..0x7E` appears. In `DCS_ENTRY`/`DCS_PARAM`/`DCS_INTERMEDIATE`, a
     * truncated UTF-8 byte (`0xC3`) decodes as `U+FFFD`, which `dcsHook` accepts as the DCS final
     * byte, entering `DCS_PASSTHROUGH` and swallowing all subsequent output until ST/ESC/CAN/SUB.
     */
    @Test
    fun reproCore5_nonAsciiInCsiIgnoreOrDcsSwallowsSubsequentOutput() {
        val terminal = Terminal(20, 2)
        // 1. Malformed CSI (`\e[??`) followed by non-ASCII text "あいう".
        terminal.writeText("\u001b[??あいう")
        assertEquals("あいう", terminal.snapshot().line(0).text().trimEnd())

        // 2. Truncated UTF-8 sequence (0xC3) inside DCS (`\eP` + 0xC3 + "Hello").
        val terminal2 = Terminal(20, 2)
        terminal2.write(
            byteArrayOf(0x1B, 'P'.code.toByte(), 0xC3.toByte()) + "Hello".toByteArray()
        )
        assertEquals("Hello", terminal2.snapshot().line(0).text().trimEnd())
    }

    /**
     * CORE-6: `Terminal.reset()` resets `Emulator` but does not reset `Parser` (or `Utf8Decoder`).
     * If `reset()` is called while an unclosed `OSC`, `DCS`, `APC`, or `SOS`/`PM` string is open,
     * subsequent text written after `reset()` is swallowed into the string, and when `BEL` or `ST`
     * later arrives, the pre-reset command is dispatched after the reset.
     */
    @Test
    fun reproCore6_terminalResetLeavesParserInUnclosedStringState() {
        val events = mutableListOf<TerminalEvent>()
        val terminal = Terminal(20, 2) { events += it }
        // Start an unclosed OSC 0 title string before reset().
        terminal.writeText("\u001b]0;BeforeReset")
        terminal.reset()

        // Plain text written after reset() should be displayed on the screen, and a subsequent BEL
        // should not set the title to the pre-reset string.
        terminal.writeText("Hello\u0007")
        assertEquals("Hello", terminal.snapshot().line(0).text().trimEnd())
        assertTrue(events.none { it is TerminalEvent.TitleChanged })
    }

    /**
     * CORE-7: When a wrapped line in the scrollback spans multiple rows (e.g. lines 0 and 1) and
     * line 0 is later evicted by scrollback capacity (`oldestLine = 1`), `SearchIndex` keeps the
     * combined `SearchText(0..1)` in `texts` and only removes matches with `firstLine <
     * oldestLine`. A match that started on line 0 and overlapped a valid match on surviving line 1
     * suppressed the line 1 match during non-overlapping matching, so after line 0 is evicted,
     * `SearchIndex` (both on `update` and on `withQuery`) misses the match on line 1.
     */
    @Test
    fun reproCore7_searchIndexLosesMatchOnSurvivingWrappedRowAfterFirstRowLeavesScrollback() {
        val terminal = Terminal(4, 1, TerminalLimits(scrollbackLines = 2))
        // Line 0: "xxfo" (wrapped), Line 1: "fofo" (in scrollback), Line 2: "1" (on screen).
        terminal.writeText("xxfofofo\r\n1")

        val index = SearchIndex(SearchQuery("fofo", ignoreCase = false))
        while (index.update(terminal.searchLines(index.nextLine, 1000))) {}
        assertEquals(listOf(SearchMatch(0, 2, 1, 2)), index.matches)

        // Scroll 1 more line: Line 0 ("xxfo") is evicted (`oldestLine = 1`), while Line 1 ("fofo")
        // remains in the scrollback at line 1.
        terminal.writeText("\r\n2")
        while (index.update(terminal.searchLines(index.nextLine, 1000))) {}

        val fresh = SearchIndex(SearchQuery("fofo", ignoreCase = false))
        while (fresh.update(terminal.searchLines(fresh.nextLine, 1000))) {}
        assertEquals(listOf(SearchMatch(1, 0, 1, 4)), fresh.matches)
        assertEquals(fresh.matches, index.matches)
    }

    /**
     * CORE-8: `Terminal.write` checks `offset + length <= bytes.size`, which overflows 32-bit
     * signed `Int` when `offset > 0` and `length > Int.MAX_VALUE - offset` (e.g., `offset = 1,
     * length = Int.MAX_VALUE`), silently doing nothing instead of throwing
     * `IllegalArgumentException`.
     */
    @Test
    fun reproCore8_writeRangeCheckOverflowsInt() {
        val terminal = Terminal(10, 2)
        assertFailsWith<IllegalArgumentException> {
            terminal.write(ByteArray(4), offset = 1, length = Int.MAX_VALUE)
        }
    }

    /**
     * CORE-9: `CSI S` (`SU`), `CSI T` (`SD`), `CSI L` (`IL`), and `CSI M` (`DL`) scroll the lines
     * covering the cursor's row without incrementing `Terminal.lineChanges`, violating the contract
     * that `lineChanges` increments when the cursor is left on a different line scrolled into its
     * row.
     */
    @Test
    fun reproCore9_scrollingCommandsDoNotIncrementLineChanges() {
        val terminal = Terminal(10, 4)
        terminal.writeText("line1\r\nline2")
        val before = terminal.lineChanges
        // Scroll the screen up by 1 line with CSI S: the cursor at row 1 is now on a new blank
        // line, just as after LF at the bottom margin.
        terminal.writeText("\u001b[S")
        assertTrue(terminal.lineChanges > before, "SU scrolling cursor's row must increment lineChanges")
    }

    /**
     * CORE-10: On a 1-column terminal (`columns = 1`), `Emulator.printMapped` executes `if
     * (pendingWrap && autowrap) wrap()` before checking `if (columns < 2) return`. As a result,
     * attempting to print a wide character after a narrow character on a 1-column terminal wraps
     * the line and scrolls the screen even though the wide character cannot fit and is dropped.
     */
    @Test
    fun reproCore10_wideCharacterAtOneColumnWrapsAndScrollsBeforeBeingDropped() {
        val terminal = Terminal(1, 2)
        terminal.writeText("a\r\nb")
        assertEquals(0, terminal.scrollbackSize)
        assertEquals("a", terminal.snapshot().line(0).text())
        assertEquals("b", terminal.snapshot().line(1).text())

        // 'あ' (width 2) cannot fit in 1 column and is dropped, so it must not wrap 'b' or scroll
        // 'a' off the screen.
        terminal.writeText("あ")
        assertEquals(0, terminal.scrollbackSize)
        assertEquals("a", terminal.snapshot().line(0).text())
        assertEquals("b", terminal.snapshot().line(1).text())
    }

    /**
     * CORE-11: When open matches on the screen (`closing = false`) push `kept.size` past
     * `SearchIndex.MAX_MATCHES` (1,000), `SearchIndex.add` removes the oldest matches from `kept`
     * (which are closed scrollback matches), decrements `closedCount`, and sets
     * `droppedClosedLine`. On the next `update` after the screen is cleared or overwritten,
     * `while (kept.size > closedCount) kept.removeLast()` removes all open screen matches without
     * restoring the closed scrollback matches that they displaced, leaving `index.matches` missing
     * scrollback matches and `index.isCapped` permanently `true`.
     */
    @Test
    fun reproCore11_openScreenMatchesExceedingMaxMatchesPermanentlyEvictClosedScrollbackMatches() {
        val terminal = Terminal(100, 11)
        // Put 1 match ("x") into scrollback line 0 by scrolling 1 line off the 11-row screen.
        terminal.writeText("x" + "\r\n".repeat(11))
        assertEquals(1, terminal.scrollbackSize)

        val index = SearchIndex(SearchQuery("x", ignoreCase = false))
        while (index.update(terminal.searchLines(index.nextLine, 1000))) {}
        assertEquals(listOf(SearchMatch(0, 0, 0, 1)), index.matches)
        assertFalse(index.isCapped)

        // Fill the 11-row screen with 1,000 'x' characters (10 rows of 100 'x's) without scrolling.
        terminal.writeText("\u001b[H" + (1..10).joinToString("\r\n") { "x".repeat(100) })
        assertEquals(1, terminal.scrollbackSize)
        while (index.update(terminal.searchLines(index.nextLine, 1000))) {}
        assertEquals(SearchIndex.MAX_MATCHES, index.matches.size)
        assertTrue(index.isCapped)

        // Clear the screen (`\e[2J`): the screen now has 0 matches, and scrollback line 0 still has
        // its 1 match ("x").
        terminal.writeText("\u001b[2J")
        while (index.update(terminal.searchLines(index.nextLine, 1000))) {}

        assertEquals(listOf(SearchMatch(0, 0, 0, 1)), index.matches)
        assertFalse(index.isCapped)
    }

    /**
     * CORE-12: When transmitting an image with an `id` (`i=<id>`) that already exists on the active
     * screen, `Graphics.finish` calls `makeRoom(screen.layer, bytes)` before `GraphicsLayer.add`
     * deletes the old image with the same `id`. Because `this.bytes` (and `layer.imageCount`) still
     * includes the image being replaced during `makeRoom`, `makeRoom` evicts unrelated older images
     * (and their placements) even when replacing the existing image fits within `maxImageBytes` and
     * `MAX_IMAGES`.
     */
    @Test
    fun reproCore12_replacingImageByIdEvictsUnrelatedImageBeforeFreeingReplacedImage() {
        // Each 5x5 image takes 5 * 5 * 4 = 100 bytes of decoded pixels; maxImageBytes = 200 fits 2
        // images at once.
        val terminal = Terminal(20, 5, TerminalLimits(maxImageBytes = 200)).apply { setCellSize(10, 20) }
        val rgb5x5 = ByteArray(5 * 5 * 3)

        // Transmit and place image i=1 (100 bytes) and image i=2 (100 bytes). Total = 200 bytes.
        terminal.writeText(apc("a=T,i=1,f=24,s=5,v=5,C=1,q=2", rgb5x5))
        terminal.writeText("\u001b[1;5H" + apc("a=T,i=2,f=24,s=5,v=5,C=1,q=2", rgb5x5))
        assertEquals(2, terminal.snapshot().imagePlacements.size)

        // Replace image i=2 with a new 5x5 image (100 bytes). Replacing i=2 keeps total memory at
        // 200 bytes (i=1 + i=2), so unrelated image i=1 must not be evicted.
        terminal.writeText("\u001b[1;5H" + apc("a=T,i=2,f=24,s=5,v=5,C=1,q=2", rgb5x5))
        assertEquals(2, terminal.snapshot().imagePlacements.size)
    }
}
