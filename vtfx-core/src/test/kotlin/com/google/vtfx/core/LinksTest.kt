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

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class LinksTest {
    private val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")

    private fun terminal(columns: Int, rows: Int, output: String) =
        Terminal(columns, rows) {}.also { it.write(output.toByteArray(Charsets.UTF_8)) }

    /** The text, URL, and spans (row:start-end) of each link on a screen showing [output]. */
    private fun links(
        output: String,
        patterns: List<LinkPattern> = listOf(LinkPattern.Urls),
        columns: Int = 40,
        rows: Int = 4,
    ): List<String> =
        LinkFinder(patterns).find(terminal(columns, rows, output).snapshot()).map { link ->
            "${link.text} -> ${link.url} " +
                link.spans.joinToString(",") { "${it.row}:${it.start}-${it.end}" }
        }

    private fun urls(text: String): List<String> =
        links(text, columns = 200, rows = 1).map { it.substringBefore(" -> ") }

    @Test
    fun findsUrlsInText() {
        assertEquals(
            listOf("https://example.com/a?b=c#d -> https://example.com/a?b=c#d 0:4-31"),
            links("see https://example.com/a?b=c#d now"),
        )
        assertEquals(listOf("HTTP://example.com"), urls("HTTP://example.com"))
        assertEquals(listOf("http://a", "https://b"), urls("http://a https://b"))
    }

    @Test
    fun urlsEndBeforeSentencePunctuationAndUnbalancedBrackets() {
        assertEquals(listOf("https://example.com/a"), urls("Go to https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), urls("(https://example.com/a)"))
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urls("https://en.wikipedia.org/wiki/Foo_(bar)."),
        )
        assertEquals(listOf("https://example.com/[1]"), urls("[https://example.com/[1]]"))
        assertEquals(listOf("https://example.com"), urls("<https://example.com>"))
        assertEquals(listOf("https://example.com"), urls("\"https://example.com\""))
        assertEquals(listOf("https://example.com"), urls("https://example.com、続き"))
    }

    @Test
    fun urlsNeedASchemeAtAWordStartAndSomethingAfterIt() {
        assertEquals(emptyList(), urls("xhttps://example.com"))
        assertEquals(emptyList(), urls("https://"))
        assertEquals(emptyList(), urls("https://..."))
        assertEquals(emptyList(), urls("ftp://example.com www.example.com"))
    }

    @Test
    fun manyClosingBracketsTakeLinearTime() {
        val text = "https://a" + ")".repeat(200_000)
        val links = LinkFinder(listOf(LinkPattern.Urls))
        val terminal = Terminal(2000, 101) {}
        terminal.write(text.toByteArray())
        val found = links.find(terminal.snapshot())
        assertEquals(listOf("https://a"), found.map { it.url })
    }

    @Test
    fun aPatternLinksToItsTemplate() {
        assertEquals(
            listOf("crbug/123 -> https://crbug.com/123 0:4-13"),
            links("Fix crbug/123.", listOf(crbug)),
        )
        assertEquals(emptyList(), links("xcrbug/123", listOf(crbug)))
    }

    @Test
    fun templateValuesArePercentEncoded() {
        val anything = LinkPattern(Regex("""go:(\S+)"""), "https://example.com/{1}?q={0}")
        assertEquals(
            listOf(
                "go:@evil/../x?y -> https://example.com/%40evil%2F..%2Fx%3Fy?q=go%3A%40evil%2F..%2Fx%3Fy 0:0-15"
            ),
            links("go:@evil/../x?y", listOf(anything)),
        )
        val unicode = LinkPattern(Regex("""w:(\S+)"""), "https://ja.wikipedia.org/wiki/{1}")
        assertEquals(
            listOf("https://ja.wikipedia.org/wiki/%E7%AB%AF%E6%9C%AB"),
            LinkFinder(listOf(unicode)).find(terminal(20, 1, "w:端末").snapshot()).map { it.url },
        )
    }

    @Test
    fun dotSegmentsAreKeptInTheUrl() {
        // Opening the URL removes them, which only drops segments of the template; it was decided
        // to allow that (docs/decisions/0019).
        fun urls(pattern: LinkPattern, text: String) =
            LinkFinder(listOf(pattern)).find(terminal(40, 1, text).snapshot()).map { it.url }
        val repo =
            LinkPattern(
                Regex("""([\w.-]+)/([\w.-]+)#(\d+)"""),
                "https://github.com/{1}/{2}/issues/{3}",
            )
        assertEquals(listOf("https://github.com/x/../issues/1"), urls(repo, "x/..#1"))
        assertEquals(
            listOf("https://github.com/socketio/socket.io/issues/1"),
            urls(repo, "socketio/socket.io#1"),
        )
    }

    @Test
    fun anUnmatchedOptionalGroupIsEmpty() {
        val pattern = LinkPattern(Regex("""bug(-x)?/(\d+)"""), "https://example.com/{2}{1}")
        assertEquals(
            listOf("https://example.com/7", "https://example.com/8-x"),
            LinkFinder(listOf(pattern)).find(terminal(40, 1, "bug/7 bug-x/8").snapshot()).map {
                it.url
            },
        )
    }

    @Test
    fun invalidTemplatesAreRejected() {
        val regex = Regex("""a(b)""")
        assertFailsWith<IllegalArgumentException> { LinkPattern(regex, "https://x/{2}") }
        assertFailsWith<IllegalArgumentException> { LinkPattern(regex, "https://x/{a}") }
        assertFailsWith<IllegalArgumentException> { LinkPattern(regex, "https://x/{1") }
        assertFailsWith<IllegalArgumentException> { LinkPattern(regex, "https://x/}") }
        val unused = LinkPattern(regex, "https://x/{0}/{1}")
    }

    @Test
    fun rowsJoinedByAutowrapAreMatchedTogether() {
        assertEquals(
            listOf("https://example.com/abc -> https://example.com/abc 0:4-10,1:0-10,2:0-7"),
            links("see https://example.com/abc", columns = 10, rows = 3),
        )
        // A line break, unlike autowrap, ends the text.
        assertEquals(
            listOf("https://ex -> https://ex 0:0-10"),
            links("https://ex\r\nample.com", columns = 10, rows = 3),
        )
    }

    @Test
    fun linesCutByTheDisplayAreMatchedWhole() {
        // The display used to match only the rows it shows of a line.
        val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")
        fun describe(links: List<FoundLink>) = links.map { link ->
            "${link.text} -> ${link.url} " +
                link.spans.joinToString(",") { "${it.row}:${it.start}-${it.end}" }
        }

        // Scrolled back so that the last row, "aa crbug/1", continues with "234" below the display.
        val scrolled =
            terminal(10, 3, "p\r\nq\r\naa crbug/1234\r\n1\r\n2\r\n3\r\n4")
                .snapshot(scrollOffset = 5)
        assertEquals("aa crbug/1", scrolled.line(2).text())
        assertEquals(
            listOf("crbug/1234 -> https://crbug.com/1234 2:3-10"),
            describe(LinkFinder(listOf(crbug)).find(scrolled)),
        )

        // The first row continues "123456789x", which has scrolled into the scrollback, so the URL
        // is not at a word start.
        val noUrl = terminal(10, 3, "123456789xhttps://a.example\r\nnext").snapshot()
        assertEquals("https://a.", noUrl.line(0).text())
        assertEquals(emptyList(), LinkFinder(listOf(LinkPattern.Urls)).find(noUrl))

        // A URL that starts above the display is a link where it shows.
        val url = terminal(10, 3, "see https://a.example/x\r\nnext").snapshot()
        assertEquals("//a.exampl", url.line(0).text())
        assertEquals(
            listOf("https://a.example/x -> https://a.example/x 0:0-10,1:0-3"),
            describe(LinkFinder(listOf(LinkPattern.Urls)).find(url)),
        )
    }

    @Test
    fun aLineLongerThanTheRowsKeptAroundTheDisplayIsNotMatchedFromWhereItIsCut() {
        // The display keeps a screen of rows above it, here 2: "https://c." and "example/aa". The
        // line starts a row before them with "123456789x", so the URL is not at a word start, but
        // the rows kept do not show it.
        val snapshot = terminal(10, 2, "123456789xhttps://c.example/aaaa\r\nnext").snapshot()
        assertEquals("aa", snapshot.line(0).text().trimEnd())
        assertEquals(emptyList(), LinkFinder(listOf(LinkPattern.Urls)).find(snapshot))
        // Below the display, the rows kept, "//c.exampl" and "e/aaaaaaaa", do not show where the
        // URL ends, so it is not linked to what they show.
        val below =
            terminal(10, 2, "p\r\n123 https://c.example/aaaaaaaaaaaa\r\n1\r\n2\r\n3\r\n4\r\n5")
                .snapshot(scrollOffset = 8)
        assertEquals("123 https:", below.line(1).text())
        assertEquals(emptyList(), LinkFinder(listOf(LinkPattern.Urls)).find(below))
    }

    @Test
    fun aWrappedLastRowWithNothingAfterItIsNotCut() {
        // Below the scroll region, autowrap on the bottom row stays on that row and leaves it
        // marked as wrapped, with no row after it. A URL reaching the end of that row used not
        // to become a link.
        val output = "\u001b[1;2r\u001b[4;1H" + "z".repeat(23) + "\u001b[4;1Hsee https://exa.com/"
        for (screen in listOf("", "\u001b[?1049h")) {
            val snapshot = terminal(20, 4, screen + output).snapshot()
            assertTrue(snapshot.line(3).isWrapped)
            assertEquals(
                listOf("https://exa.com/"),
                LinkFinder(listOf(LinkPattern.Urls)).find(snapshot).map { it.url },
            )
        }
    }

    @Test
    fun spansCoverWideCharacters() {
        val pattern = LinkPattern(Regex("""チケット(\d+)"""), "https://example.com/{1}")
        assertEquals(
            listOf("チケット12 -> https://example.com/12 0:2-12"),
            links("a チケット12", listOf(pattern)),
        )
    }

    @Test
    fun theCellLeftBlankBeforeAWrappedWideCharacterIsNotText() {
        // "ケ" does not fit in the last column, so autowrap leaves it blank. That cell used to
        // split the matched text.
        val pattern = LinkPattern(Regex("""チケット(\d+)"""), "https://example.com/{1}")
        assertEquals(
            listOf("チケット12 -> https://example.com/12 0:7-9,1:0-8"),
            links("1234567チケット12", listOf(pattern), columns = 10, rows = 2),
        )
        // A space that the host wrote there is text.
        assertEquals(emptyList(), links("1234567チ ケット12", listOf(pattern), columns = 10, rows = 2))
    }

    @Test
    fun hyperlinksWinOverMatches() {
        val output =
            "\u001b]8;;https://osc8.example\u001b\\https://shown.example\u001b]8;;\u001b\\ https://plain.example"
        assertEquals(
            listOf("https://plain.example"),
            LinkFinder(listOf(LinkPattern.Urls)).find(terminal(60, 1, output).snapshot()).map {
                it.url
            },
        )
    }

    @Test
    fun aMatchThatAHyperlinkRemovesStillHidesTheMatchesItOverlaps() {
        // The URL covers the OSC 8 hyperlink, and crbug/123 lost to the URL before that.
        val output =
            "\u001b]8;;https://osc8.example\u001b\\https://example.com/\u001b]8;;\u001b\\crbug/123 crbug/456"
        assertEquals(
            listOf("https://crbug.com/456"),
            LinkFinder(listOf(LinkPattern.Urls, crbug))
                .find(terminal(60, 1, output).snapshot())
                .map { it.url },
        )
    }

    @Test
    fun aMatchOverConcealedTextIsNotALink() {
        // SGR 8 hides the end of the URL, which would otherwise go to another site than shown.
        assertEquals(
            listOf("https://plain.example"),
            LinkFinder(listOf(LinkPattern.Urls))
                .find(
                    terminal(
                            80,
                            1,
                            "https://good.example\u001b[8m.evil.example\u001b[28m https://plain.example",
                        )
                        .snapshot()
                )
                .map { it.url },
        )
        // The same for the app's patterns: the display shows crbug/1.
        assertEquals(emptyList(), links("crbug/1\u001b[8m234\u001b[28m", listOf(crbug)))
    }

    @Test
    fun aMatchThatConcealedTextRemovesStillHidesTheMatchesItOverlaps() {
        val output = "https://example.com/\u001b[8mx\u001b[28mcrbug/123 crbug/456"
        assertEquals(
            listOf("https://crbug.com/456"),
            LinkFinder(listOf(LinkPattern.Urls, crbug))
                .find(terminal(60, 1, output).snapshot())
                .map { it.url },
        )
    }

    @Test
    fun earlierPatternsWinOverlaps() {
        val issue =
            LinkPattern(Regex("""https://example\.com/(\d+)"""), "https://issues.example/{1}")
        assertEquals(
            listOf("https://issues.example/5"),
            LinkFinder(listOf(issue, LinkPattern.Urls))
                .find(terminal(40, 1, "https://example.com/5").snapshot())
                .map { it.url },
        )
        assertEquals(
            listOf("https://example.com/5"),
            LinkFinder(listOf(LinkPattern.Urls, issue))
                .find(terminal(40, 1, "https://example.com/5").snapshot())
                .map { it.url },
        )
    }

    @Test
    fun overlapsAreDroppedAmongInterleavedMatches() {
        val hash = LinkPattern(Regex("""#(\d+)"""), "https://h.example/{1}")
        val range = LinkPattern(Regex("""(\d+)-(\d+)"""), "https://r.example/{1}/{2}")
        // hash takes #1 and #5; range's 1-2 overlaps #1, and 0-0 and 3-4 overlap nothing.
        assertEquals(
            listOf("0-0", "#1", "3-4", "#5"),
            LinkFinder(listOf(hash, range))
                .find(terminal(40, 1, "0-0 #1-2 3-4 #5").snapshot())
                .map { it.text },
        )
    }

    @Test
    fun manyMatchesTakeLinearTime() {
        // Dropping overlaps compared each match with all before it, in quadratic time. The bound
        // is far from both the time that took and the time this takes now, since
        // the machine may be busy with other tasks of the build.
        val pattern = LinkPattern(Regex("""#(\d+)"""), "https://example.com/{1}")
        val terminal = terminal(2048, 200, "#1 ".repeat(2048 * 200 / 3))
        val started = System.nanoTime()
        val found = LinkFinder(listOf(LinkPattern.Urls, pattern)).find(terminal.snapshot())
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(2048 * 200 / 3, found.size)
        assertTrue(millis < 10_000, "took $millis ms")
    }

    @Test
    fun linksFollowTheirLinesAcrossCalls() {
        val finder = LinkFinder(listOf(LinkPattern.Urls))
        val terminal = terminal(30, 3, "https://a.example\r\nplain")
        assertEquals(listOf(0), finder.find(terminal.snapshot()).map { it.spans.single().row })
        terminal.write("\r\n\r\n".toByteArray())
        assertEquals(emptyList(), finder.find(terminal.snapshot()).map { it.spans.single().row })
        terminal.write("\r\nhttps://b.example".toByteArray())
        val links = finder.find(terminal.snapshot())
        assertEquals(listOf("https://b.example"), links.map { it.url })
        assertTrue(links.single().contains(0, 2))
        assertFalse(links.single().contains(17, 2))
    }

    @Test
    fun patternsAreEqualByTheirDefinition() {
        assertEquals(crbug, LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}"))
        assertEquals(
            crbug.hashCode(),
            LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}").hashCode(),
        )
        assertNotEquals(
            crbug,
            LinkPattern(
                Regex("""\bcrbug/(\d+)""", RegexOption.IGNORE_CASE),
                "https://crbug.com/{1}",
            ),
        )
        assertNotEquals(crbug, LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://bugs.example/{1}"))
        assertNotEquals(crbug, LinkPattern.Urls)
    }
}
