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

package com.google.vtfx.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Checks that the scrollback's [CompactLine] gives back the line it was made from. */
class CompactLineTest {
    private val red = CellStyle.Default.withForeground(TerminalColor.indexed(1))
    private val blueBackground = CellStyle.Default.withBackground(TerminalColor.indexed(4))

    private fun assertRoundTrip(line: Line) {
        val expanded = line.compact().expand()
        assertEquals(line.columns, expanded.columns)
        assertTrue(expanded.contentEquals(line), "content")
        assertEquals(line.isWrapped, expanded.isWrapped, "wrapped")
    }

    private fun Line.write(
        column: Int,
        text: String,
        style: CellStyle = CellStyle.Default,
        link: Int = 0,
    ) {
        var x = column
        text.codePoints().forEach { codePoint ->
            val width = CharWidth.of(codePoint)
            set(x, codePoint, width, style, link)
            x += width
        }
    }

    @Test
    fun blankLine() {
        assertRoundTrip(Line(10))
    }

    @Test
    fun uniformStyle() {
        val line = Line(10)
        line.write(0, "abc", red)
        assertRoundTrip(line)
    }

    @Test
    fun mixedStylesAndAnErasedBackgroundAtTheEnd() {
        val line = Line(10)
        line.write(0, "ab", red)
        line.write(2, "cd")
        line.erase(4, 10, blueBackground.erased())
        assertRoundTrip(line)
    }

    @Test
    fun clustersWideCharactersAndLinks() {
        val line = Line(10)
        line.write(0, "e")
        line.combine(0, 0x0301)
        line.write(1, "あ", link = 3)
        line.write(3, "x", red, link = 3)
        line.isWrapped = true
        assertRoundTrip(line)
        val links = mutableListOf<Int>()
        line.compact().forEachLink { links += it }
        assertEquals(listOf(3, 3, 3), links)
    }

    @Test
    fun clustersInSeveralCellsComeBackInTheirCells() {
        val line = Line(10)
        line.write(0, "abcdef")
        line.combine(1, 0x0301)
        line.combine(1, 0x0302)
        line.combine(4, 0x0308)
        line.write(6, "😀")
        line.combine(6, 0xFE0F)
        assertRoundTrip(line)
        val expanded = line.compact().expand()
        assertEquals("b\u0301\u0302", expanded.textAt(1))
        assertEquals("c", expanded.textAt(2))
        assertEquals("e\u0308", expanded.textAt(4))
        assertEquals("😀\uFE0F", expanded.textAt(6))
    }

    @Test
    fun clustersDoNotTakeAnObjectPerCell() {
        // Text in which every cell has a cluster, as in Indic scripts: the text is 4 bytes a
        // cell, the code point and the cluster's place 8 more, and an object per cell would take
        // several times that.
        val line = Line(120)
        for (column in 0 until 120) {
            line.write(column, "e")
            line.combine(column, 0x0301)
        }
        assertTrue(line.compact().estimatedBytes <= 120 * 14, "${line.compact().estimatedBytes}")
    }

    @Test
    fun underlineColorsComeBackInTheirCells() {
        val underlined = CellStyle.Default.withUnderline(UnderlineStyle.Curly)
        val line = Line(10)
        line.write(0, "ab", underlined)
        line.set(2, 'c'.code, 1, underlined, underlineColor = TerminalColor.indexed(1).bits)
        line.set(3, 'あ'.code, 2, underlined, underlineColor = TerminalColor.rgb(1, 2, 3).bits)
        assertRoundTrip(line)
        val expanded = line.compact().expand()
        assertEquals(0, expanded.underlineColorAt(1))
        assertEquals(TerminalColor.indexed(1).bits, expanded.underlineColorAt(2))
        assertEquals(TerminalColor.rgb(1, 2, 3).bits, expanded.underlineColorAt(4))
        assertEquals(0, expanded.underlineColorAt(5))
    }

    @Test
    fun underlineColorsTakeNoMemoryUnlessUsed() {
        val underlined = CellStyle.Default.withUnderline(UnderlineStyle.Single)
        val plain = Line(120)
        plain.write(0, "a".repeat(120), underlined)
        val colored = Line(120)
        colored.write(0, "a".repeat(120), underlined)
        colored.set(0, 'a'.code, 1, underlined, underlineColor = TerminalColor.indexed(1).bits)
        // A line whose colors were all erased does not keep the array either.
        val erased = Line(120)
        erased.set(0, 'a'.code, 1, underlined, underlineColor = TerminalColor.indexed(1).bits)
        erased.erase(0, 1, CellStyle.Default)
        erased.write(0, "a".repeat(120), underlined)
        assertEquals(
            plain.compact().estimatedBytes + 16 + 120 * 4,
            colored.compact().estimatedBytes,
        )
        assertEquals(plain.compact().estimatedBytes, erased.compact().estimatedBytes)
    }

    @Test
    fun blankEndIsNotStored() {
        val short = Line(200)
        short.write(0, "abc")
        val long = Line(200)
        long.write(0, "a".repeat(200))
        assertTrue(short.compact().estimatedBytes < long.compact().estimatedBytes / 10)
    }
}
