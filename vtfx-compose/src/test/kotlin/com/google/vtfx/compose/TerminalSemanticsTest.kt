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

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Requests from TalkBack, an accessibility tool, unless a test says otherwise.
@Config(sdk = [SDK], shadows = [ShadowToolAccessibilityManager::class])
// Real font metrics, so that the grid has as many rows as fit.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalSemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})
    private val display = TerminalDisplay(session)

    @Test
    fun visibleTextIsExposedAndUpdatedRowByRow() {
        composeRule.setContent { Terminal(display, Modifier.size(300.dp, 200.dp)) }
        session.feed("hello\r\nworld".toByteArray())
        val hello = composeRule.onNode(hasText("hello", substring = false)).fetchSemanticsNode()
        val world = composeRule.onNode(hasText("world", substring = false)).fetchSemanticsNode()
        // Separate nodes, in reading order, so that a screen reader moves line by line.
        assertTrue(hello.boundsInRoot.bottom <= world.boundsInRoot.top)
        // Where the rows are drawn: the first at the top of the terminal.
        assertEquals(0f, hello.boundsInRoot.top)
        session.feed("\u001b[2J\u001b[Hbye".toByteArray())
        composeRule.onNode(hasText("bye", substring = false)).assertExists()
        composeRule.onAllNodes(hasText("hello")).assertCountEquals(0)
    }

    @Test
    fun concealedTextIsNotExposed() {
        composeRule.setContent { Terminal(display, Modifier.size(300.dp, 200.dp)) }
        session.feed("visible\u001b[8mhidden\u001b[28m!".toByteArray())
        composeRule.onNode(hasText("visible      !", substring = false)).assertExists()
        composeRule.onAllNodes(hasText("hidden", substring = true)).assertCountEquals(0)
    }

    @Test
    fun scrolledBackTextIsExposed() {
        composeRule.setContent { Terminal(display, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        session.feed(("old\r\n" + "\r\n".repeat(40) + "new").toByteArray())
        composeRule.onAllNodes(hasText("old")).assertCountEquals(0)
        composeRule.runOnIdle { display.scrollBy(1000) }
        composeRule.onNode(hasText("old", substring = false)).assertExists()
    }

    @Test
    fun rowsReportTheirOwnBoundsAndATapReachesTheGrid() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            Terminal(display, Modifier.size(300.dp, 200.dp))
        }
        session.feed("a\r\nb".toByteArray())
        val a = composeRule.onNode(hasText("a", substring = false))
        val b = composeRule.onNode(hasText("b", substring = false)).fetchSemanticsNode()
        // What a screen reader is told, not extended to a minimum touch target, which would make
        // the rows overlap and the focus land on the wrong row.
        fun reportedBounds(id: Int) =
            Rect().also {
                view.accessibilityNodeProvider
                    .createAccessibilityNodeInfo(id)!!
                    .getBoundsInScreen(it)
            }
        val aBounds = reportedBounds(a.fetchSemanticsNode().id)
        val bBounds = reportedBounds(b.id)
        assertEquals(a.fetchSemanticsNode().boundsInWindow.height, aBounds.height().toFloat(), 1f)
        assertEquals(aBounds.bottom, bBounds.top)
        // A screen reader's double tap on a row is a tap there.
        a.performClick()
        composeRule.waitForIdle()
        assertTrue((display.inputHost as TerminalInputView).isFocused)
    }

    @Test
    fun rowsAreShownOnlyToAccessibilityTools() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            Terminal(display, Modifier.size(300.dp, 200.dp))
        }
        session.feed("secret".toByteArray())
        val row = composeRule.onNode(hasText("secret", substring = false)).fetchSemanticsNode()
        val provider = view.accessibilityNodeProvider
        // The host may show secrets, so other services than accessibility tools, such as TalkBack,
        // do not see the rows (docs/decisions/0015).
        assertTrue(provider.createAccessibilityNodeInfo(row.id)!!.isAccessibilityDataSensitive)
        ShadowToolAccessibilityManager.requestsFromTool = false
        try {
            assertNull(provider.createAccessibilityNodeInfo(row.id))
        } finally {
            ShadowToolAccessibilityManager.requestsFromTool = true
        }
    }

    @Test
    fun sessionOutlivesTheComposable() {
        // As across a configuration change: the composable goes away and comes back.
        // Each composition has a display of its own, as with Terminal(session).
        var shown by mutableStateOf(true)
        val displays = mutableListOf<TerminalDisplay>()
        composeRule.setContent {
            if (shown) {
                val display = remember { TerminalDisplay(session).also { displays += it } }
                Terminal(display, Modifier.size(300.dp, 200.dp))
            }
        }
        session.feed("before".toByteArray())
        composeRule.waitForIdle()
        shown = false
        composeRule.waitForIdle()
        assertNull(displays.single().inputHost)
        session.feed("\r\nwhile away".toByteArray())
        shown = true
        composeRule.waitForIdle()
        composeRule.onNode(hasText("before", substring = false)).assertExists()
        composeRule.onNode(hasText("while away", substring = false)).assertExists()
        composeRule.onNode(hasText("before", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals(2, displays.size)
        assertTrue(displays.last().inputHost is TerminalInputView)
    }

    @Test
    fun tapFocusesTheInputView() {
        composeRule.setContent { Terminal(display, Modifier.size(300.dp, 200.dp)) }
        session.feed("x".toByteArray())
        assertNull(display.inputHost)
        composeRule.onNode(hasText("x")).performClick()
        composeRule.waitForIdle()
        val view = display.inputHost as TerminalInputView
        assertTrue(view.isFocused)
        assertTrue(view.onCheckIsTextEditor())
    }
}
