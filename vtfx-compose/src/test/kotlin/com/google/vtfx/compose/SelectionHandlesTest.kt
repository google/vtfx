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

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
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
class SelectionHandlesTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({}, clipboard = RecordingClipboard())
    private val display = TerminalDisplay(session)
    private val toolbar = FakeTextToolbar()
    private lateinit var state: TerminalState

    private fun show(output: String) {
        composeRule.setContent {
            state = rememberTerminalState()
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(display, Modifier.size(300.dp, 200.dp).testTag(TERMINAL), state = state)
            }
        }
        composeRule.waitForIdle()
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
    }

    private val cellWidth: Float
        get() =
            composeRule.onNodeWithTag(TERMINAL).fetchSemanticsNode().size.width.toFloat() /
                session.snapshot().columns

    /** Long-presses the cell at [column] of the first row, and lifts the finger. */
    private fun longPress(column: Int) {
        val x = (column + 0.5f) * cellWidth
        composeRule.onNodeWithTag(TERMINAL).performTouchInput { longClick(Offset(x, 5f)) }
        composeRule.waitForIdle()
    }

    /** Drags the handle tagged [tag] by [columns] cells to the right, and lifts the finger. */
    private fun dragHandle(tag: String, columns: Int) {
        val distance = columns * cellWidth
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            moveBy(Offset(distance, 0f))
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun longPress_showsTheStartHandle() {
        show("hello world")

        longPress(1)

        composeRule.onNodeWithTag(START_HANDLE_TAG).assertExists()
    }

    @Test
    fun longPress_showsTheEndHandle() {
        show("hello world")

        longPress(1)

        composeRule.onNodeWithTag(END_HANDLE_TAG).assertExists()
    }

    @Test
    fun selectAllOfTheState_showsNoHandles() {
        show("hello world")

        composeRule.runOnIdle { state.selectAll() }

        composeRule.onNodeWithTag(END_HANDLE_TAG).assertDoesNotExist()
    }

    @Test
    fun selectAllOfTheMenuOfALongPress_showsTheHandles() {
        show("hello world")
        longPress(1)
        val menu = toolbar.menus.single()

        composeRule.runOnIdle { menu.onSelectAllRequested!!() }

        composeRule.onNodeWithTag(END_HANDLE_TAG).assertExists()
    }

    @Test
    fun tap_hidesTheHandles() {
        show("hello world")
        longPress(1)

        composeRule.onNodeWithTag(TERMINAL).performTouchInput { click(Offset(5f, 100f)) }

        composeRule.onNodeWithTag(END_HANDLE_TAG).assertDoesNotExist()
    }

    @Test
    fun mouseSelection_hidesTheHandles() {
        show("hello world")
        longPress(1)
        val from = Offset(7.5f * cellWidth, 5f)
        val to = Offset(9.5f * cellWidth, 5f)

        composeRule.onNodeWithTag(TERMINAL).performMouseInput {
            moveTo(from)
            press()
            moveTo(to)
            release()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(END_HANDLE_TAG).assertDoesNotExist()
    }

    @Test
    fun outputThatScrollsTheStartOffTheDisplay_hidesTheStartHandle() {
        show("hello world")
        longPress(1)

        session.feed("\r\n".repeat(40).toByteArray())
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(START_HANDLE_TAG).assertDoesNotExist()
    }

    @Test
    fun draggingTheEndHandle_movesTheEndByCells() {
        show("hello world")
        longPress(1)

        dragHandle(END_HANDLE_TAG, 3)

        assertEquals(display.selectionOf(Cell(0, 0), Cell(7, 0)), display.selection)
    }

    @Test
    fun draggingTheStartHandle_movesTheStartByCells() {
        show("hello world")
        longPress(7)

        dragHandle(START_HANDLE_TAG, -4)

        assertEquals(display.selectionOf(Cell(10, 0), Cell(2, 0)), display.selection)
    }

    @Test
    fun draggingAHandle_hidesTheMenu() {
        show("hello world")
        longPress(1)
        val distance = 3 * cellWidth

        composeRule.onNodeWithTag(END_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(distance, 0f))
        }
        composeRule.waitForIdle()

        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun liftingADraggedHandle_showsTheMenuAgain() {
        show("hello world")
        longPress(1)

        dragHandle(END_HANDLE_TAG, 3)

        assertEquals(2, toolbar.menus.size)
    }

    @Test
    fun menuOfALongPress_coversTheHandlesBelowTheRow() {
        show("hello world")
        val handleHeight = with(composeRule.density) { 25.dp.toPx() }

        longPress(1)

        assertTrue(toolbar.menus.single().rect.height > handleHeight)
    }

    private companion object {
        const val TERMINAL = "terminal"
    }
}
