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

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Checks that the adapter delegates each call as docs/design/ime-contract.md section 6 specifies.
 * The editing model itself is tested in vtfx-input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class TerminalInputConnectionTest {
    private val sent = StringBuilder()
    private val session = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
    private val view = View(RuntimeEnvironment.getApplication())
    private var pastes = 0
    private var changes = 0
    private val connection =
        TerminalInputConnection(
            view = view,
            session = session,
            keys = KeyEventHandler(session),
            cursorAnchor = CursorAnchorReporter(view, session) { null },
            onPaste = { pastes++ },
            onEditorChanged = { changes++ },
        )

    @Test
    fun editorInfoFollowsTheContract() {
        session.edit { it.commitText("ab") }
        val info = EditorInfo()
        session.edit { TerminalInputConnection.configure(info, it) }
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType)
        assertEquals(
            EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            info.imeOptions,
        )
        assertEquals(2, info.initialSelStart)
        assertEquals(2, info.initialSelEnd)
        assertEquals("ab", info.getInitialTextBeforeCursor(10, 0).toString())
    }

    @Test
    fun directInputAsksForPlainCharacters() {
        val info = EditorInfo()
        session.edit { TerminalInputConnection.configure(info, it, directInput = true) }
        assertEquals(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            info.inputType,
        )
        assertEquals(
            EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            info.imeOptions,
        )
    }

    @Test
    fun editsAreDelegated() {
        assertTrue(connection.beginBatchEdit())
        assertTrue(connection.setComposingText("にほ", 1))
        assertTrue(connection.commitText("日本", 1))
        assertFalse(connection.endBatchEdit())
        assertEquals("日本", sent.toString())
        assertTrue(connection.replaceText(0, 2, "二本", 1, null))
        assertFalse(connection.replaceText(0, 9, "x", 1, null))
        assertTrue(connection.deleteSurroundingText(1, 0))
        assertEquals("日本\u007f\u007f二本\u007f", sent.toString())
    }

    @Test fun editsWithoutReplaceTextAreDelegated() = assertEditsWithoutReplaceText()

    /**
     * The edits of IMEs before API 34, which correct text without `replaceText`
     * (docs/design/ime-contract.md section 6.1). They send the same on every version.
     */
    private fun assertEditsWithoutReplaceText() {
        assertTrue(connection.beginBatchEdit())
        assertTrue(connection.setComposingText("にほ", 1))
        assertTrue(connection.commitText("日本", 1))
        assertFalse(connection.endBatchEdit())
        assertEquals("日本", sent.toString())
        connection.beginBatchEdit()
        assertTrue(connection.setComposingRegion(0, 2))
        assertTrue(connection.commitText("二本", 1))
        connection.endBatchEdit()
        connection.beginBatchEdit()
        assertTrue(connection.setSelection(1, 2))
        assertTrue(connection.commitText("x", 1))
        connection.endBatchEdit()
        assertTrue(connection.deleteSurroundingText(1, 0))
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("日本\u007f\u007f二本\u007fx\u007f\u007f\r", sent.toString())
    }

    @Test
    fun editsDeleteOnlyTheCharactersThatTheGateSent() {
        // The editor keeps out what the gate drops; this checks that both follow the same rule.
        for (c in (0..0xff).map { it.toChar() } + '\ud800') {
            if (c == '\r' || c == '\n') continue
            val host = StringBuilder()
            val session = TerminalSession({ host.append(String(it, Charsets.UTF_8)) })
            session.edit { it.commitText("a${c}b") }
            val received = host.toString()
            host.setLength(0)
            assertTrue(session.edit { it.replaceText(0, 0, "X") })
            val deletes = received.codePointCount(0, received.length)
            assertEquals(
                "U+%04X".format(c.code),
                "\u007f".repeat(deletes) + "X$received",
                host.toString(),
            )
        }
    }

    @Test
    fun queriesSeeOnlyTheInputHistory() {
        connection.commitText("echo", 1)
        connection.setComposingText("か", 1)
        assertEquals("oか", connection.getTextBeforeCursor(2, 0).toString())
        assertEquals("", connection.getTextAfterCursor(10, 0).toString())
        assertNull(connection.getSelectedText(0))
        val surrounding = connection.getSurroundingText(3, 3, 0)
        assertEquals("hoか", surrounding.text.toString())
        assertEquals(3, surrounding.selectionStart)
        assertEquals(2, surrounding.offset)
        assertEquals("echoか", connection.getExtractedText(null, 0).text.toString())
        assertEquals(0, connection.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test
    fun onlySelectionsEndingAtTheCursorAreAccepted() {
        connection.commitText("abc", 1)
        assertFalse(connection.setSelection(0, 1))
        assertTrue(connection.setSelection(1, 3))
        assertEquals("bc", connection.getSelectedText(0).toString())
        val surrounding = connection.getSurroundingText(10, 10, 0)
        assertEquals("abc", surrounding.text.toString())
        assertEquals(1, surrounding.selectionStart)
        assertEquals(3, surrounding.selectionEnd)
        connection.commitText("x", 1)
        assertEquals("abc\u007f\u007fx", sent.toString())
    }

    @Test
    fun keyEventsAreSent() {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("\u001b[Da\r", sent.toString())
    }

    @Test
    fun editorActionIsEnter() {
        connection.commitText("ls", 1)
        assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertEquals("ls\r", sent.toString())
    }

    @Test
    fun onlyPasteIsAcceptedFromTheContextMenu() {
        assertTrue(connection.performContextMenuAction(android.R.id.paste))
        assertFalse(connection.performContextMenuAction(android.R.id.selectAll))
        assertEquals(1, pastes)
    }

    @Test
    fun pasteFromTheContextMenuDuringABatchEditFollowsTheComposingText() {
        val pasting =
            TerminalInputConnection(
                view = view,
                session = session,
                keys = KeyEventHandler(session),
                cursorAnchor = CursorAnchorReporter(view, session) { null },
                onPaste = { session.paste("x") },
                onEditorChanged = {},
            )
        pasting.setComposingText("か", 1)
        pasting.beginBatchEdit()
        assertTrue(pasting.performContextMenuAction(android.R.id.paste))
        pasting.endBatchEdit()
        assertEquals("かx", sent.toString())
    }

    @Test
    fun richContentAndPrivateCommandsAreRefused() {
        assertFalse(connection.performPrivateCommand("x", null))
        assertTrue(connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE))
    }

    @Test
    fun closingCommitsTheComposition() {
        connection.setComposingText("か", 1)
        connection.closeConnection()
        assertEquals("か", sent.toString())
    }

    @Test
    fun changesAreReportedOutsideBatchEdits() {
        connection.beginBatchEdit()
        connection.setComposingText("a", 1)
        assertEquals(0, changes)
        connection.endBatchEdit()
        assertEquals(1, changes)
        connection.setComposingText("ab", 1)
        assertEquals(2, changes)
    }

    @Test
    fun callsWithoutAnEffectAreNotReportedAsChanges() {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        connection.finishComposingText()
        connection.beginBatchEdit()
        connection.endBatchEdit()
        connection.setComposingText("a", 1)
        connection.setComposingText("a", 1)
        assertEquals(1, changes)
    }
}
