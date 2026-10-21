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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for the user's copy and paste through [TerminalGate], to which the OSC 52 policy does not
 * apply.
 */
class UserClipboardTest {
    private val osc52Refused =
        GatePolicy(
            allowClipboardWrite = false,
            maxClipboardWriteLength = 0,
            allowClipboardRead = false,
        )

    @Test
    fun copyPutsTheTextOnTheClipboardWhateverTheOsc52Policy() {
        val clipboard = FakeClipboard("old")
        val gate = TerminalGate(HostWriter {}, osc52Refused, clipboard)

        val copied = gate.copyToClipboard("selected text")

        assertTrue(copied)
        assertEquals("selected text", clipboard.text)
    }

    @Test
    fun copyWithoutAClipboardReturnsFalse() {
        val gate = TerminalGate(HostWriter {}, GatePolicy(), clipboard = null)

        assertFalse(gate.copyToClipboard("selected text"))
    }

    @Test
    fun pasteReadsTheClipboardWhateverTheOsc52Policy() {
        val gate = TerminalGate(HostWriter {}, osc52Refused, FakeClipboard("from another app"))

        assertEquals("from another app", gate.readClipboardForPaste())
    }

    @Test
    fun pasteWithoutAClipboardReadsNothing() {
        val gate = TerminalGate(HostWriter {}, GatePolicy(), clipboard = null)

        assertNull(gate.readClipboardForPaste())
    }

    @Test
    fun pasteFromAnEmptyClipboardReadsNothing() {
        val gate = TerminalGate(HostWriter {}, GatePolicy(), FakeClipboard(null))

        assertNull(gate.readClipboardForPaste())
    }
}
