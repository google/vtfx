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

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import com.google.vtfx.session.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks which session is told that it has the focus: the one bound to the focused view, while its
 * window has the focus (docs/design/ime-contract.md section 5.1, mode 1004).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class TerminalInputViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()
    private val view = TerminalInputView(activity.get())

    /** Another view that can take the focus. */
    private val other =
        View(activity.get()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

    init {
        activity
            .get()
            .setContentView(
                LinearLayout(activity.get()).apply {
                    addView(view)
                    addView(other)
                }
            )
    }

    private class Host {
        val sent = StringBuilder()
        val session =
            TerminalSession({ sent.append(String(it, Charsets.UTF_8)) }).also {
                it.feed("\u001b[?1004h".toByteArray())
            }
        val display = TerminalDisplay(session)

        fun take(): String = sent.toString().also { sent.setLength(0) }
    }

    private val first = Host()
    private val second = Host()

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    /** Binds [host], focuses the view in a focused window, and commits "old" through the IME. */
    private fun focusAndType(host: Host): InputConnection {
        view.bind(host.display)
        assertTrue(view.requestFocus())
        view.dispatchWindowFocusChanged(true)
        val input = view.onCreateInputConnection(EditorInfo())!!
        input.commitText("old", 1)
        assertEquals("\u001b[Iold", host.take())
        return input
    }

    @Test
    fun losingTheWindowFocusReportsFocusOutAndResetsTheIme() {
        val input = focusAndType(first)
        view.dispatchWindowFocusChanged(false)
        assertEquals("\u001b[O", first.take())
        assertEquals("", input.getTextBeforeCursor(100, 0).toString())
        assertFalse(input.replaceText(0, 3, "new", 1, null))
        assertEquals("", first.take())
        view.dispatchWindowFocusChanged(true)
        assertEquals("\u001b[I", first.take())
    }

    @Test
    fun theSessionIsFocusedOnlyWithBothTheViewAndTheWindowFocused() {
        focusAndType(first)
        view.dispatchWindowFocusChanged(false)
        assertTrue(other.requestFocus())
        assertEquals("\u001b[O", first.take())
        view.dispatchWindowFocusChanged(true)
        assertEquals("", first.take())
        assertTrue(view.requestFocus())
        assertEquals("\u001b[I", first.take())
    }

    @Test
    fun losingTheWindowFocusWaitsForTheComposition() {
        val input = focusAndType(first)
        input.setComposingText("か", 1)
        view.dispatchWindowFocusChanged(false)
        // The history is reset at once; only the restart waits for the composition.
        assertEquals("か", input.getTextBeforeCursor(100, 0).toString())
        input.finishComposingText()
        assertEquals("\u001b[Oか", first.take())
        assertEquals("", input.getTextBeforeCursor(100, 0).toString())
    }

    @Test
    fun bindingAnotherSessionMovesTheFocus() {
        val input = focusAndType(first)
        view.bind(second.display)
        assertEquals("\u001b[O", first.take())
        assertEquals("\u001b[I", second.take())
        assertEquals("", input.getTextBeforeCursor(100, 0).toString())
    }

    @Test
    fun detachingReportsFocusOut() {
        focusAndType(first)
        activity.get().setContentView(View(activity.get()))
        assertEquals("\u001b[O", first.take())
    }

    /** Selects text on [host]'s display, as a drag would. */
    private fun select(host: Host): TerminalSelection =
        TerminalSelection(Cell(0, 0), Cell(2, 0)).also { host.display.selection = it }

    @Test
    fun modifierKeysAloneKeepTheSelection() {
        first.display.attach()
        val input = focusAndType(first)
        val selection = select(first)
        // Ctrl+Shift, as before the C of Ctrl+Shift+C: from the IME, which passes modifier keys on
        // with sendKeyEvent, and from a hardware keyboard.
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val ctrlShift = ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val keys =
            listOf(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl),
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, ctrlShift),
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl),
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
            )
        for (event in keys) assertTrue(input.sendKeyEvent(event))
        for (event in keys) view.dispatchKeyEvent(event)
        // Calls that an IME may make around a key without changing anything.
        input.beginBatchEdit()
        input.finishComposingText()
        input.endBatchEdit()
        input.clearMetaKeyStates(KeyEvent.META_SHIFT_ON)
        assertEquals(selection, first.display.selection)
        assertEquals("", first.take())
    }

    @Test
    fun inputDropsTheSelection() {
        first.display.attach()
        val input = focusAndType(first)
        select(first)
        input.setComposingText("か", 1)
        assertNull(first.display.selection)
        input.finishComposingText()
        assertEquals("か", first.take())

        // A key that leaves the edit buffer as it was.
        select(first)
        input.sendKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals("\u001b[D", first.take())
        assertNull(first.display.selection)

        // A Backspace with an empty edit buffer, which leaves it empty.
        select(first)
        assertEquals("", input.getTextBeforeCursor(100, 0).toString())
        input.deleteSurroundingText(1, 0)
        assertEquals("\u007f", first.take())
        assertNull(first.display.selection)
    }

    private fun key(action: Int, keyCode: Int, metaState: Int) =
        KeyEvent(0, 0, action, keyCode, 0, metaState)
}
