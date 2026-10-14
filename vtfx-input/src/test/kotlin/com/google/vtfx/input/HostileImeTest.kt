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
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The IME is not trusted (requirement 6.1): any sequence of calls, with any arguments, must keep
 * the editor's invariants and make the host see exactly the committed input history.
 */
class HostileImeTest {
    @Test
    fun randomCallsKeepTheInvariants() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val host = HostileIme.Host("$ ")
            val editor = ImeEditor(host, maxHistoryLength = 16)
            val calls = mutableListOf<String>()
            repeat(CALLS) { step ->
                calls += HostileIme.call(editor) { random.nextInt(it) }
                HostileIme.checkInvariants(
                    editor,
                    host,
                    maxHistoryLength = 16,
                    "iteration $iteration, step $step, calls ${calls.joinToString("; ")}",
                )
            }
            editor.closeConnection()
            HostileIme.checkDeleteBound(host, CALLS, maxHistoryLength = 16, "iteration $iteration")
        }
    }

    @Test
    fun deletesBeforeTheHistoryAreBounded() {
        val host = HostileIme.Host("$ ")
        val editor = ImeEditor(host, maxHistoryLength = 16)
        editor.beginBatchEdit()
        // Twice, so that an unbounded count would also overflow.
        editor.deleteSurroundingText(Int.MAX_VALUE, 0)
        editor.deleteSurroundingTextInCodePoints(Int.MAX_VALUE, Int.MAX_VALUE)
        editor.endBatchEdit()
        assertEquals(16, host.largestDelete)
    }

    @Test
    fun theHistoryStaysBoundedDuringABatchEdit() {
        // An IME that never ends its batch edit must not make the edit buffer grow without bound.
        val host = HostileIme.Host("$ ")
        val editor = ImeEditor(host, maxHistoryLength = 16)
        editor.beginBatchEdit()
        repeat(100) {
            editor.commitText("abcdefghij")
            assertTrue(editor.text.length <= 16, "after $it commits: ${editor.text.length}")
        }
        editor.endBatchEdit()
        // What was committed reached the host, in order.
        assertEquals("$ " + "abcdefghij".repeat(100), host.line.toString())
    }

    @Test
    fun textRetypedAfterDeletingBeforeTheHistoryIsSentAgain() {
        // Found with random calls: the retyped text was kept as a common prefix,
        // and the DELs for the text before the history then deleted it at the host.
        val host = HostileIme.Host("$ ")
        val editor = ImeEditor(host, maxHistoryLength = 16)
        editor.commitText("ls")
        editor.beginBatchEdit()
        editor.deleteSurroundingText(4, 0)
        editor.commitText("ls")
        editor.endBatchEdit()
        assertEquals("ls", host.line.toString())
    }

    @Test
    fun negativeLengthsGiveNoText() {
        val editor = ImeEditor(HostileIme.Host("$ "))
        editor.commitText("ls")
        assertEquals("", editor.textBeforeCursor(-2))
        assertEquals("", editor.textBeforeCursor(Int.MIN_VALUE))
    }

    private companion object {
        const val SEED = 20261012L
        const val ITERATIONS = 3000
        const val CALLS = 40
    }
}
