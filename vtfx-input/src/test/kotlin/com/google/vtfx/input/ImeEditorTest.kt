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

package com.google.vtfx.input

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ImeEditorTest {
    private val sent = mutableListOf<HostInput>()
    private var restarts = 0
    private val editor =
        ImeEditor(
            object : ImeEditor.Listener {
                override fun send(input: HostInput) {
                    sent += input
                }

                override fun restartInput() {
                    restarts++
                }
            }
        )

    private val enter = HostInput.Key(KeyStroke.Special(TerminalKey.Enter))

    private fun text(s: String) = HostInput.Text(s)

    private fun delete(n: Int) = HostInput.Delete(n)

    @Test
    fun composingTextIsNotSent() {
        editor.setComposingText("にほ")
        assertEquals(emptyList<HostInput>(), sent)
        assertEquals("にほ", editor.text)
        assertEquals(0, editor.composingStart)
        assertEquals(2, editor.composingEnd)
    }

    @Test
    fun commitSendsAndKeepsHistory() {
        editor.setComposingText("にほ")
        editor.commitText("日本")
        assertEquals(listOf<HostInput>(text("日本")), sent)
        assertEquals("日本", editor.text)
        assertEquals(-1, editor.composingStart)
    }

    @Test
    fun editsInABatchAreSentOnceAtTheEnd() {
        editor.commitText("Dont")
        sent.clear()
        editor.beginBatchEdit()
        editor.deleteSurroundingText(4, 0)
        editor.commitText("Don't ")
        assertEquals(emptyList<HostInput>(), sent)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(delete(1), text("'t ")), sent)
    }

    @Test
    fun nestedBatchesSendAtTheOutermostEnd() {
        editor.beginBatchEdit()
        editor.beginBatchEdit()
        editor.commitText("a")
        assertTrue(editor.endBatchEdit())
        assertEquals(emptyList<HostInput>(), sent)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(text("a")), sent)
        assertFalse(editor.endBatchEdit())
    }

    @Test
    fun replaceTextInTheHistorySendsTheDifference() {
        editor.commitText("ok ")
        sent.clear()
        assertTrue(editor.replaceText(2, 3, ". "))
        assertEquals(listOf<HostInput>(delete(1), text(". ")), sent)
    }

    @Test
    fun replaceTextOutsideTheBufferIsRejected() {
        editor.commitText("ok")
        sent.clear()
        assertFalse(editor.replaceText(1, 5, "x"))
        assertFalse(editor.replaceText(-1, 1, "x"))
        assertEquals(emptyList<HostInput>(), sent)
        assertEquals("ok", editor.text)
    }

    @Test
    fun deletingBeyondTheHistorySendsDelForTheRest() {
        editor.commitText("ab")
        sent.clear()
        editor.deleteSurroundingText(5, 0)
        assertEquals(listOf<HostInput>(delete(5)), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun deletionsBeyondTheHistoryComeBeforeNewText() {
        editor.commitText("abc")
        sent.clear()
        editor.beginBatchEdit()
        editor.deleteSurroundingText(5, 0)
        editor.commitText("z")
        editor.deleteSurroundingText(3, 0)
        editor.endBatchEdit()
        // "abc", 2 characters before it, "z", and 2 more.
        assertEquals(listOf<HostInput>(delete(7)), sent)
    }

    @Test
    fun deleteDoesNotSplitASurrogatePair() {
        editor.commitText("a😀")
        sent.clear()
        editor.deleteSurroundingText(1, 0)
        assertEquals(listOf<HostInput>(delete(1)), sent)
        assertEquals("a", editor.text)
    }

    @Test
    fun deleteInCodePointsCountsCodePoints() {
        editor.commitText("a😀b")
        sent.clear()
        editor.deleteSurroundingTextInCodePoints(2, 0)
        assertEquals(listOf<HostInput>(delete(2)), sent)
        assertEquals("a", editor.text)
    }

    @Test
    fun deleteRemovesComposingTextFirst() {
        editor.commitText("a")
        editor.setComposingText("かな")
        sent.clear()
        editor.deleteSurroundingText(1, 0)
        assertEquals(emptyList<HostInput>(), sent)
        assertEquals("aか", editor.text)
    }

    @Test
    fun reconvertingTheHistoryDeletesItUntilCommitted() {
        editor.commitText("漢字")
        sent.clear()
        assertTrue(editor.setComposingRegion(0, 2))
        assertEquals(listOf<HostInput>(delete(2)), sent)
        assertEquals(0, editor.composingStart)
        editor.commitText("感じ")
        assertEquals(listOf<HostInput>(delete(2), text("感じ")), sent)
    }

    @Test
    fun composingRegionMustEndAtTheCursor() {
        editor.commitText("abc")
        assertFalse(editor.setComposingRegion(0, 2))
        assertFalse(editor.setComposingRegion(0, 4))
        assertTrue(editor.setComposingRegion(2, 3))
        assertEquals(2, editor.composingStart)
    }

    @Test
    fun emptyComposingRegionFinishesTheComposition() {
        editor.setComposingText("a")
        assertTrue(editor.setComposingRegion(1, 1))
        assertEquals(listOf<HostInput>(text("a")), sent)
    }

    @Test
    fun selectionMustEndAtTheCursor() {
        editor.commitText("abc")
        assertTrue(editor.setSelection(3, 3))
        assertFalse(editor.setSelection(1, 1))
        assertFalse(editor.setSelection(0, 2))
        assertTrue(editor.setSelection(1, 3))
        assertEquals(1, editor.selectionStart)
        assertEquals("bc", editor.selectedText)
        assertEquals("a", editor.textBeforeCursor(10))
    }

    @Test
    fun commitReplacesTheSelection() {
        // Gboard's double-space period on the emulator (docs/decisions/0008).
        editor.commitText("ok ")
        sent.clear()
        editor.finishComposingText()
        assertTrue(editor.setSelection(2, 3))
        editor.commitText(". ")
        assertEquals(listOf<HostInput>(delete(1), text(". ")), sent)
        assertEquals("ok. ", editor.text)
        assertEquals(null, editor.selectedText)
    }

    @Test
    fun composingTextReplacesTheSelection() {
        editor.commitText("abc")
        sent.clear()
        editor.setSelection(1, 3)
        editor.setComposingText("x")
        assertEquals(listOf<HostInput>(delete(2)), sent)
        assertEquals("ax", editor.text)
        assertEquals(1, editor.composingStart)
    }

    @Test
    fun otherEditsCollapseTheSelectionFirst() {
        editor.commitText("abc")
        sent.clear()
        editor.setSelection(0, 3)
        editor.deleteSurroundingText(1, 0)
        assertEquals(listOf<HostInput>(delete(1)), sent)
        assertEquals("ab", editor.text)
        assertEquals(editor.cursor, editor.selectionStart)
    }

    @Test
    fun selectionIsRejectedDuringComposition() {
        editor.commitText("ab")
        editor.setComposingText("か")
        assertFalse(editor.setSelection(0, 3))
    }

    @Test
    fun committedNewlineIsSentAsEnterAndResets() {
        editor.commitText("ls\r\nx\ny")
        assertEquals(listOf<HostInput>(text("ls"), enter, text("x"), enter, text("y")), sent)
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun controlsThatAreNotSentAreNotKeptInTheHistory() {
        editor.commitText("a\u001bb\u007f\u009bc")
        assertEquals(listOf<HostInput>(text("abc")), sent)
        assertEquals("abc", editor.text)
        // Only the characters that reached the host are deleted.
        sent.clear()
        editor.replaceText(0, 0, "X")
        assertEquals(listOf<HostInput>(delete(3), text("Xabc")), sent)
    }

    @Test
    fun tabIsKeptInTheHistory() {
        editor.commitText("a\tb")
        assertEquals(listOf<HostInput>(text("a\tb")), sent)
        assertEquals("a\tb", editor.text)
    }

    @Test
    fun controlsAreRemovedFromTheComposingText() {
        editor.setComposingText("か\u0007")
        assertEquals("か", editor.composingText)
        editor.finishComposingText()
        assertEquals(listOf<HostInput>(text("か")), sent)
    }

    @Test
    fun controlsAreRemovedFromReplacements() {
        editor.commitText("ab")
        sent.clear()
        editor.replaceText(1, 2, "\u001bc")
        assertEquals(listOf<HostInput>(delete(1), text("c")), sent)
        assertEquals("ac", editor.text)
    }

    @Test
    fun backspaceAfterACommittedControlDeletesTheSentCharacter() {
        editor.commitText("ab\u001b")
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Backspace))
        assertEquals("a", editor.text)
    }

    @Test
    fun enterKeyFinishesTheCompositionThenResets() {
        editor.setComposingText("한")
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Enter))
        assertEquals(listOf<HostInput>(text("한"), enter), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun performEditorActionIsEnter() {
        editor.commitText("a")
        editor.performEditorAction()
        assertEquals(listOf<HostInput>(text("a"), enter), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun backspaceKeySendsDelAndShortensTheHistoryWithoutReset() {
        editor.commitText("あい")
        sent.clear()
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Backspace))
        assertEquals(
            listOf<HostInput>(HostInput.Key(KeyStroke.Special(TerminalKey.Backspace))),
            sent,
        )
        assertEquals("あ", editor.text)
        assertEquals(0, restarts)
        // The history matches the host, so a later replacement deletes only what remains.
        sent.clear()
        editor.replaceText(0, 1, "x")
        assertEquals(listOf<HostInput>(delete(1), text("x")), sent)
    }

    @Test
    fun characterKeyIsCommittedAfterTheComposingText() {
        editor.setComposingText("か")
        editor.onKeyDown(KeyStroke.Character('x'.code))
        assertEquals(listOf<HostInput>(text("かx")), sent)
        assertEquals(0, restarts)
    }

    @Test
    fun otherKeysSendTheCompositionFirstAndReset() {
        val left = KeyStroke.Special(TerminalKey.Left)
        val ctrlC = KeyStroke.Character('c'.code, KeyModifiers.of(ctrl = true))
        editor.setComposingText("あ")
        editor.onKeyDown(left)
        editor.onKeyDown(ctrlC)
        assertEquals(listOf<HostInput>(text("あ"), HostInput.Key(left), HostInput.Key(ctrlC)), sent)
        assertEquals(2, restarts)
    }

    @Test
    fun pasteFollowsTheCompositionAndResets() {
        editor.setComposingText("日本")
        editor.paste("x\n")
        assertEquals(listOf(text("日本"), HostInput.Paste("x\n")), sent)
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun pasteInABatchIsSentAfterTheEditsNotSentYet() {
        editor.commitText("ab")
        sent.clear()
        editor.beginBatchEdit()
        editor.deleteSurroundingText(1, 0)
        editor.setComposingText("か")
        editor.paste("p")
        assertEquals(listOf(delete(1), text("か"), HostInput.Paste("p")), sent)
        // The reset waits for the end of the batch, and nothing else is sent then.
        assertEquals(0, restarts)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf(delete(1), text("か"), HostInput.Paste("p")), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun pendingInputIsSentDuringABatchAndResetsAtItsEnd() {
        editor.commitText("ab")
        sent.clear()
        editor.beginBatchEdit()
        editor.deleteSurroundingText(1, 0)
        editor.setComposingText("か")
        editor.sendPendingInput()
        assertEquals(listOf(delete(1), text("か")), sent)
        assertEquals("aか", editor.text)
        assertEquals(0, restarts)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf(delete(1), text("か")), sent)
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun pendingInputWithoutComposingTextDoesNotReset() {
        editor.commitText("ab")
        editor.sendPendingInput()
        assertEquals(listOf<HostInput>(text("ab")), sent)
        assertEquals("ab", editor.text)
        assertEquals(0, restarts)
    }

    @Test
    fun resetWaitsForTheCompositionToFinish() {
        editor.commitText("a")
        editor.setComposingText("か")
        editor.requestReset()
        assertEquals(0, restarts)
        assertEquals("aか", editor.text)
        editor.finishComposingText()
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun resetInABatchHappensAtTheEnd() {
        editor.beginBatchEdit()
        editor.commitText("a")
        editor.requestReset()
        assertEquals(0, restarts)
        editor.endBatchEdit()
        assertEquals(listOf<HostInput>(text("a")), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun historyLongerThanTheLimitResets() {
        val small =
            ImeEditor(
                object : ImeEditor.Listener {
                    override fun send(input: HostInput) {}

                    override fun restartInput() {
                        restarts++
                    }
                },
                maxHistoryLength = 3,
            )
        small.commitText("abc")
        assertEquals(0, restarts)
        small.commitText("d")
        assertEquals(1, restarts)
        assertEquals("", small.text)
    }

    @Test
    fun closeConnectionFinishesTheComposition() {
        editor.beginBatchEdit()
        editor.setComposingText("か")
        editor.closeConnection()
        assertEquals(listOf<HostInput>(text("か")), sent)
    }

    @Test
    fun textBeforeCursorDoesNotSplitASurrogatePair() {
        editor.commitText("a😀")
        assertEquals("", editor.textBeforeCursor(1))
        assertEquals("😀", editor.textBeforeCursor(2))
        assertEquals("a😀", editor.textBeforeCursor(100))
    }
}
