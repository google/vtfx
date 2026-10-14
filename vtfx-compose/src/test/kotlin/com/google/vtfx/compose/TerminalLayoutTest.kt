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

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Checks how [Terminal] sizes itself within its constraints, and where its padding puts the grid
 * (docs/decisions/0032).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], qualifiers = "w1500dp-h1000dp-mdpi")
class TerminalLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    /** The sizes that the session reported, in order. */
    private val sizes = mutableListOf<Pair<Int, Int>>()

    /** Starts at 10 x 5, so that a resize to the default size is reported. */
    private val session =
        TerminalSession(
            {},
            columns = 10,
            rows = 5,
            listener =
                object : TerminalSessionListener {
                    override fun onResize(columns: Int, rows: Int) {
                        sizes += columns to rows
                    }
                },
        )

    private fun warnings() = ShadowLog.getLogsForTag("vtfx").filter { it.type == Log.WARN }

    @Test
    fun takes80By24CellsWhenTheConstraintsAllow() {
        composeRule.setContent { Terminal(session) }
        composeRule.waitForIdle()
        assertEquals(listOf(80 to 24), sizes)
    }

    @Test
    fun takes80By24CellsWithUnboundedConstraints() {
        composeRule.setContent {
            Box(Modifier.size(100.dp).wrapContentSize(unbounded = true)) { Terminal(session) }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(80 to 24), sizes)
    }

    @Test
    fun takesAsMuchAsTheConstraintsAllow() {
        composeRule.setContent {
            Box(Modifier.size(200.dp, 100.dp)) { Terminal(session, Modifier.testTag("terminal")) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal").assertWidthIsEqualTo(200.dp)
        composeRule.onNodeWithTag("terminal").assertHeightIsEqualTo(100.dp)
        val (columns, rows) = sizes.single()
        assertTrue("$columns x $rows", columns < 80 && rows < 24)
    }

    @Test
    fun takesTheSizeGiven() {
        composeRule.setContent {
            Terminal(session, Modifier.testTag("terminal").size(1200.dp, 50.dp))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal").assertWidthIsEqualTo(1200.dp)
        composeRule.onNodeWithTag("terminal").assertHeightIsEqualTo(50.dp)
        val (columns, rows) = sizes.single()
        assertTrue("$columns x $rows", columns > 80 && rows < 24)
    }

    @Test
    fun noWidthLogsAWarningAndKeepsTheSessionsSize() {
        composeRule.setContent { Terminal(session, Modifier.size(0.dp, 100.dp)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), sizes)
        assertEquals(1, warnings().size)
    }

    @Test
    fun noHeightLogsAWarningAndKeepsTheSessionsSize() {
        composeRule.setContent { Terminal(session, Modifier.size(100.dp, 0.dp)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), sizes)
        assertEquals(1, warnings().size)
    }

    @Test
    fun aSizeWithRoomLogsNoWarning() {
        composeRule.setContent { Terminal(session, Modifier.size(100.dp, 100.dp)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<ShadowLog.LogItem>(), warnings())
    }

    private val padding = PaddingValues(start = 30.dp, top = 20.dp, end = 10.dp, bottom = 5.dp)

    /** Returns the view that [Terminal] connects the IME to, under [view]. */
    private fun inputView(view: View): TerminalInputView =
        checkNotNull(findInputView(view)) { "no TerminalInputView" }

    private fun findInputView(view: View): TerminalInputView? {
        if (view is TerminalInputView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findInputView(view.getChildAt(i))?.let {
                return it
            }
        }
        return null
    }

    @Test
    fun theDefaultSizeAddsThePadding() {
        composeRule.setContent {
            Column {
                Terminal(
                    session,
                    Modifier.testTag("padded"),
                    style = TerminalStyle(padding = padding),
                )
                Terminal(TerminalSession({}), Modifier.testTag("plain"))
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(80 to 24), sizes)
        val padded = composeRule.onNodeWithTag("padded").getUnclippedBoundsInRoot()
        val plain = composeRule.onNodeWithTag("plain").getUnclippedBoundsInRoot()
        assertEquals(plain.width + 40.dp, padded.width)
        assertEquals(plain.height + 25.dp, padded.height)
    }

    @Test
    fun theColumnsAndRowsFitInsideThePadding() {
        val other = TerminalSession({}, columns = 10, rows = 5)
        composeRule.setContent {
            Column {
                Terminal(
                    session,
                    Modifier.size(500.dp, 300.dp),
                    style = TerminalStyle(padding = padding),
                )
                Terminal(other, Modifier.size(460.dp, 275.dp))
            }
        }
        composeRule.waitForIdle()
        val expected = other.snapshot().let { it.columns to it.rows }
        assertEquals(listOf(expected), sizes)
    }

    @Test
    fun theGridAndTheViewForTheImeAreInsideThePadding() {
        lateinit var root: View
        composeRule.setContent {
            root = LocalView.current
            Box(Modifier.padding(start = 7.dp)) {
                Terminal(
                    session,
                    Modifier.size(500.dp, 300.dp),
                    style = TerminalStyle(padding = padding),
                )
            }
        }
        composeRule.waitForIdle()
        session.feed("row".toByteArray())
        composeRule.onNodeWithText("row").assertLeftPositionInRootIsEqualTo(37.dp)
        composeRule.onNodeWithText("row").assertTopPositionInRootIsEqualTo(20.dp)
        // The IME places its candidates from the view's position (CursorAnchorInfo).
        composeRule.runOnIdle {
            val view = inputView(root)
            val rootLocation = IntArray(2).also { root.getLocationInWindow(it) }
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            assertEquals(37, location[0] - rootLocation[0])
            assertEquals(20, location[1] - rootLocation[1])
            assertEquals(460, view.width)
            assertEquals(275, view.height)
        }
    }

    @Test
    fun startAndEndFollowTheLayoutDirection() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Terminal(
                    session,
                    Modifier.size(500.dp, 300.dp),
                    style = TerminalStyle(padding = padding),
                )
            }
        }
        composeRule.waitForIdle()
        session.feed("row".toByteArray())
        // The end padding (10 dp) is on the left.
        composeRule.onNodeWithText("row").assertLeftPositionInRootIsEqualTo(10.dp)
    }

    @Test
    fun aTapInThePaddingFocusesTheTerminal() {
        lateinit var root: View
        composeRule.setContent {
            root = LocalView.current
            Terminal(
                session,
                Modifier.testTag("terminal").size(500.dp, 300.dp),
                style = TerminalStyle(padding = padding),
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(inputView(root).isFocused) }
        composeRule.onNodeWithTag("terminal").performTouchInput { click(Offset(10f, 150f)) }
        composeRule.runOnIdle { assertTrue(inputView(root).isFocused) }
    }
}
