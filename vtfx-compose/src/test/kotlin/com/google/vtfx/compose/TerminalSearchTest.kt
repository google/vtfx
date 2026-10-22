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
import com.google.vtfx.core.SearchIndex
import com.google.vtfx.core.SearchQuery
import com.google.vtfx.core.Terminal
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The search of a display (docs/decisions/0047), through [TerminalDisplay]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class TerminalSearchTest {
    private val session = TerminalSession({}, columns = 20, rows = 5)
    private val display = TerminalDisplay(session).also { it.attach() }

    private fun feed(text: String) = session.feed(text.toByteArray(Charsets.UTF_8))

    private fun query(text: String) = SearchQuery(text, ignoreCase = false)

    private fun result(matchCount: Int, currentMatchIndex: Int?) =
        TerminalSearchResult(matchCount, isMatchCountCapped = false, currentMatchIndex)

    private fun lines(count: Int) = (1..count).joinToString("\r\n") { "line $it" }

    @Test
    fun aSearchCountsTheMatchesAndMovesToTheNewest() {
        feed("foo\r\nbar foo\r\nfoo")

        display.search(query("foo"))

        assertEquals(result(3, 2), display.searchResult)
    }

    @Test
    fun findPreviousMovesToTheMatchAbove() {
        feed("foo\r\nbar foo\r\nfoo")
        display.search(query("foo"))

        display.findPrevious()

        assertEquals(result(3, 1), display.searchResult)
    }

    @Test
    fun findNextGoesFromTheLastMatchToTheFirst() {
        feed("foo\r\nbar foo\r\nfoo")
        display.search(query("foo"))

        display.findNext()

        assertEquals(result(3, 0), display.searchResult)
    }

    @Test
    fun findPreviousGoesFromTheFirstMatchToTheLast() {
        feed("foo\r\nfoo")
        display.search(query("foo"))
        display.findPrevious()

        display.findPrevious()

        assertEquals(result(2, 1), display.searchResult)
    }

    @Test
    fun typingMoreKeepsTheCurrentMatchWhileItMatches() {
        feed("foobar\r\nfoo\r\nfoo")
        display.search(query("foo"))
        display.findPrevious()
        display.findPrevious()

        display.search(query("foob"))

        assertEquals(result(1, 0), display.searchResult)
    }

    @Test
    fun aNewQueryMovesToTheNearestMatchAboveTheCurrentOne() {
        feed("bar\r\nfoo\r\nbar\r\nfoo")
        display.search(query("foo"))
        display.findPrevious()

        display.search(query("bar"))

        assertEquals(result(2, 0), display.searchResult)
    }

    @Test
    fun aMatchInTheScrollbackIsScrolledTo() {
        feed("needle\r\n" + lines(50))

        display.search(query("needle"))

        assertTrue(display.isScrolledBack)
        assertEquals(0L, display.displaySnapshot().firstLine)
    }

    @Test
    fun aMatchOnTheDisplayDoesNotScrollIt() {
        feed(lines(50) + "\r\nneedle")

        display.search(query("needle"))

        assertFalse(display.isScrolledBack)
    }

    @Test
    fun outputAddsMatchesAndKeepsTheCurrentOne() {
        feed("foo")
        display.search(query("foo"))

        feed("\r\nfoo")

        assertEquals(result(2, 0), display.searchResult)
    }

    @Test
    fun outputThatErasesTheCurrentMatchMovesToTheNearestAboveIt() {
        feed("foo\r\nfoo")
        display.search(query("foo"))

        feed("\u001b[2K")

        assertEquals(result(1, 0), display.searchResult)
    }

    @Test
    fun readingStopsWhileOutputScrollsFasterThanTheReads() {
        val terminal = Terminal(80, 24)
        terminal.write("y\r\n".repeat(20_000).toByteArray(Charsets.UTF_8))
        val index = SearchIndex(query("y"))
        readSearchLines(index, minLines = 1000, terminal::searchLines)
        // About 8 KiB, which scrolls more lines than a read reads.
        val output = "y\r\n".repeat(2730).toByteArray(Charsets.UTF_8)
        var reads = 0

        readSearchLines(index, minLines = 1000) { from, minLines ->
            check(++reads < 1000) { "the reads did not stop" }
            // The output that the session takes between two reads.
            terminal.write(output)
            terminal.searchLines(from, minLines)
        }
        readSearchLines(index, minLines = 1000, terminal::searchLines)

        val screenLine = terminal.searchLines(Long.MAX_VALUE, 1).screenLine
        assertEquals(screenLine + terminal.rows - 2, index.matches.last().firstLine)
    }

    @Test
    fun theSearchStaysAtTheMatchesWhenTheUserTypes() {
        feed("foo")
        display.search(query("foo"))

        display.notifyEditorChanged()

        assertEquals(result(1, 0), display.searchResult)
    }

    @Test
    fun noQueryEndsTheSearch() {
        feed("foo")
        display.search(query("foo"))

        display.search(null)

        assertNull(display.searchResult)
        assertNull(display.visibleMatches(display.displaySnapshot()))
    }

    @Test
    fun aSearchWithoutMatchesHasNoCurrentMatch() {
        feed("foo")

        display.search(query("bar"))

        assertEquals(result(0, null), display.searchResult)
    }

    @Test
    fun onlyTheMatchesOnTheDisplayAreVisible() {
        feed(lines(50))
        display.search(query("line"))

        val visible = display.visibleMatches(display.displaySnapshot())

        assertEquals(listOf(45L, 46L, 47L, 48L, 49L), visible?.matches?.map { it.firstLine })
    }
}
