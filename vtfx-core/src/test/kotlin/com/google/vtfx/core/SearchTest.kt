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

import java.lang.management.ManagementFactory
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SearchTest {
    private fun terminal(
        columns: Int,
        rows: Int,
        output: String,
        limits: TerminalLimits = TerminalLimits(),
    ) = Terminal(columns, rows, limits).also { it.write(output) }

    private fun Terminal.write(output: String) = write(output.toByteArray(Charsets.UTF_8))

    /** Brings the index up to date with [terminal], reading [minLines] at a time. */
    private fun SearchIndex.update(terminal: Terminal, minLines: Int = 1000) {
        while (update(terminal.searchLines(nextLine, minLines))) {
            // Reads until the index has the screen.
        }
    }

    private fun search(
        terminal: Terminal,
        text: String,
        ignoreCase: Boolean = false,
        minLines: Int = 1000,
    ): SearchIndex =
        SearchIndex(SearchQuery(text, ignoreCase)).also { it.update(terminal, minLines) }

    /** The matches of [index] as firstLine:startColumn-lastLine:endColumn. */
    private fun found(index: SearchIndex): List<String> =
        index.matches.map { "${it.firstLine}:${it.startColumn}-${it.lastLine}:${it.endColumn}" }

    @Test
    fun findsEachOccurrenceOnTheScreen() {
        val terminal = terminal(20, 4, "foo bar\r\nbar foo foo")

        val index = search(terminal, "foo")

        assertEquals(listOf("0:0-0:3", "1:4-1:7", "1:8-1:11"), found(index))
    }

    @Test
    fun caseMattersUnlessIgnored() {
        val terminal = terminal(20, 4, "Foo FOO foo")

        val exact = search(terminal, "foo")
        val ignoringCase = search(terminal, "foo", ignoreCase = true)

        assertEquals(listOf("0:8-0:11"), found(exact))
        assertEquals(listOf("0:0-0:3", "0:4-0:7", "0:8-0:11"), found(ignoringCase))
    }

    @Test
    fun aMatchGoesOnOverRowsThatAutowrapJoins() {
        val terminal = terminal(10, 4, "xxxxxxxabcdef")

        val index = search(terminal, "abcdef")

        assertEquals(listOf("0:7-1:3"), found(index))
    }

    @Test
    fun aMatchDoesNotGoOverALineBreak() {
        val terminal = terminal(10, 4, "abc\r\ndef")

        val index = search(terminal, "cd")

        assertEquals(emptyList(), found(index))
    }

    @Test
    fun aMatchCoversWideCharactersWhole() {
        val terminal = terminal(10, 4, "日本語")

        val index = search(terminal, "本")

        assertEquals(listOf("0:2-0:4"), found(index))
    }

    @Test
    fun theBlankThatAutowrapLeavesBeforeAWideCharacterIsNotText() {
        val terminal = terminal(5, 4, "abcd日")

        val index = search(terminal, "d日")

        assertEquals(listOf("0:3-1:2"), found(index))
    }

    @Test
    fun concealedTextIsNotMatched() {
        val terminal = terminal(20, 4, "\u001b[8msecret\u001b[0m secret")

        val index = search(terminal, "secret")

        assertEquals(listOf("0:7-0:13"), found(index))
    }

    @Test
    fun anOccurrenceOverConcealedTextLeavesTheOneOverlappingIt() {
        val terminal = terminal(20, 4, "\u001b[8ma\u001b[0maa")

        val index = search(terminal, "aa")

        assertEquals(listOf("0:1-0:3"), found(index))
    }

    @Test
    fun matchesDoNotOverlap() {
        val terminal = terminal(20, 4, "aaaaa")

        val index = search(terminal, "aa")

        assertEquals(listOf("0:0-0:2", "0:2-0:4"), found(index))
    }

    @Test
    fun theBlanksAtTheEndOfALineAreNotText() {
        val terminal = terminal(20, 4, "foo bar")

        val index = search(terminal, "bar ")

        assertEquals(emptyList(), found(index))
    }

    @Test
    fun anotherQueryFindsItsMatchesInTheLinesAlreadyRead() {
        val output = (1..30).joinToString("\r\n") { "line $it foo bar" }
        val terminal = terminal(20, 5, output)
        val index = search(terminal, "foo")

        val other = index.withQuery(SearchQuery("bar", ignoreCase = false))
        other.update(terminal)

        assertEquals(found(search(terminal, "bar")), found(other))
    }

    @Test
    fun anotherQueryKeepsUpWithTheScrollback() {
        val terminal = terminal(20, 2, "match 1\r\nx")
        val other = search(terminal, "x").withQuery(SearchQuery("match", ignoreCase = false))
        other.update(terminal)
        terminal.write("\r\nmatch 2\r\ny\r\nz")

        other.update(terminal)

        assertEquals(listOf("0:0-0:5", "2:0-2:5"), found(other))
    }

    @Test
    fun linesKeepTheirNumbersInTheScrollback() {
        val terminal = terminal(20, 2, "match a\r\nx")
        val index = search(terminal, "match")
        terminal.write("\r\nmatch b\r\ny\r\nz")

        index.update(terminal)

        assertEquals(listOf("0:0-0:5", "2:0-2:5"), found(index))
    }

    @Test
    fun readingFewLinesAtATimeFindsTheSameMatches() {
        val output = (1..30).joinToString("\r\n") { "line $it " + "x".repeat(it % 25) + "match" }
        val terminal = terminal(20, 5, output)

        val oneAtATime = search(terminal, "match", minLines = 1)

        assertEquals(found(search(terminal, "match")), found(oneAtATime))
    }

    @Test
    fun aMatchOfTheScreenGoesWhenTheScreenChanges() {
        val terminal = terminal(20, 4, "match")
        val index = search(terminal, "match")
        terminal.write("\u001b[2J")

        index.update(terminal)

        assertEquals(emptyList(), found(index))
    }

    @Test
    fun matchesGoWithTheLinesThatLeaveTheScrollback() {
        val terminal = terminal(20, 1, "match 1\r\nmatch 2", TerminalLimits(scrollbackLines = 1))
        val index = search(terminal, "match")
        terminal.write("\r\nmatch 3")

        index.update(terminal)

        assertEquals(listOf("1:0-1:5", "2:0-2:5"), found(index))
    }

    @Test
    fun clearingTheScrollbackDropsItsMatches() {
        val terminal = terminal(20, 2, "match 1\r\nmatch 2\r\nmatch 3")
        val index = search(terminal, "match")
        terminal.write("\u001b[3J")

        index.update(terminal)

        assertEquals(listOf("1:0-1:5", "2:0-2:5"), found(index))
    }

    @Test
    fun aResizeSearchesTheReflowedLinesAgain() {
        val terminal = terminal(10, 2, "xxxxxxxmatch\r\nmatch\r\nend")
        val index = search(terminal, "match")
        terminal.resize(20, 2)

        index.update(terminal)

        assertEquals(found(search(terminal, "match")), found(index))
    }

    @Test
    fun theAlternateScreenIsSearchedWithoutTheScrollback() {
        val terminal = terminal(20, 2, "match 1\r\nmatch 2\r\nmatch 3")
        val index = search(terminal, "match")
        terminal.write("\u001b[?1049h\u001b[Hmatch alt")

        index.update(terminal)

        assertEquals(listOf("1:0-1:5"), found(index))
    }

    @Test
    fun leavingTheAlternateScreenFindsTheMainScreenAgain() {
        val terminal = terminal(20, 2, "match 1\r\nmatch 2\r\nmatch 3\u001b[?1049h")
        val index = search(terminal, "match")
        terminal.write("\u001b[?1049l")

        index.update(terminal)

        assertEquals(listOf("0:0-0:5", "1:0-1:5", "2:0-2:5"), found(index))
    }

    @Test
    fun onlyTheNewestMatchesAreKept() {
        val terminal = terminal(100, 20, "a".repeat(SearchIndex.MAX_MATCHES + 1))

        val index = search(terminal, "a")

        assertEquals(SearchIndex.MAX_MATCHES, index.matches.size)
        assertEquals("0:1-0:2", found(index).first())
        assertTrue(index.isCapped)
    }

    @Test
    fun aSearchWithFewerMatchesIsNotCapped() {
        val terminal = terminal(100, 20, "a".repeat(SearchIndex.MAX_MATCHES))

        val index = search(terminal, "a")

        assertFalse(index.isCapped)
    }

    @Test
    fun theTextToSearchForMustNotBeEmpty() {
        assertFailsWith<IllegalArgumentException> { SearchQuery("", ignoreCase = false) }
    }

    @Test
    fun aSnapshotNumbersItsFirstLineAsTheSearchDoes() {
        val terminal = terminal(20, 2, "match 1\r\nmatch 2\r\nmatch 3\r\nend")
        val index = search(terminal, "match")

        val snapshot = terminal.snapshot(scrollOffset = 1)

        assertEquals(index.matches[1].firstLine, snapshot.firstLine)
    }

    @Test
    fun repetitiveTextTakesTimeLinearInItsLength() {
        // A naive search would compare the query at each of the 4 million cells.
        val terminal = terminal(2000, 2000, "a".repeat(2000 * 2000))
        val query = "a".repeat(1000) + "b"

        val cpuNanos =
            assertCompletesWithin(Duration.ofSeconds(60)) {
                val threads = ManagementFactory.getThreadMXBean()
                val start = threads.currentThreadCpuTime
                search(terminal, query)
                threads.currentThreadCpuTime - start
            }

        assertTrue(cpuNanos <= 10_000_000_000L, "${cpuNanos / 1_000_000} ms of CPU time")
    }
}
