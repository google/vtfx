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

package com.google.vtfx.gate

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalQuery
import com.google.vtfx.testing.terminalModes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class HostWriterTest {
    private val sent = mutableListOf<String>()
    private val writer = HostWriter { sent += String(it, Charsets.UTF_8) }

    @Test
    fun textConvertsLineBreaksToCr() {
        writer.sendText("a\nb\r\nc\rd")
        assertEquals(listOf("a\rb\rc\rd"), sent)
    }

    @Test
    fun textKeepsTabAndDropsOtherControls() {
        writer.sendText("a\tb\u0000\u0003\u001b[31m\u007f\u0085\u009bc")
        assertEquals(listOf("a\tb[31mc"), sent)
    }

    @Test
    fun textReplacesUnpairedSurrogates() {
        writer.sendText("a\uD83Db\uDE00\uD83D\uDE00")
        assertEquals(listOf("a\uFFFDb\uFFFD\uD83D\uDE00"), sent)
    }

    @Test
    fun textWithOnlyControlsSendsNothing() {
        writer.sendText("\u001b\u0007")
        assertEquals(emptyList(), sent)
    }

    @Test
    fun deletesSendDel() {
        writer.sendDeletes(3)
        assertEquals(listOf("\u007f\u007f\u007f"), sent)
        assertFailsWith<IllegalArgumentException> { writer.sendDeletes(0) }
    }

    @Test
    fun keysAreEncodedUnderTheModes() {
        writer.sendKey(KeyStroke.Special(TerminalKey.Up), terminalModes())
        writer.sendKey(
            KeyStroke.Special(TerminalKey.Up),
            terminalModes(applicationCursorKeys = true),
        )
        writer.sendKey(KeyStroke.Special(TerminalKey.Enter), terminalModes(newLine = true))
        writer.sendKey(KeyStroke.Character('c'.code, KeyModifiers.of(ctrl = true)), terminalModes())
        assertEquals(listOf("\u001b[A", "\u001bOA", "\r\n", "\u0003"), sent)
    }

    @Test
    fun repliesAreBuiltFromTheQuery() {
        writer.sendReply(TerminalQuery.PrimaryDeviceAttributes)
        writer.sendReply(TerminalQuery.SecondaryDeviceAttributes)
        writer.sendReply(TerminalQuery.OperatingStatus)
        writer.sendReply(TerminalQuery.CursorPosition(3, 14, isDecPrivate = false))
        writer.sendReply(TerminalQuery.CursorPosition(1, 1, isDecPrivate = true))
        assertEquals(
            listOf(
                "\u001b[?62;22c",
                "\u001b[>0;10;1c",
                "\u001b[0n",
                "\u001b[3;14R",
                "\u001b[?1;1R",
            ),
            sent,
        )
    }

    @Test
    fun cursorPositionOutOfRangeIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> {
            writer.sendReply(TerminalQuery.CursorPosition(0, 1, false))
        }
    }

    @Test
    fun focusReports() {
        writer.sendFocus(true)
        writer.sendFocus(false)
        assertEquals(listOf("\u001b[I", "\u001b[O"), sent)
    }

    @Test
    fun theConnectionOwnsTheBytesItIsGiven() {
        // A connection may overwrite what it sent, for example to wipe it, without changing what
        // later calls send.
        val writer = HostWriter { bytes ->
            sent += String(bytes, Charsets.UTF_8)
            bytes.fill('x'.code.toByte())
        }
        repeat(2) {
            writer.sendFocus(true)
            writer.sendFocus(false)
            writer.sendPaste("a", bracketed = true)
            writer.sendKey(KeyStroke.Special(TerminalKey.Up), terminalModes())
            writer.sendReply(TerminalQuery.OperatingStatus)
        }
        val once =
            listOf("\u001b[I", "\u001b[O", "\u001b[200~a\u001b[201~", "\u001b[A", "\u001b[0n")
        assertEquals(once + once, sent)
    }

    @Test
    fun sendingFromTheConnectionIsAProgrammingError() {
        lateinit var nested: HostWriter
        var calls = 0
        nested = HostWriter { if (calls++ == 0) nested.sendText("b") }
        assertFailsWith<IllegalStateException> { nested.sendText("a") }
        // The writer is usable again afterwards.
        val writer = HostWriter { sent += String(it, Charsets.UTF_8) }
        writer.sendText("c")
        assertEquals(listOf("c"), sent)
    }

    @Test
    fun knowsWhetherTheCurrentThreadIsInTheConnection() {
        lateinit var writer: HostWriter
        val inside = mutableListOf<Boolean>()
        writer = HostWriter {
            inside += writer.isWritingOnCurrentThread()
            val thread = Thread { inside += writer.isWritingOnCurrentThread() }
            thread.start()
            thread.join()
        }
        assertEquals(false, writer.isWritingOnCurrentThread())
        writer.sendText("a")
        assertEquals(listOf(true, false), inside)
        assertEquals(false, writer.isWritingOnCurrentThread())
    }
}
