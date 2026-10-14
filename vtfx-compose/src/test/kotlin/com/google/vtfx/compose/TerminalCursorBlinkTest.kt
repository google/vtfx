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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks when a blinking cursor shows (docs/decisions/0012). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalCursorBlinkTest {
    @get:Rule val composeRule = createComposeRule()

    private val sent = StringBuilder()
    private val session = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
    private lateinit var root: View

    /** Shows a focused terminal whose cursor blinks, with the clock advanced by the test. */
    private fun show() {
        composeRule.mainClock.autoAdvance = false
        val requester = FocusRequester()
        composeRule.setContent {
            root = LocalView.current.rootView
            Terminal(
                session,
                Modifier.size(300.dp, 200.dp).focusRequester(requester),
                style =
                    TerminalStyle(
                        colors = TerminalColors.Dark.copy(cursor = CURSOR),
                        cursorBlinking = true,
                    ),
            )
        }
        advance(FRAME_MILLIS)
        composeRule.runOnIdle { requester.requestFocus() }
        advance(FRAME_MILLIS)
    }

    private fun advance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    /** Whether the cursor is drawn, wherever it is: nothing else has its color. */
    private fun cursorShown(): Boolean {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return CURSOR.toArgb() in pixels
    }

    /** Presses and releases a hardware key, dispatched from the root as the window does. */
    private fun press(keyCode: Int) {
        composeRule.runOnIdle {
            root.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
            root.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0))
        }
        advance(FRAME_MILLIS)
    }

    private fun feed(output: String) {
        session.feed(output.toByteArray())
        advance(FRAME_MILLIS)
    }

    /**
     * Advances the clock to 900 ms into a cycle of the blinking, just before the cursor turns off:
     * it turns off at 0 ms, on at 500 ms, and off at 1000 ms.
     */
    private fun advanceToTheEndOfAnOnPhase() {
        var elapsed = 0L
        while (cursorShown()) {
            check(elapsed < 1000) { "the cursor does not blink" }
            advance(FRAME_MILLIS)
            elapsed += FRAME_MILLIS
        }
        advance(600)
        assertTrue(cursorShown())
        advance(300)
    }

    /** Checks that the cursor is on for 500 ms from now, and then blinks again from now. */
    private fun assertPhaseStartsAgain() {
        assertTrue(cursorShown())
        advance(300)
        assertTrue(cursorShown())
        advance(250)
        assertFalse(cursorShown())
        advance(500)
        assertTrue(cursorShown())
    }

    @Test
    fun typingKeepsTheCursorOnForAWholePhase() {
        show()
        advanceToTheEndOfAnOnPhase()
        // The host does not echo, so the cursor does not move.
        press(KeyEvent.KEYCODE_A)
        assertEquals("a", sent.toString())
        assertPhaseStartsAgain()
    }

    @Test
    fun outputThatMovesTheCursorKeepsItOnForAWholePhase() {
        show()
        advanceToTheEndOfAnOnPhase()
        feed("\r\n")
        assertPhaseStartsAgain()
    }

    @Test
    fun outputThatLeavesTheCursorWhereItWasDoesNotRestartThePhase() {
        show()
        advanceToTheEndOfAnOnPhase()
        // A change of the colors, and a redraw elsewhere that returns the cursor.
        feed("\u001b[31m\u001b[5;5Hx\u001b[1;1H")
        assertTrue(cursorShown())
        advance(300)
        assertFalse(cursorShown())
    }

    private companion object {
        const val FRAME_MILLIS = 16L

        /** A cursor color that nothing else on the screen has. */
        val CURSOR = Color(0xFFFF00FF)
    }
}
