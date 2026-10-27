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

package com.google.vtfx.compose

import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the selection follows its text as the terminal changes (section 4 of compose-input.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class TerminalDisplaySelectionTest {
    private val clipboard = RecordingClipboard()

    private fun display(scrollbackLines: Int = 100): TerminalDisplay =
        TerminalDisplay(
                TerminalSession(
                    {},
                    columns = 20,
                    rows = 5,
                    limits = TerminalLimits(scrollbackLines = scrollbackLines),
                    clipboard = clipboard,
                )
            )
            .also { it.attach() }

    private fun TerminalDisplay.feed(text: String) = session.feed(text.toByteArray())

    @Test
    fun outputBelowTheSelectionKeepsIt() {
        val display = display()
        display.feed("hello world")
        val selected = display.selectionOf(Cell(0, 0), Cell(4, 0))
        display.selection = selected

        display.feed("\r\nmore")

        assertEquals(selected, display.selection)
    }

    @Test
    fun theSelectionFollowsItsTextIntoTheScrollback() {
        val display = display()
        display.feed("hello world\r\n")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7")
        val unused = display.copySelection()

        assertEquals(listOf("hello"), clipboard.copied)
    }

    @Test
    fun overwritingTheSelectedTextDropsIt() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.feed("\rjello")

        assertNull(display.selection)
    }

    @Test
    fun writingTheSameTextInAnotherStyleKeepsTheSelection() {
        val display = display()
        display.feed("hello world")
        val selected = display.selectionOf(Cell(0, 0), Cell(4, 0))
        display.selection = selected

        display.feed("\r\u001b[1;31mhello")

        assertEquals(selected, display.selection)
    }

    @Test
    fun changingTheLineOutsideTheSelectedColumnsKeepsTheSelection() {
        val display = display()
        display.feed("hello world")
        val selected = display.selectionOf(Cell(0, 0), Cell(4, 0))
        display.selection = selected

        display.feed("\u001b[1;7Hthere")

        assertEquals(selected, display.selection)
    }

    @Test
    fun concealingTheSelectedTextDropsIt() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.feed("\r\u001b[8mhello")

        assertNull(display.selection)
    }

    @Test
    fun aChangeThatScrollsIntoTheScrollbackBeforeTheRedrawDropsTheSelection() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        // In one output, so that the line is already in the scrollback when the display checks.
        display.feed("\rjello\r\n\r\n\r\n\r\n\r\n")

        assertNull(display.selection)
    }

    @Test
    fun scrollingTheDisplayKeepsTheSelection() {
        val display = display()
        display.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8")
        val selected = display.selectionOf(Cell(0, 2), Cell(0, 3))
        display.selection = selected

        display.scrollBy(2)

        assertEquals(selected, display.selection)
    }

    @Test
    fun clearingTheScrollbackDropsTheSelection() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.feed("\u001b[3J")

        assertNull(display.selection)
    }

    @Test
    fun switchingToTheAlternateScreenDropsTheSelection() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.feed("\u001b[?1049h")

        assertNull(display.selection)
    }

    @Test
    fun resizingDropsTheSelection() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.session.resize(30, 5)

        assertNull(display.selection)
    }

    @Test
    fun typingDropsTheSelection() {
        val display = display()
        display.feed("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))

        display.notifyEditorChanged()

        assertNull(display.selection)
    }

    @Test
    fun theLinesOfTheSelectionThatLeaveTheScrollbackAreCutOff() {
        val display = display(scrollbackLines = 3)
        display.feed("1\r\n2\r\n3\r\n4\r\n5")
        display.selection = display.selectionOf(Cell(0, 0), Cell(0, 2))

        // 1 and 2 leave the scrollback, which keeps 3 to 5.
        display.feed("\r\n6\r\n7\r\n8\r\n9\r\n10")
        val unused = display.copySelection()

        assertEquals(listOf("3"), clipboard.copied)
    }

    @Test
    fun aSelectionWhoseLinesAllLeaveTheScrollbackIsDropped() {
        val display = display(scrollbackLines = 3)
        display.feed("1\r\n2\r\n3\r\n4\r\n5")
        display.selection = display.selectionOf(Cell(0, 0), Cell(0, 1))

        display.feed("\r\n6\r\n7\r\n8\r\n9\r\n10")

        assertNull(display.selection)
    }

    @Test
    fun aSelectionOfAllCopiesTheLinesItCoveredBeforeTheOutputBelowIt() {
        val display = display()
        display.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7")
        display.selectAll()
        // A line below those that Select all covered, which scrolls them without changing them.
        display.feed("\r\n8")

        val unused = display.copySelection()

        assertEquals(listOf("1\n2\n3\n4\n5\n6\n7"), clipboard.copied)
    }
}
