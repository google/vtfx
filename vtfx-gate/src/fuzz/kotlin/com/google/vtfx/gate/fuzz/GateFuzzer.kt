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

package com.google.vtfx.gate.fuzz

import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalEvent
import com.google.vtfx.gate.ClipboardAccess
import com.google.vtfx.gate.GatePolicy
import com.google.vtfx.gate.HostWriter
import com.google.vtfx.gate.KnownReplies
import com.google.vtfx.gate.ReportedColors
import com.google.vtfx.gate.TerminalGate
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Fuzzes the gate with the untrusted input it handles (requirement 6.1): host output, through a
 * real [Terminal], and pasted text (docs/design/testing.md section 7).
 *
 * A libFuzzer entry point. The tests replay its corpus in src/fuzz/corpus/GateFuzzer
 * ([GateFuzzerCorpusTest]), and the `fuzz` Gradle task fuzzes it; see scripts/fuzz.sh.
 *
 * Input format, so that seeds can be written by hand:
 * - Byte 0: the number of columns, 1 + (byte % 80).
 * - Byte 1: the number of rows, 1 + (byte % 24).
 * - Byte 2: bit 0 allows clipboard reads, bit 1 reports colors, bits 2 to 5 are the limit of
 *   clipboard writes, and bit 6 allows clipboard writes.
 * - Bytes 3 and 4: the width of a cell in pixels, big-endian, % 1001. Bytes 5 and 6: the height,
 *   the same way. The cell size is set, as a display sets it, only if both are nonzero, so that the
 *   replies to XTWINOPS 14 and 16 are made and their ranges checked.
 * - The rest: host output. 0xFE, which does not appear in UTF-8, splits it into separate writes.
 *   The rest is also pasted, decoded as UTF-8.
 */
object GateFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(input: ByteArray) {
        if (input.size < HEADER_SIZE) return
        val columns = 1 + (input[0].toInt() and 0xFF) % 80
        val rows = 1 + (input[1].toInt() and 0xFF) % 24
        val flags = input[2].toInt() and 0xFF
        val policy =
            GatePolicy(
                allowClipboardWrite = flags and 0x40 != 0,
                maxClipboardWriteLength = (flags shr 2) % 16,
                allowClipboardRead = flags and 1 != 0,
            )
        val reportsColors = flags and 2 != 0
        val cellWidth = input.uint16At(3) % 1001
        val cellHeight = input.uint16At(5) % 1001
        val output = input.copyOfRange(HEADER_SIZE, input.size)

        val replies = mutableListOf<String>()
        val clipboardWrites = mutableListOf<String>()
        val clipboard =
            object : ClipboardAccess {
                override fun write(text: String) {
                    clipboardWrites += text
                }

                override fun read(): String = CLIPBOARD_TEXT
            }
        val gate =
            TerminalGate(
                HostWriter { replies += String(it, Charsets.UTF_8) },
                policy,
                clipboard,
                urlOpener = { error("host output opened a URL: $it") },
                colors =
                    if (reportsColors)
                        ReportedColors { if (it == ColorTarget.Palette(255)) null else 0x12AB34 }
                    else null,
            )
        // Like TerminalSession, hands the gate the events of each write together.
        val events = mutableListOf<TerminalEvent>()
        val terminal = Terminal(columns, rows) { events += it }
        if (cellWidth != 0 && cellHeight != 0) terminal.setCellSize(cellWidth, cellHeight)
        for (write in output.split(SPLIT)) {
            terminal.write(write)
            gate.handle(events.toList())
            events.clear()
        }

        val allowed = buildList {
            add(KnownReplies.pattern.pattern)
            if (reportsColors) add(COLOR_REPLY)
            if (policy.allowClipboardRead) add(clipboardReply())
        }
            .joinToString("|")
            .toRegex()
        for (reply in replies) check(allowed.matches(reply)) {
            "unexpected reply: ${reply.escaped()}"
        }
        val replyBytes = replies.sumOf { it.length }
        check(replyBytes <= MAX_REPLY_BYTES_PER_INPUT_BYTE * output.size) {
            "$replyBytes reply bytes for ${output.size}"
        }
        check(policy.allowClipboardWrite || clipboardWrites.isEmpty()) {
            "clipboard written without permission"
        }
        for (text in clipboardWrites) check(text.length <= policy.maxClipboardWriteLength) {
            "clipboard write of ${text.length}"
        }

        checkPaste(String(output, Charsets.UTF_8))
    }

    /** A paste cannot end the bracket early or carry controls (requirement 6.1). */
    private fun checkPaste(text: String) {
        val sent = mutableListOf<ByteArray>()
        HostWriter { sent += it }.sendPaste(text, bracketed = true)
        if (sent.isEmpty()) return
        // Decoding strictly also checks that the paste is valid UTF-8.
        val pasted = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(sent.single())).toString()
        check(pasted.startsWith(PASTE_START) && pasted.endsWith(PASTE_END)) {
            "paste not bracketed: ${pasted.escaped()}"
        }
        val body = pasted.substring(PASTE_START.length, pasted.length - PASTE_END.length)
        check(body.isNotEmpty()) { "empty paste was sent" }
        check(body.all { it == '\r' || it == '\t' || (it >= ' ' && it !in '\u007f'..'\u009f') }) {
            "control in paste: ${body.escaped()}"
        }
    }

    private fun clipboardReply(): String {
        val data = Base64.getEncoder().encodeToString(CLIPBOARD_TEXT.toByteArray(Charsets.UTF_8))
        // xterm's selection targets, written out here rather than taken from vtfx-core or the
        // gate, so that the oracle does not depend on the code it checks.
        // Reads after the first of a write are replied to as empty.
        return """\u001b\]52;[cpqs0-7]{1,16};(?:${Regex.escape(data)})?\u001b\\"""
    }

    private fun ByteArray.uint16At(index: Int): Int =
        ((this[index].toInt() and 0xFF) shl 8) or (this[index + 1].toInt() and 0xFF)

    private fun ByteArray.split(separator: Byte): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == separator) {
                parts += copyOfRange(start, i)
                start = i + 1
            }
        }
        parts += copyOfRange(start, size)
        return parts
    }

    private fun String.escaped() =
        asIterable().joinToString("") {
            if (it < ' ' || it in '\u007f'..'\u009f') "\\x%02x".format(it.code) else "$it"
        }

    private const val HEADER_SIZE = 7
    private const val SPLIT = 0xFE.toByte()

    /** Text with a sequence that would answer the host, were it not encoded. */
    private const val CLIPBOARD_TEXT = "x\u001b[6n\u001b]52;c;?\u0007"

    /** The longest reply is to a short query for a styled cell (DECRQSS SGR). */
    private const val MAX_REPLY_BYTES_PER_INPUT_BYTE = 16

    private const val COLOR_REPLY = """\u001b\](?:1[0-2]|4;\d{1,3});rgb:1212/abab/3434\u001b\\"""
    private const val PASTE_START = "\u001b[200~"
    private const val PASTE_END = "\u001b[201~"
}
