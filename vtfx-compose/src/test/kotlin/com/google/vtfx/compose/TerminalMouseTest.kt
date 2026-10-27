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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.tripleClick
import androidx.compose.ui.unit.dp
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.testing.hyperlink
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

/** A mouse on the terminal (docs/decisions/0085). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalMouseTest {
    @get:Rule val composeRule = createComposeRule()

    private val sent = StringBuilder()
    private val clipboard = RecordingClipboard()
    private val copied
        get() = clipboard.copied

    private val session =
        TerminalSession({ sent.append(String(it, Charsets.UTF_8)) }, clipboard = clipboard)
    private val display = TerminalDisplay(session)
    private val toolbar = FakeTextToolbar()
    private val linkClicks = mutableListOf<TerminalLinkClick>()
    private val contextClicks = mutableListOf<Offset>()
    private var onContextClick: ((Offset) -> Unit)? by mutableStateOf(null)

    /** The size of a cell, from the size of the grid and the session's size. */
    private var cellWidth = 0f
    private var cellHeight = 0f

    private fun show(output: String) {
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(
                    display,
                    Modifier.size(300.dp, 200.dp),
                    onLinkClick = { linkClicks += it },
                    onContextClick = onContextClick,
                )
            }
        }
        composeRule.waitForIdle()
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
        val size = composeRule.onRoot().fetchSemanticsNode().size
        val snapshot = session.snapshot()
        cellWidth = size.width.toFloat() / snapshot.columns
        cellHeight = size.height.toFloat() / snapshot.rows
        sent.setLength(0)
    }

    /** The middle of the cell at [column] and [row], 0-based, for the first few columns. */
    private fun cell(column: Int, row: Int) =
        Offset((column + 0.5f) * cellWidth, (row + 0.5f) * cellHeight)

    private fun lines(count: Int) = (1..count).joinToString("\r\n") { "line $it" }

    private fun mouse(block: androidx.compose.ui.test.MouseInjectionScope.() -> Unit) {
        composeRule.onRoot().performMouseInput(block)
        composeRule.waitForIdle()
    }

    /** Runs [block] with [key] held down. */
    private fun withKey(key: Key, block: androidx.compose.ui.test.MouseInjectionScope.() -> Unit) {
        composeRule.onRoot().performMultiModalInput {
            key { keyDown(key) }
            mouse { block() }
            key { keyUp(key) }
        }
        composeRule.waitForIdle()
    }

    private fun drag(from: Offset, to: Offset) = mouse {
        moveTo(from)
        press()
        moveTo(to)
        release()
    }

    // --- Wheel ---

    @Test
    fun wheelScrollsTheScrollbackThreeLinesANotch() {
        show(lines(100))
        mouse { scroll(-1f) }
        assertEquals(3, display.scrollOffset)
        mouse { scroll(-2f) }
        assertEquals(9, display.scrollOffset)
        mouse { scroll(1f) }
        assertEquals(6, display.scrollOffset)
        assertEquals("", sent.toString())
    }

    @Test
    fun partsOfANotchAddUp() {
        show(lines(100))
        mouse { scroll(-0.5f) }
        assertEquals(0, display.scrollOffset)
        mouse { scroll(-0.5f) }
        assertEquals(3, display.scrollOffset)
    }

    @Test
    fun wheelOnTheAlternateScreenSendsArrowKeys() {
        show("\u001b[?1049h")
        mouse { scroll(-1f) }
        assertEquals("\u001b[A".repeat(3), sent.toString())
        sent.setLength(0)
        mouse { scroll(1f) }
        assertEquals("\u001b[B".repeat(3), sent.toString())
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun wheelOnTheAlternateScreenLeavesTheStickyModifiers() {
        show("\u001b[?1049h")
        composeRule.runOnIdle { session.stickyModifiers = KeyModifiers.of(ctrl = true) }
        mouse { scroll(-1f) }
        assertEquals("\u001b[A".repeat(3), sent.toString())
        assertEquals(KeyModifiers.of(ctrl = true), session.stickyModifiers)
    }

    @Test
    fun wheelIsReportedWhenTheHostAsks() {
        show("${lines(100)}\u001b[?1000h\u001b[?1006h")
        mouse {
            moveTo(cell(2, 1))
            scroll(-1f)
        }
        assertEquals("\u001b[<64;3;2M", sent.toString())
        sent.setLength(0)
        mouse { scroll(2f) }
        assertEquals("\u001b[<65;3;2M".repeat(2), sent.toString())
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun wheelWithShiftScrollsTheScrollbackWhileTheHostAsksForTheMouse() {
        show("${lines(100)}\u001b[?1000h\u001b[?1006h")
        withKey(Key.ShiftLeft) { scroll(-1f) }
        assertEquals(3, display.scrollOffset)
        assertEquals("", sent.toString())
    }

    // --- Selection ---

    @Test
    fun dragSelectsWithoutAMenuOrTheHost() {
        show("hello world")
        drag(cell(1, 0), cell(7, 0))
        assertEquals(display.selectionOf(Cell(1, 0), Cell(7, 0)), display.selection)
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
        assertEquals("", sent.toString())
    }

    @Test
    fun dragBackwardsSelectsFromThePress() {
        show("hello world")
        drag(cell(7, 0), cell(2, 0))
        assertEquals(display.selectionOf(Cell(7, 0), Cell(2, 0)), display.selection)
    }

    @Test
    fun aClickWithoutADragSelectsNothingAndDropsTheSelection() {
        show("hello world")
        display.selection = display.selectionOf(Cell(0, 0), Cell(4, 0))
        mouse { click(cell(2, 0)) }
        assertNull(display.selection)
    }

    @Test
    fun doubleClickSelectsAWord() {
        show("hello world")
        mouse { doubleClick(cell(7, 0)) }
        assertEquals(display.selectionOf(Cell(6, 0), Cell(10, 0)), display.selection)
    }

    @Test
    fun dragAfterADoubleClickExtendsByTheWord() {
        show("hello big world")
        mouse {
            click(cell(7, 0))
            moveTo(cell(7, 0))
            press()
            moveTo(cell(1, 0))
            release()
        }
        // From the end of "big" to the cell under the pointer.
        assertEquals(display.selectionOf(Cell(8, 0), Cell(1, 0)), display.selection)
    }

    @Test
    fun tripleClickSelectsTheLineAcrossAutowrap() {
        show("")
        val columns = session.snapshot().columns
        session.feed("first\r\n${"x".repeat(columns + 3)}\r\nlast".toByteArray())
        composeRule.waitForIdle()
        mouse { tripleClick(cell(1, 2)) }
        assertEquals(display.selectionOf(Cell(0, 1), Cell(columns - 1, 2)), display.selection)
        composeRule.runOnIdle { display.copySelection() }
        assertEquals(listOf("x".repeat(columns + 3)), copied)
    }

    @Test
    fun shiftClickExtendsTheSelection() {
        show("hello world")
        mouse { doubleClick(cell(1, 0)) }
        assertEquals(display.selectionOf(Cell(0, 0), Cell(4, 0)), display.selection)
        withKey(Key.ShiftLeft) { click(cell(8, 0)) }
        assertEquals(display.selectionOf(Cell(0, 0), Cell(8, 0)), display.selection)
    }

    @Test
    fun overwritingTheSelectionDuringADragEndsIt() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(3, 0))
        }
        assertNotNull(display.selection)
        session.feed("\rhallo".toByteArray())
        composeRule.waitForIdle()
        mouse {
            moveTo(cell(8, 0))
            release()
        }
        assertNull(display.selection)
    }

    @Test
    fun outputOutsideTheSelectionDuringADragLetsItGoOn() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(3, 0))
        }
        session.feed("\r\nmore".toByteArray())
        composeRule.waitForIdle()

        mouse {
            moveTo(cell(8, 0))
            release()
        }

        assertEquals(display.selectionOf(Cell(1, 0), Cell(8, 0)), display.selection)
    }

    @Test
    fun aDragFollowsTheTextThatOutputScrolls() {
        show(lines(100))
        mouse {
            moveTo(cell(1, 1))
            press()
            moveTo(cell(3, 1))
        }
        // Scrolls the screen up by a line: the press is now on the first row.
        session.feed("\r\nnew".toByteArray())
        composeRule.waitForIdle()

        mouse {
            moveTo(cell(3, 2))
            release()
        }

        assertEquals(display.selectionOf(Cell(1, 0), Cell(3, 2)), display.selection)
    }

    @Test
    fun outputBeforeTheFirstMoveOfADragLetsItSelect() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press()
        }
        session.feed("!".toByteArray())
        composeRule.waitForIdle()

        mouse {
            moveTo(cell(8, 0))
            moveTo(cell(9, 0))
            release()
        }

        assertEquals(display.selectionOf(Cell(1, 0), Cell(9, 0)), display.selection)
        assertEquals(emptyList<TerminalLinkClick>(), linkClicks)
    }

    @Test
    fun aDragEndedByOutputSelectsNothingOnLaterMoves() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(3, 0))
        }
        session.feed("\rhallo".toByteArray())
        composeRule.waitForIdle()
        mouse {
            moveTo(cell(8, 0))
            moveTo(cell(9, 0))
            release()
        }
        assertNull(display.selection)
    }

    @Test
    fun copyAfterADragAcrossAResizeDoesNotCrash() {
        show("hello world")
        mouse {
            moveTo(cell(15, 0))
            press()
        }
        composeRule.runOnIdle { session.resize(10, session.snapshot().rows) }
        composeRule.waitForIdle()
        mouse {
            moveTo(cell(18, 0))
            release()
        }
        assertNull(display.selection)
        composeRule.runOnIdle { display.copySelection() }
        assertEquals(emptyList<String>(), copied)
    }

    @Test
    fun holdingTheButtonIsNotALongPress() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press()
            advanceEventTime(1000)
        }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()
        mouse { release() }
        // A click, not the word that a long press of a finger selects.
        assertNull(display.selection)
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
    }

    @Test
    fun aDragDoesNotScrollAsASwipe() {
        show(lines(100))
        drag(cell(1, 1), cell(1, 5))
        assertEquals(0, display.scrollOffset)
        assertEquals(display.selectionOf(Cell(1, 1), Cell(1, 5)), display.selection)
    }

    @Test
    fun theMouseActsInThePaddingAtTheNearestCell() {
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(
                    display,
                    Modifier.size(300.dp, 200.dp),
                    style = TerminalStyle(padding = PaddingValues(20.dp)),
                )
            }
        }
        composeRule.waitForIdle()
        session.feed("${lines(100)}\u001b[?1000h\u001b[?1006h".toByteArray())
        composeRule.waitForIdle()
        sent.setLength(0)
        // The top left corner, in the padding: the first cell.
        mouse { click(Offset(2f, 2f)) }
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", sent.toString())
        session.feed("\u001b[?1000l".toByteArray())
        composeRule.waitForIdle()
        mouse { scroll(-1f) }
        assertEquals(3, display.scrollOffset)
        mouse { rightClick(Offset(2f, 2f)) }
        assertEquals(1, toolbar.menus.size)
    }

    // --- Clicks ---

    @Test
    fun aClickFocusesTheTerminal() {
        show("\u001b[?1004h")
        mouse { click(cell(1, 0)) }
        assertEquals("\u001b[I", sent.toString())
    }

    @Test
    fun clicksAreReportedWhenTheHostAsks() {
        show("\u001b[?1000h\u001b[?1006h")
        mouse { click(cell(2, 1)) }
        assertEquals("\u001b[<0;3;2M\u001b[<0;3;2m", sent.toString())
        sent.setLength(0)
        mouse { rightClick(cell(2, 1)) }
        assertEquals("\u001b[<2;3;2M\u001b[<2;3;2m", sent.toString())
        sent.setLength(0)
        mouse {
            moveTo(cell(2, 1))
            press(MouseButton.Tertiary)
            release(MouseButton.Tertiary)
        }
        assertEquals("\u001b[<1;3;2M\u001b[<1;3;2m", sent.toString())
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
        assertNull(display.selection)
    }

    @Test
    fun clicksWithModifiersAreReportedWithThem() {
        show("\u001b[?1000h\u001b[?1006h")
        withKey(Key.CtrlLeft) { click(cell(0, 0)) }
        assertEquals("\u001b[<16;1;1M\u001b[<16;1;1m", sent.toString())
    }

    @Test
    fun aClickThatTheHostGetsDropsTheSelection() {
        show("hello world\u001b[?1000h\u001b[?1006h")
        withKey(Key.ShiftLeft) {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(7, 0))
            release()
        }
        assertEquals(display.selectionOf(Cell(1, 0), Cell(7, 0)), display.selection)
        // At the press, which the host gets as input, as a tap drops it.
        mouse {
            moveTo(cell(2, 0))
            press()
        }
        assertNull(display.selection)
        mouse { release() }
        assertEquals("\u001b[<0;3;1M\u001b[<0;3;1m", sent.toString())
        assertNull(display.selection)
    }

    @Test
    fun shiftLeavesTheMouseToTheTerminalWhileTheHostAsks() {
        show("hello world\u001b[?1000h\u001b[?1006h")
        withKey(Key.ShiftLeft) {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(7, 0))
            release()
        }
        assertEquals(display.selectionOf(Cell(1, 0), Cell(7, 0)), display.selection)
        assertEquals("", sent.toString())
    }

    @Test
    fun theMiddleButtonDoesNothingWhileTheHostDoesNotAsk() {
        show("hello world")
        mouse {
            moveTo(cell(1, 0))
            press(MouseButton.Tertiary)
            release(MouseButton.Tertiary)
        }
        assertEquals("", sent.toString())
        assertNull(display.selection)
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
    }

    // --- Motion ---

    @Test
    fun dragsAreReportedByCellWhenTheHostAsksForButtonEvents() {
        show("\u001b[?1002h\u001b[?1006h")
        mouse {
            moveTo(cell(1, 0))
            press()
            moveBy(Offset(cellWidth * 0.2f, 0f))
            moveTo(cell(3, 0))
            moveTo(cell(3, 1))
            release()
        }
        assertEquals(
            "\u001b[<0;2;1M\u001b[<32;4;1M\u001b[<32;4;2M\u001b[<0;4;2m",
            sent.toString(),
        )
    }

    @Test
    fun hoverIsReportedByCellWhenTheHostAsksForAllMotion() {
        show("\u001b[?1003h\u001b[?1006h")
        mouse {
            moveTo(cell(1, 0))
            moveBy(Offset(cellWidth * 0.2f, 0f))
            moveTo(cell(2, 0))
        }
        assertEquals("\u001b[<35;2;1M\u001b[<35;3;1M", sent.toString())
    }

    @Test
    fun motionIsNotReportedWhenTheHostAsksOnlyForClicks() {
        show("\u001b[?1000h\u001b[?1006h")
        mouse {
            moveTo(cell(1, 0))
            press()
            moveTo(cell(3, 0))
            release()
            moveTo(cell(5, 0))
        }
        assertEquals("\u001b[<0;2;1M\u001b[<0;4;1m", sent.toString())
    }

    // --- Scrolled back ---

    @Test
    fun theMouseIsTheTerminalsWhileScrolledBack() {
        // Reports carry only a screen row, so a click on a row of the scrollback would reach the
        // host as a click on a row that the user does not see.
        show(
            "\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\ plain\r\n" +
                "${lines(100)}\u001b[?1000h\u001b[?1006h"
        )
        // To the top of the scrollback, where the link is on the first row.
        withKey(Key.ShiftLeft) { scroll(-100f) }
        assertTrue(display.scrollOffset > 0)
        mouse { click(cell(1, 0)) }
        assertEquals(
            TerminalLink.Explicit("link", hyperlink("https://example.com/a", null)),
            linkClicks.single().link,
        )
        drag(cell(6, 1), cell(3, 2))
        assertEquals(display.selectionOf(Cell(6, 1), Cell(3, 2)), display.selection)
        mouse { rightClick(cell(2, 1)) }
        assertEquals(1, toolbar.menus.size)
        mouse {
            moveTo(cell(2, 1))
            press(MouseButton.Tertiary)
            release(MouseButton.Tertiary)
        }
        assertEquals("", sent.toString())
    }

    @Test
    fun motionIsNotReportedWhileScrolledBack() {
        show("${lines(100)}\u001b[?1003h\u001b[?1006h")
        withKey(Key.ShiftLeft) { scroll(-1f) }
        mouse {
            moveTo(cell(1, 0))
            moveTo(cell(2, 0))
            moveTo(cell(3, 1))
        }
        assertEquals("", sent.toString())
    }

    @Test
    fun wheelDownToTheBottomHandsTheMouseBackToTheHost() {
        show("${lines(100)}\u001b[?1000h\u001b[?1006h")
        withKey(Key.ShiftLeft) { scroll(-2f) }
        assertEquals(6, display.scrollOffset)
        mouse {
            moveTo(cell(2, 1))
            scroll(1f)
        }
        // Scrolled, not reported, until the display is back at the bottom.
        assertEquals(3, display.scrollOffset)
        assertEquals("", sent.toString())
        mouse { scroll(1f) }
        assertEquals(0, display.scrollOffset)
        assertEquals("", sent.toString())
        mouse { scroll(1f) }
        assertEquals("\u001b[<65;3;2M", sent.toString())
        sent.setLength(0)
        mouse { click(cell(2, 1)) }
        assertEquals("\u001b[<0;3;2M\u001b[<0;3;2m", sent.toString())
    }

    @Test
    fun aPressKeepsItsOwnerUntilItsRelease() {
        show("${lines(100)}\u001b[?1000h\u001b[?1006h")
        // Pressed at the bottom, released scrolled back: the host gets the release.
        mouse {
            moveTo(cell(2, 1))
            press()
        }
        withKey(Key.ShiftLeft) { scroll(-1f) }
        assertEquals(3, display.scrollOffset)
        mouse { release() }
        assertEquals("\u001b[<0;3;2M\u001b[<0;3;2m", sent.toString())
        sent.setLength(0)
        // Pressed scrolled back, released at the bottom: the host gets neither.
        mouse {
            moveTo(cell(4, 1))
            press()
            scroll(1f)
        }
        assertEquals(0, display.scrollOffset)
        mouse { release() }
        assertEquals("", sent.toString())
    }

    @Test
    fun theAlternateScreenGetsTheMouseAfterScrollingBackOnTheMainScreen() {
        // The alternate screen has no scrollback to show, whatever the main screen's offset.
        show(lines(100))
        mouse { scroll(-1f) }
        assertEquals(3, display.scrollOffset)
        session.feed("\u001b[?1049h\u001b[?1000h\u001b[?1006h".toByteArray())
        composeRule.waitForIdle()
        sent.setLength(0)
        mouse { click(cell(2, 1)) }
        assertEquals("\u001b[<0;3;2M\u001b[<0;3;2m", sent.toString())
    }

    // --- Right click ---

    @Test
    fun rightClickShowsTheMenuAtTheClick() {
        show("hello world")
        mouse { rightClick(cell(1, 0)) }
        val menu = toolbar.menus.single()
        assertNull(menu.onCopyRequested)
        assertNotNull(menu.onPasteRequested)
        assertNotNull(menu.onSelectAllRequested)
        assertEquals(cell(1, 0).x, menu.rect.left, 0.01f)
        assertEquals(cell(1, 0).y, menu.rect.top, 0.01f)
    }

    @Test
    fun rightClickKeepsTheSelectionToCopy() {
        show("hello world")
        mouse { doubleClick(cell(1, 0)) }
        mouse { rightClick(cell(1, 0)) }
        val menu = toolbar.menus.single()
        composeRule.runOnIdle { menu.onCopyRequested!!() }
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun rightClickCallsOnContextClickInsteadOfTheMenu() {
        onContextClick = { contextClicks += it }
        show("hello world")
        mouse { doubleClick(cell(1, 0)) }
        mouse { rightClick(cell(2, 1)) }
        assertEquals(listOf(cell(2, 1)), contextClicks)
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
        // The selection stays for the app's menu to copy.
        assertNotNull(display.selection)
    }

    @Test
    fun onContextClickGetsThePositionInTheTerminal() {
        onContextClick = { contextClicks += it }
        composeRule.setContent {
            Terminal(
                display,
                Modifier.padding(start = 10.dp, top = 20.dp).size(300.dp, 200.dp),
                onContextClick = onContextClick,
            )
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        mouse { rightClick(Offset(15.dp.value * density, 30.dp.value * density)) }
        // Relative to the top left of the terminal, inside the padding.
        val position = contextClicks.single()
        assertEquals(5f * density, position.x, 0.5f)
        assertEquals(10f * density, position.y, 0.5f)
    }

    // --- Links ---

    @Test
    fun aClickOnALinkCallsBackWithTheMouseAndTheModifiers() {
        show("\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\ plain")
        mouse { click(cell(1, 0)) }
        // Another cell, or it would be a double click.
        withKey(Key.CtrlLeft) { click(cell(2, 0)) }
        mouse { click(cell(7, 0)) }
        val link = TerminalLink.Explicit("link", hyperlink("https://example.com/a", null))
        assertEquals(listOf(link, link), linkClicks.map { it.link })
        assertEquals(
            listOf(PointerType.Mouse, PointerType.Mouse),
            linkClicks.map { it.pointerType },
        )
        assertFalse(linkClicks[0].keyboardModifiers.isCtrlPressed)
        assertTrue(linkClicks[1].keyboardModifiers.isCtrlPressed)
    }

    @Test
    fun aDragFromALinkSelectsInsteadOfClicking() {
        show("\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\ plain")
        drag(cell(1, 0), cell(7, 0))
        assertEquals(emptyList<TerminalLinkClick>(), linkClicks)
        assertNotNull(display.selection)
    }

    @Test
    fun theHostGetsClicksOnLinksUnlessShiftIsHeld() {
        show(
            "\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\\u001b[?1000h\u001b[?1006h"
        )
        mouse { click(cell(1, 0)) }
        assertEquals(emptyList<TerminalLinkClick>(), linkClicks)
        assertEquals("\u001b[<0;2;1M\u001b[<0;2;1m", sent.toString())
        sent.setLength(0)
        withKey(Key.ShiftLeft) { click(cell(1, 0)) }
        assertEquals("", sent.toString())
        assertTrue(linkClicks.single().keyboardModifiers.isShiftPressed)
    }
}
