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
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Pins that the two copies of the rule for dropping control characters (ime-contract.md section
 * 4.2) agree: `ImeEditor.withoutDroppedControls` in vtfx-input, which keeps them out of the edit
 * buffer, and `OutboundText.encode` in vtfx-gate, which keeps them from the host. If they
 * disagreed, the input history would not match what the host received, and a later edit would send
 * DELs for characters the host never got, or leave characters the IME no longer shows (invariant
 * 3).
 *
 * The copies stay apart on purpose: the gate must not depend on code outside security review
 * (docs/decisions/0041), and vtfx-input must not depend on the gate (`config/boundaries.toml`). The
 * session has both, so the agreement is tested here, through the session's real wiring.
 *
 * Committed text passes the editor's rule and then the gate's, so what the host receives of it
 * shows only what the gate drops and the editor keeps. A paste passes the gate's rule alone, so
 * comparing what the editor keeps of a commit with what the host receives of the same text pasted
 * also shows what the editor drops and the gate keeps.
 */
class DroppedControlsAgreementTest {
    /** What the editor kept in its edit buffer and what the host received, decoded as UTF-8. */
    private data class Commit(val kept: String, val received: String)

    /** Commits [text] as an IME does, to a new session. */
    private fun commitToNewSession(text: String): Commit {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        val kept = session.edit { editor ->
            editor.commitText(text)
            editor.text
        }
        return Commit(kept, sent.toString(Charsets.UTF_8))
    }

    /** Pastes [text] to a new session, whose host has not enabled bracketed paste. */
    private fun pasteToNewSession(text: String): String {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.paste(text)
        return sent.toString(Charsets.UTF_8)
    }

    private fun Char.label(): String = "U+%04X".format(code)

    @Test
    fun theHostReceivesWhatTheEditorKeeps_forEveryCodeUnitButSurrogatesLineBreaksAndTab() {
        val codeUnits =
            // Line breaks and HT reset the history, which is then empty.
            ('\u0000'..'\uffff').filter {
                !it.isSurrogate() && it != '\r' && it != '\n' && it != '\t'
            }

        val mismatches = codeUnits.filter {
            commitToNewSession("a${it}b").let { c -> c.kept != c.received }
        }

        assertEquals(emptyList(), mismatches.map { it.label() })
    }

    @Test
    fun theEditorKeepsWhatTheGateLetsThrough_forEveryCodeUnitButSurrogatesLineBreaksAndTab() {
        val codeUnits =
            // Line breaks and HT reset the history, which is then empty.
            ('\u0000'..'\uffff').filter {
                !it.isSurrogate() && it != '\r' && it != '\n' && it != '\t'
            }

        val mismatches = codeUnits.filter {
            commitToNewSession("a${it}b").kept != pasteToNewSession("a${it}b")
        }

        assertEquals(emptyList(), mismatches.map { it.label() })
    }

    @Test
    fun aTabIsReceivedAndResetsTheHistory() {
        val commit = commitToNewSession("a\tb")
        assertEquals("a\tb", commit.received)
        assertEquals("", commit.kept)
        assertEquals("a\tb", pasteToNewSession("a\tb"))
    }

    @Test
    fun anUnpairedSurrogateIsKeptAsIsAndReceivedAsOneReplacementCharacter() {
        // Both are one code point, so a later edit sends one DEL for it (section 4.1).
        val surrogates = ('\ud800'..'\udfff').toList()

        val mismatches = surrogates.filter {
            commitToNewSession("a${it}b") != Commit(kept = "a${it}b", received = "a\ufffdb")
        }

        assertEquals(emptyList(), mismatches.map { it.label() })
    }

    @Test
    fun aSurrogatePairIsKeptAndReceivedWhole() {
        val commit = commitToNewSession("a\ud83d\ude00b")

        assertEquals(Commit(kept = "a\ud83d\ude00b", received = "a\ud83d\ude00b"), commit)
    }

    @Test
    fun aSurrogatePairAroundADroppedControlIsKeptAndReceivedWhole() {
        // The editor drops ESC first, so the gate gets the pair and does not replace its halves.
        val commit = commitToNewSession("\ud83d\u001b\ude00")

        assertEquals(Commit(kept = "\ud83d\ude00", received = "\ud83d\ude00"), commit)
    }

    @Test
    fun aSurrogatePairCompletedByALaterCommitReplacesTheReplacementCharacter() {
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.edit { it.commitText("\ud83d") }

        val kept = session.edit { editor ->
            editor.commitText("\ude00")
            editor.text
        }

        // The host got U+FFFD for the unpaired high surrogate, so one DEL deletes it.
        assertEquals(
            Commit(kept = "\ud83d\ude00", received = "\ufffd\u007f\ud83d\ude00"),
            Commit(kept, sent.toString(Charsets.UTF_8)),
        )
    }

    @Test
    fun aCarriageReturnIsReceivedAsEnterAndEmptiesTheEditBuffer() {
        // The editor sends a line break as Enter, not as text, and resets (section 5.1), so no
        // later edit can delete across it.
        val commit = commitToNewSession("a\rb")

        assertEquals(Commit(kept = "", received = "a\rb"), commit)
    }

    @Test
    fun aLineFeedIsReceivedAsEnterAndEmptiesTheEditBuffer() {
        val commit = commitToNewSession("a\nb")

        assertEquals(Commit(kept = "", received = "a\rb"), commit)
    }
}
