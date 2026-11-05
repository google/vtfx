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

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Zooming into the display with a pinch, and what touches do on the zoomed display. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalZoomTest {
    @get:Rule val composeRule = createComposeRule()

    private val sent = StringBuilder()
    private val resizes = mutableListOf<Pair<Int, Int>>()
    private val session =
        TerminalSession(
            { sent.append(String(it, Charsets.UTF_8)) },
            clipboard = RecordingClipboard(),
            listener =
                object : TerminalSessionListener {
                    override fun onResize(columns: Int, rows: Int) {
                        resizes += Pair(columns, rows)
                    }
                },
        )
    private val display = TerminalDisplay(session)
    private val state = TerminalState()
    private val linkClicks = mutableListOf<TerminalLinkClick>()
    private var showTerminal by mutableStateOf(true)

    /** The terminal is 300 by 200 pixels (Robolectric's density is 1). */
    private fun show(output: String) {
        composeRule.setContent {
            if (showTerminal) {
                Terminal(
                    display,
                    Modifier.size(300.dp, 200.dp),
                    state,
                    linkPatterns = listOf(LinkPattern.Urls),
                    onLinkClick = { linkClicks += it },
                )
            }
        }
        composeRule.waitForIdle()
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
        sent.setLength(0)
    }

    private fun lines(count: Int) = (1..count).joinToString("\r\n") { "line $it" }

    private val cellWidth: Float
        get() = 300f / session.snapshot().columns

    /** Spreads two fingers apart around the middle of the terminal: about twice as large. */
    private fun pinchOut() {
        composeRule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(130f, 100f),
                end0 = Offset(80f, 100f),
                start1 = Offset(170f, 100f),
                end1 = Offset(220f, 100f),
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aPinchZoomsWithoutResizingTheTerminal() {
        show(lines(5))
        val size = session.size()
        val resizesBefore = resizes.toList()

        pinchOut()

        assertEquals(3.5f, state.zoom, 0.2f)
        // About the middle of the fingers, which stays where it was.
        assertEquals(150f, display.toGrid(Offset(150f, 100f)).x, 5f)
        assertEquals(size, session.size())
        composeRule.mainClock.advanceTimeBy(1000)
        assertEquals(resizesBefore, resizes)
        assertEquals("", sent.toString())
        assertEquals(null, display.selection)
    }

    @Test
    fun theZoomStaysFromOneToFourTimes() {
        show(lines(5))
        composeRule.runOnIdle { display.zoomBy(10f, Offset.Zero, Offset.Zero) }
        assertEquals(MAX_ZOOM, state.zoom)
        composeRule.onRoot().performTouchInput {
            pinch(Offset(20f, 100f), Offset(140f, 100f), Offset(280f, 100f), Offset(160f, 100f))
        }
        composeRule.waitForIdle()
        assertTrue("${state.zoom}", state.zoom < MAX_ZOOM)
        composeRule.runOnIdle { display.zoomBy(0.01f, Offset.Zero, Offset.Zero) }
        assertEquals(1f, state.zoom)
        assertEquals(Offset.Zero, display.pan)
    }

    @Test
    fun aSwipeMovesTheZoomedDisplayBeforeItScrolls() {
        show(lines(100))
        composeRule.runOnIdle {
            // Twice as large, showing the bottom right quarter.
            display.zoomBy(2f, Offset(300f, 200f), Offset(300f, 200f))
        }
        assertEquals(Offset(150f, 100f), display.pan)
        // 60 pixels down on the display: 30 of the grid, without scrolling.
        composeRule.onRoot().performTouchInput {
            down(Offset(100f, 50f))
            moveTo(Offset(100f, 70f))
            moveTo(Offset(100f, 110f))
            advanceEventTime(200)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(150f, display.pan.x, 0.01f)
        assertTrue("${display.pan}", display.pan.y in 69f..86f)
        assertEquals(0, display.scrollOffset)
        // Farther than the display can move: it reaches the top, and the rest scrolls back.
        repeat(2) {
            composeRule.onRoot().performTouchInput {
                down(Offset(100f, 10f))
                for (y in 30..190 step 20) moveTo(Offset(100f, y.toFloat()))
                advanceEventTime(200)
                up()
            }
            composeRule.waitForIdle()
        }
        assertEquals(0f, display.pan.y)
        assertTrue("${display.scrollOffset}", display.scrollOffset > 0)
        assertEquals("", sent.toString())
    }

    @Test
    fun aSidewaysSwipeMovesOnlyTheZoomedDisplay() {
        show(lines(100))
        composeRule.onRoot().performTouchInput {
            down(Offset(250f, 100f))
            for (x in 230 downTo 50 step 20) moveTo(Offset(x.toFloat(), 100f))
            advanceEventTime(200)
            up()
        }
        composeRule.waitForIdle()
        // Not zoomed, it does nothing.
        assertEquals(Offset.Zero, display.pan)
        assertEquals(0, display.scrollOffset)
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        composeRule.onRoot().performTouchInput {
            down(Offset(250f, 100f))
            for (x in 230 downTo 50 step 20) moveTo(Offset(x.toFloat(), 100f))
            advanceEventTime(200)
            up()
        }
        composeRule.waitForIdle()
        assertTrue("${display.pan}", display.pan.x > 50f)
        assertEquals(0f, display.pan.y, 0.01f)
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun aFlingMovesTheZoomedDisplayOnAfterTheFinger() {
        show(lines(100))
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        composeRule.onRoot().performTouchInput {
            down(Offset(250f, 100f))
            for (step in 1..5) {
                advanceEventTime(20)
                moveTo(Offset(250f - step * 20f, 100f))
            }
            up()
        }
        composeRule.waitForIdle()
        // The finger moved 100 pixels of the display, 50 of the grid.
        assertTrue("${display.pan}", display.pan.x > 50f)
        assertEquals(null, display.fling)
    }

    @Test
    fun aTapOnTheZoomedDisplayActsOnTheCellUnderIt() {
        show("go to https://example.com now")
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        // Column 3 shows at about 7 cells on the display, where column 7 would be unzoomed.
        composeRule.onRoot().performTouchInput { click(Offset(7f * cellWidth, 10f)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<TerminalLinkClick>(), linkClicks)
        // Column 7, in the link, shows at about 15 cells.
        composeRule.onRoot().performTouchInput { click(Offset(15f * cellWidth, 10f)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.com"), linkClicks.map { it.link.text })
    }

    @Test
    fun aLongPressOnTheZoomedDisplaySelectsTheWordUnderIt() {
        show("hello big world")
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        // Column 7, in "big", at 15 cells on the display.
        composeRule.onRoot().performTouchInput { longClick(Offset(15f * cellWidth, 10f)) }
        composeRule.waitForIdle()
        assertEquals(
            display.selectionOf(Cell(6, 0), Cell(8, 0), SelectionUnit.Word),
            display.selection,
        )
    }

    @Test
    fun typingMovesTheZoomedDisplayToTheCursor() {
        show("")
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        // The host's echo moves the cursor to the bottom right, out of the zoomed display.
        session.feed("\u001b[11;37H".toByteArray())
        composeRule.waitForIdle()
        assertEquals(Offset.Zero, display.pan)
        composeRule.runOnIdle { session.sendText("x") }
        composeRule.waitForIdle()
        val (column, row) = session.cursorPosition()
        val cell = display.toDisplay(Offset(column * cellWidth, row * 200f / session.size().second))
        assertTrue("$cell", cell.x in 0f..300f && cell.y in 0f..200f)
        assertTrue("${display.pan}", display.pan.x > 0f && display.pan.y > 0f)
    }

    @Test
    fun theStateKeepsTheZoomWhenTheTerminalComesBack() {
        show(lines(5))
        composeRule.runOnIdle { display.zoomBy(2f, Offset(300f, 200f), Offset(300f, 200f)) }
        showTerminal = false
        composeRule.waitForIdle()
        assertEquals(1f, state.zoom)
        showTerminal = true
        composeRule.waitForIdle()
        assertEquals(2f, state.zoom)
        assertEquals(Offset(150f, 100f), display.pan)
        val saved = SavedView.fromSaveable(checkNotNull(state.view()).toSaveable())
        assertEquals(2f, saved.zoom)
        assertEquals(Offset(150f, 100f), saved.pan)
        composeRule.runOnIdle { state.resetZoom() }
        assertEquals(1f, state.zoom)
        assertEquals(Offset.Zero, display.pan)
    }

    @Test
    fun twoFingersHeldStillDoNotSelect() {
        show("hello big world")
        composeRule.onRoot().performTouchInput {
            down(0, Offset(20f, 10f))
            down(1, Offset(120f, 10f))
            advanceEventTime(2000)
            moveTo(1, Offset(160f, 10f))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        assertEquals(null, display.selection)
        assertTrue("${state.zoom}", state.zoom > 1f)
    }

    @Test
    fun theHandlesShowWhereTheZoomedDisplayShowsTheirEnds() {
        show("hello big world")
        composeRule.runOnIdle { display.zoomBy(2f, Offset.Zero, Offset.Zero) }
        composeRule.onRoot().performTouchInput { longClick(Offset(15f * cellWidth, 10f)) }
        composeRule.waitForIdle()
        val selection = checkNotNull(display.selection)
        val metrics = CellMetrics(cellWidth, 200f / session.size().second, 0f)
        val tips = checkNotNull(handleTips(selection, display.displaySnapshot(), metrics))
        // The start of "big" at column 6, twice as far from the left on the display.
        assertEquals(12f * cellWidth, display.toDisplay(checkNotNull(tips.start)).x, 0.01f)
        composeRule.onNode(androidx.compose.ui.test.hasTestTag(START_HANDLE_TAG)).assertExists()
        // Moved 7 cells of the grid to the right, so that the start is off the display, its
        // handle goes, and the end, 2 cells further, stays.
        composeRule.runOnIdle { display.panBy(Offset(-14f * cellWidth, 0f)) }
        composeRule.waitForIdle()
        composeRule
            .onNode(androidx.compose.ui.test.hasTestTag(START_HANDLE_TAG))
            .assertDoesNotExist()
        composeRule.onNode(androidx.compose.ui.test.hasTestTag(END_HANDLE_TAG)).assertExists()
    }
}
