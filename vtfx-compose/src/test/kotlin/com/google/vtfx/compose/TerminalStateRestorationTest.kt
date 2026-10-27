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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a [TerminalState] keeps while its terminal is out of the composition, as in an app with tabs
 * (docs/decisions/0095). The rows that the terminal shows are read from its accessibility nodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK], shadows = [ShadowToolAccessibilityManager::class])
// Real font metrics, so that the grid has as many rows as fit.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalStateRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = TerminalSession({})

    /** Whether the terminal tab is shown, rather than another tab. */
    private var terminalTab by mutableStateOf(true)

    /** The state of the terminal tab, while it is shown. */
    private lateinit var state: TerminalState

    /**
     * Shows the terminal tab, with its state kept by a `SaveableStateHolder` as navigation does.
     */
    private fun showTabs() {
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            if (terminalTab) {
                holder.SaveableStateProvider("terminal") {
                    state = rememberTerminalState()
                    Terminal(session, Modifier.size(300.dp, 200.dp), state = state)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun feed(text: String) {
        session.feed(text.toByteArray())
        composeRule.waitForIdle()
    }

    /** Scrolls the terminal back to show [text], through a search that it then ends. */
    private fun scrollTo(text: String) {
        composeRule.runOnIdle {
            state.search(text, ignoreCase = false)
            state.clearSearch()
        }
        composeRule.waitForIdle()
    }

    private fun switchTabsAndBack() {
        terminalTab = false
        composeRule.waitForIdle()
        terminalTab = true
        composeRule.waitForIdle()
    }

    private fun assertShown(text: String) {
        composeRule.onNode(hasText(text, substring = false)).assertExists()
    }

    private fun assertNotShown(text: String) {
        composeRule.onAllNodes(hasText(text, substring = false)).assertCountEquals(0)
    }

    @Test
    fun aTerminalBackFromAnotherTabShowsTheLinesItShowedBefore() {
        showTabs()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        scrollTo("old")

        switchTabsAndBack()

        assertShown("old")
    }

    @Test
    fun aTerminalBackFromAnotherTabShowsTheSameLinesAfterOutputWhileAway() {
        showTabs()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        scrollTo("old")
        terminalTab = false
        composeRule.waitForIdle()
        feed("\r\n".repeat(5) + "newer")

        terminalTab = true
        composeRule.waitForIdle()

        assertShown("old")
    }

    @Test
    fun aTerminalBackFromAnotherTabAtTheBottomShowsTheBottom() {
        showTabs()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        terminalTab = false
        composeRule.waitForIdle()
        feed("\r\n".repeat(5) + "newer")

        terminalTab = true
        composeRule.waitForIdle()

        assertShown("newer")
    }

    @Test
    fun aTerminalBackFromAnotherTabShowsTheBottomIfTheScrollbackWasCleared() {
        showTabs()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        scrollTo("old")
        terminalTab = false
        composeRule.waitForIdle()
        feed("\u001b[3J\u001b[2J\u001b[Hcleared")

        terminalTab = true
        composeRule.waitForIdle()

        assertShown("cleared")
    }

    @Test
    fun aTerminalBackFromAnotherTabKeepsTheSearchAndItsCurrentMatch() {
        showTabs()
        feed("foo\r\nbar foo\r\nfoo")
        composeRule.runOnIdle {
            state.search("foo", ignoreCase = false)
            state.findPrevious()
        }

        switchTabsAndBack()

        composeRule.runOnIdle {
            assertEquals(
                TerminalSearchResult(
                    matchCount = 3,
                    isMatchCountCapped = false,
                    currentMatchIndex = 1,
                ),
                state.searchResult,
            )
        }
    }

    @Test
    fun aStateKeepsTheSearchWhileItsTerminalIsAway() {
        val state = TerminalState()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            if (shown) Terminal(session, Modifier.size(300.dp, 200.dp), state = state)
        }
        feed("foo\r\nbar foo")
        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }
        shown = false
        composeRule.waitForIdle()

        shown = true
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                TerminalSearchResult(
                    matchCount = 2,
                    isMatchCountCapped = false,
                    currentMatchIndex = 1,
                ),
                state.searchResult,
            )
        }
    }

    @Test
    fun aRecreatedTerminalShowsTheLinesItShowedBefore() {
        // As across a configuration change, with the session kept outside the composable.
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            state = rememberTerminalState()
            Terminal(session, Modifier.size(300.dp, 200.dp), state = state)
        }
        composeRule.waitForIdle()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        scrollTo("old")

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertShown("old")
    }

    @Test
    fun aStateRestoredWithAnotherSessionShowsTheBottom() {
        // As after the process was killed: the new session has the same lines and numbers.
        var session by mutableStateOf(session)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            state = rememberTerminalState()
            Terminal(session, Modifier.size(300.dp, 200.dp), state = state)
        }
        composeRule.waitForIdle()
        feed("old\r\n" + "\r\n".repeat(40) + "new")
        scrollTo("old")
        // Resized as the first one was, so that its lines are numbered in the same generation.
        val newSession = TerminalSession({})
        newSession.resize(session.size().first, session.size().second)
        newSession.feed(("old\r\n" + "\r\n".repeat(40) + "new").toByteArray())
        session = newSession

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertNotShown("old")
    }

    @Test
    fun aStateRestoredWithAnotherSessionSearchesItForTheSameText() {
        var session by mutableStateOf(session)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            state = rememberTerminalState()
            Terminal(session, Modifier.size(300.dp, 200.dp), state = state)
        }
        composeRule.waitForIdle()
        feed("foo")
        composeRule.runOnIdle { state.search("foo", ignoreCase = false) }
        // Resized as the first one was, so that its lines are numbered in the same generation.
        val newSession = TerminalSession({})
        newSession.resize(session.size().first, session.size().second)
        newSession.feed("foo\r\nfoo".toByteArray())
        session = newSession

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                TerminalSearchResult(
                    matchCount = 2,
                    isMatchCountCapped = false,
                    currentMatchIndex = 1,
                ),
                state.searchResult,
            )
        }
    }
}
