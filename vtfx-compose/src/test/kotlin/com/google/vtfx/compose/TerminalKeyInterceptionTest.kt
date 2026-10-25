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

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks that an app takes hardware keys from a terminal for its own shortcuts: before the IME with
 * `onPreImeKeyEvent` of [Terminal] (docs/decisions/0085), and after it with Compose's
 * `onPreviewKeyEvent` on an ancestor (docs/decisions/0087).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalKeyInterceptionTest {
    @get:Rule val composeRule = createComposeRule()

    private val sent = StringBuilder()
    private val session = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })

    /** The types of the key events that the app took. */
    private val taken = mutableListOf<KeyEventType>()
    private lateinit var root: View

    /** Shows a focused terminal in an app that takes Ctrl+Shift+C if [takesShortcut]. */
    private fun show(takesShortcut: Boolean) {
        composeRule.setContent {
            root = LocalView.current.rootView
            Box(
                Modifier.onPreviewKeyEvent { event ->
                    val take =
                        takesShortcut &&
                            event.isCtrlPressed &&
                            event.isShiftPressed &&
                            event.key == Key.C
                    if (take) taken += event.type
                    take
                }
            ) {
                Terminal(session, Modifier.size(300.dp, 200.dp))
            }
        }
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.waitForIdle()
        sent.setLength(0)
    }

    /** Presses and releases a hardware key, dispatched from the root as the window does. */
    private fun press(keyCode: Int, metaState: Int = 0) {
        composeRule.runOnIdle {
            root.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            root.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        }
        composeRule.waitForIdle()
    }

    private fun pressCtrlShiftC() = press(KeyEvent.KEYCODE_C, CTRL_SHIFT)

    /** The key events that the app's pre-IME hook saw, as (action, repeat count). */
    private val seenPreIme = mutableListOf<Pair<Int, Int>>()

    /**
     * Shows a focused terminal whose pre-IME hook takes Ctrl+Shift+C, and a second terminal above
     * it, unfocused, whose hook takes every key.
     */
    private fun showWithHook() {
        val other = TerminalSession({})
        composeRule.setContent {
            root = LocalView.current.rootView
            Column {
                Terminal(
                    other,
                    Modifier.size(300.dp, 100.dp),
                    onPreImeKeyEvent = {
                        seenPreIme += -1 to -1
                        true
                    },
                )
                Terminal(
                    session,
                    Modifier.testTag("terminal").size(300.dp, 100.dp),
                    onPreImeKeyEvent = { event ->
                        val native = event.nativeKeyEvent
                        seenPreIme += native.action to native.repeatCount
                        event.isCtrlPressed && event.isShiftPressed && event.key == Key.C
                    },
                )
            }
        }
        composeRule.onNodeWithTag("terminal").performTouchInput { click(center) }
        composeRule.waitForIdle()
        sent.setLength(0)
    }

    /**
     * Dispatches [event] as the window does: to the views before the IME, then, unless consumed, to
     * the IME, which takes it if [imeTakesKeys] (as Gboard does with a physical keyboard), then to
     * the views again.
     */
    private fun dispatchThroughIme(event: KeyEvent, imeTakesKeys: Boolean) {
        composeRule.runOnIdle {
            if (!root.dispatchKeyEventPreIme(event) && !imeTakesKeys) root.dispatchKeyEvent(event)
        }
        composeRule.waitForIdle()
    }

    private fun pressThroughIme(keyCode: Int, metaState: Int, imeTakesKeys: Boolean) {
        dispatchThroughIme(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
            imeTakesKeys,
        )
        dispatchThroughIme(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState), imeTakesKeys)
    }

    @Test
    fun thePreImeHookSeesKeysThatTheImeTakes() {
        showWithHook()
        pressThroughIme(KeyEvent.KEYCODE_C, CTRL_SHIFT, imeTakesKeys = true)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to 0, KeyEvent.ACTION_UP to 0), seenPreIme)
    }

    @Test
    fun keysThatThePreImeHookConsumesDoNotReachTheSession() {
        showWithHook()
        pressThroughIme(KeyEvent.KEYCODE_C, CTRL_SHIFT, imeTakesKeys = false)
        assertEquals("", sent.toString())
    }

    @Test
    fun keysThatThePreImeHookPassesOnReachTheSession() {
        showWithHook()
        pressThroughIme(KeyEvent.KEYCODE_C, CTRL, imeTakesKeys = false)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to 0, KeyEvent.ACTION_UP to 0), seenPreIme)
        assertEquals("\u0003", sent.toString())
    }

    @Test
    fun thePreImeHookSeesRepeatedPresses() {
        showWithHook()
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0)
        dispatchThroughIme(down, imeTakesKeys = false)
        dispatchThroughIme(KeyEvent.changeTimeRepeat(down, 0, 1), imeTakesKeys = false)
        dispatchThroughIme(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), imeTakesKeys = false)
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN to 0, KeyEvent.ACTION_DOWN to 1, KeyEvent.ACTION_UP to 0),
            seenPreIme,
        )
        assertEquals("aa", sent.toString())
    }

    @Test
    fun anUnfocusedTerminalsPreImeHookDoesNotSeeKeys() {
        showWithHook()
        pressThroughIme(KeyEvent.KEYCODE_A, 0, imeTakesKeys = false)
        assertEquals(false, seenPreIme.contains(-1 to -1))
        assertEquals("a", sent.toString())
    }

    @Test
    fun thePreImeHookFollowsRecomposition() {
        var takeKeys by mutableStateOf(false)
        composeRule.setContent {
            root = LocalView.current.rootView
            Terminal(
                session,
                Modifier.size(300.dp, 200.dp),
                onPreImeKeyEvent = if (takeKeys) ({ true }) else null,
            )
        }
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.waitForIdle()
        sent.setLength(0)
        pressThroughIme(KeyEvent.KEYCODE_A, 0, imeTakesKeys = false)
        takeKeys = true
        composeRule.waitForIdle()
        pressThroughIme(KeyEvent.KEYCODE_B, 0, imeTakesKeys = false)
        assertEquals("a", sent.toString())
    }

    @Test
    fun anAncestorTakesAKeyBeforeTheTerminal() {
        show(takesShortcut = true)
        pressCtrlShiftC()
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), taken)
        assertEquals("", sent.toString())
    }

    @Test
    fun keysNotTakenReachTheHost() {
        show(takesShortcut = true)
        press(KeyEvent.KEYCODE_A)
        assertEquals("a", sent.toString())
    }

    @Test
    fun onlyTheFocusedTerminalsAncestorSeesTheKey() {
        val other = TerminalSession({})
        val seen = mutableListOf<String>()
        composeRule.setContent {
            root = LocalView.current.rootView
            Column {
                Box(
                    Modifier.onPreviewKeyEvent {
                        seen += "first"
                        false
                    }
                ) {
                    Terminal(other, Modifier.size(300.dp, 100.dp))
                }
                Box(
                    Modifier.testTag("second").onPreviewKeyEvent {
                        seen += "second"
                        false
                    }
                ) {
                    Terminal(session, Modifier.size(300.dp, 100.dp))
                }
            }
        }
        composeRule.onNodeWithTag("second").performTouchInput { click(center) }
        composeRule.waitForIdle()
        press(KeyEvent.KEYCODE_A)
        assertEquals(listOf("second", "second"), seen)
        assertEquals("a", sent.toString())
    }

    @Test
    fun withoutTheAncestorTheShortcutReachesTheHost() {
        show(takesShortcut = false)
        pressCtrlShiftC()
        assertEquals("\u0003", sent.toString())
    }

    @Test
    fun theKittyKeyboardProtocolGetsShiftWithTheShortcut() {
        session.feed("\u001b[>1u".toByteArray())
        show(takesShortcut = false)
        pressCtrlShiftC()
        // The character key reports Shift, which the protocol sends with the key in lower case
        // (docs/decisions/0083).
        assertEquals("\u001b[99;6u", sent.toString())
    }

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val CTRL_SHIFT = CTRL or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
