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
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calls that an untrusted IME may make (requirement 6.1), and the invariants that must hold after
 * any of them. Shared by [HostileImeTest], which chooses the calls at random, and the fuzz test.
 */
internal object HostileIme {
    /** Text an IME may commit: line breaks, surrogate pairs, combining marks, and nothing. */
    val TEXTS = listOf("", "a", "ab ", "日本", "😀", "é", "x\ny", "\r\n", "\uD83D", "long text here")
    val KEYS =
        listOf(
            KeyStroke.Character('q'.code),
            KeyStroke.Special(TerminalKey.Backspace),
            KeyStroke.Special(TerminalKey.Enter),
            KeyStroke.Special(TerminalKey.Left),
        )

    /**
     * The host's input: what was sent, with Enter as a line feed, and DEL deleting one code point.
     */
    class Host(prompt: String) : ImeEditor.Listener {
        val line = StringBuilder(prompt)
        var largestDelete = 0

        /**
         * Whether a key other than Backspace was sent and the editor has not reset since. The
         * editor's history then no longer matches the host's line, until the reset that waits for
         * the composing text to finish (docs/design/ime-contract.md section 5).
         */
        var awaitingReset = false

        override fun send(input: HostInput) {
            when (input) {
                is HostInput.Text -> line.append(input.text)
                is HostInput.Delete -> {
                    largestDelete = maxOf(largestDelete, input.count)
                    repeat(minOf(input.count, line.codePointCount(0, line.length))) {
                        line.setLength(line.offsetByCodePoints(line.length, -1))
                    }
                }
                is HostInput.Key ->
                    when (input.stroke) {
                        KeyStroke.Special(TerminalKey.Backspace) ->
                            if (line.isNotEmpty())
                                line.setLength(line.offsetByCodePoints(line.length, -1))
                        KeyStroke.Special(TerminalKey.Enter) -> {
                            line.append('\n')
                            awaitingReset = true
                        }
                        else -> awaitingReset = true
                    }
                is HostInput.Paste -> {
                    line.append(input.text)
                    awaitingReset = true
                }
            }
        }

        override fun restartInput() {
            awaitingReset = false
        }
    }

    /**
     * Makes a call, with the call and its arguments chosen by [choose], which returns a number in 0
     * until its argument. Returns the call, for failure messages.
     */
    fun call(editor: ImeEditor, choose: (Int) -> Int): String {
        fun offset() =
            when (choose(4)) {
                0 -> -2 + choose(editor.text.length + 5)
                1 -> editor.text.length
                2 -> Int.MAX_VALUE
                else -> Int.MIN_VALUE
            }
        fun text() = TEXTS[choose(TEXTS.size)]
        fun describe(name: String, vararg arguments: Any) =
            "$name(${arguments.joinToString { if (it is String) "'$it'" else "$it" }})"
        return when (choose(14)) {
            0 ->
                text().let {
                    editor.setComposingText(it)
                    describe("setComposingText", it)
                }
            1 ->
                text().let {
                    editor.commitText(it)
                    describe("commitText", it)
                }
            2 -> editor.finishComposingText().let { describe("finishComposingText") }
            3 ->
                offset().let { a ->
                    offset().let { b ->
                        editor.setComposingRegion(a, b)
                        describe("setComposingRegion", a, b)
                    }
                }
            4 ->
                offset().let { a ->
                    offset().let { b ->
                        editor.deleteSurroundingText(a, b)
                        describe("deleteSurroundingText", a, b)
                    }
                }
            5 ->
                offset().let { a ->
                    offset().let { b ->
                        editor.deleteSurroundingTextInCodePoints(a, b)
                        describe("deleteSurroundingTextInCodePoints", a, b)
                    }
                }
            6 ->
                offset().let { a ->
                    offset().let { b ->
                        text().let { t ->
                            editor.replaceText(a, b, t)
                            describe("replaceText", a, b, t)
                        }
                    }
                }
            7 ->
                offset().let { a ->
                    offset().let { b ->
                        editor.setSelection(a, b)
                        describe("setSelection", a, b)
                    }
                }
            8 -> editor.beginBatchEdit().let { describe("beginBatchEdit") }
            9 -> editor.endBatchEdit().let { describe("endBatchEdit") }
            10 ->
                offset().let {
                    editor.textBeforeCursor(it)
                    describe("textBeforeCursor", it)
                }
            11 ->
                KEYS[choose(KEYS.size)].let {
                    editor.onKeyDown(it)
                    describe("onKeyDown", it)
                }
            12 -> editor.requestReset().let { describe("requestReset") }
            else -> editor.closeConnection().let { describe("closeConnection") }
        }
    }

    /**
     * Checks the invariants after a call of an editor with [maxHistoryLength]. [context] describes
     * the calls so far.
     */
    fun checkInvariants(editor: ImeEditor, host: Host, maxHistoryLength: Int, context: String) {
        // Invariant 1: the cursor is at the end of the edit buffer.
        assertEquals(editor.text.length, editor.cursor, context)
        assertTrue(editor.selectionStart in 0..editor.cursor, context)
        // Invariant 5: the input history stays within its limit, also during a batch edit.
        val history =
            editor.text.substring(0, editor.composingStart.takeIf { it >= 0 } ?: editor.cursor)
        assertTrue(history.codePointCount(0, history.length) <= maxHistoryLength, context)
        if (!editor.isBatchEditInProgress && !host.awaitingReset) {
            // The host has exactly the committed part of the history at the end of its line.
            val committed =
                editor.text
                    .substring(
                        0,
                        if (editor.composingStart >= 0) editor.composingStart else editor.cursor,
                    )
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
            assertTrue(
                host.line.endsWith(committed),
                "$context: host '${host.line}' editor '$committed'",
            )
        }
    }

    /**
     * Checks that the DELs beyond the text that was sent are bounded by the history's limit, after
     * [calls] calls.
     */
    fun checkDeleteBound(host: Host, calls: Int, maxHistoryLength: Int, context: String) {
        assertTrue(
            host.largestDelete <= TEXTS.maxOf { it.length } * calls + maxHistoryLength,
            context,
        )
    }
}
