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

package com.google.vtfx.gate.esctest

import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalEvent
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.HostWriter
import com.google.vtfx.gate.ReportedColors
import com.google.vtfx.gate.TerminalGate
import java.io.ByteArrayOutputStream

/**
 * The terminal under test for esctest2, run by scripts/esctest.py: reads esctest's output from
 * stdin, interprets it with vtfx-core, and writes the replies vtfx-gate builds to stdout.
 *
 * Three sequences are handled by this harness only, since vtfx does not support them on purpose
 * (docs/decisions/0079): DECRQCRA, through which esctest reads the screen one cell at a time,
 * XTWINOPS 8, with which it sets the size to 80 x 25 before each test, and XTWINOPS 21, with which
 * it reads the window title that vtfx reports in TitleChanged events.
 */
object EsctestHost {
    private const val ESC = 0x1B

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val writer = HostWriter { bytes ->
            out.write(bytes)
            out.flush()
        }
        val colors = ReportedColors { target ->
            when (target) {
                ColorTarget.Foreground -> 0x000000
                ColorTarget.Background -> 0xFFFFFF
                ColorTarget.Cursor -> 0x000000
                // Any color, as a display reports one, which the tests of OSC 104 and 105 read
                // before they set a color and expect again after they reset it.
                is ColorTarget.Palette -> 0x808080
            }
        }
        val gate = TerminalGate(writer, colors = colors)
        var title = ""
        val terminal =
            Terminal(80, 25) { event ->
                if (event is TerminalEvent.TitleChanged) title = event.title
                // No clipboard, so handing the events one at a time changes nothing.
                gate.handle(listOf(event))
            }
        val scanner = Scanner(terminal, out) { title }
        val buffer = ByteArray(65536)
        while (true) {
            val n = System.`in`.read(buffer)
            if (n < 0) break
            scanner.feed(buffer, n)
        }
    }

    /** Passes the input to [terminal], except the sequences this harness handles. */
    private class Scanner(
        private val terminal: Terminal,
        private val out: java.io.PrintStream,
        private val title: () -> String,
    ) {
        private val pending = ByteArrayOutputStream()

        fun feed(bytes: ByteArray, length: Int) {
            pending.write(bytes, 0, length)
            val data = pending.toByteArray()
            pending.reset()
            var start = 0
            var i = 0
            while (i < data.size) {
                if (data[i].toInt() != ESC) {
                    i++
                    continue
                }
                if (i + 1 >= data.size) break
                if (data[i + 1] != '['.code.toByte()) {
                    i++
                    continue
                }
                // A CSI sequence: parameters, intermediates, and a final byte.
                var j = i + 2
                while (j < data.size && data[j] in 0x30..0x3F) j++
                while (j < data.size && data[j] in 0x20..0x2F) j++
                if (j >= data.size) break
                val sequence = String(data, i + 2, j + 1 - (i + 2), Charsets.US_ASCII)
                if (handle(sequence, data, start, i)) {
                    start = j + 1
                }
                i = j + 1
            }
            // Keep an incomplete sequence for the next read.
            val end = if (i < data.size) i else data.size
            terminal.write(data, start, end - start)
            pending.write(data, end, data.size - end)
        }

        /**
         * Handles [sequence] (after CSI) if it is one of the harness's; the text before it first.
         */
        private fun handle(sequence: String, data: ByteArray, start: Int, at: Int): Boolean {
            val final = sequence.last()
            val body = sequence.dropLast(1)
            when {
                final == 'y' && body.endsWith("*") -> {
                    terminal.write(data, start, at - start)
                    checksum(body.dropLast(1).split(';'))
                }
                final == 't' && body.split(';').firstOrNull() == "8" -> {
                    terminal.write(data, start, at - start)
                    val params = body.split(';').map { it.toIntOrNull() ?: 0 }
                    val rows = params.getOrElse(1) { 0 }.takeIf { it > 0 } ?: terminal.rows
                    val columns = params.getOrElse(2) { 0 }.takeIf { it > 0 } ?: terminal.columns
                    terminal.resize(columns, rows)
                }
                final == 't' && body == "21" -> {
                    terminal.write(data, start, at - start)
                    // Not a reply vtfx makes (docs/decisions/0083), so it is written here.
                    out.write("\u001b]l${title()}\u001b\\".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                else -> return false
            }
            return true
        }

        /**
         * DECRQCRA: the sum of the code points in the rectangle, as xterm 279 and later compute it
         * for esctest: blank cells count as spaces.
         */
        private fun checksum(params: List<String>) {
            fun param(i: Int, default: Int) =
                params.getOrNull(i)?.toIntOrNull()?.takeIf { it > 0 } ?: default
            val id = params.getOrNull(0)?.toIntOrNull() ?: 0
            val snapshot = terminal.snapshot()
            val top = param(2, 1)
            val left = param(3, 1)
            val bottom = param(4, snapshot.rows)
            val right = param(5, snapshot.columns)
            var sum = 0
            for (row in top..minOf(bottom, snapshot.rows)) {
                for (column in left..minOf(right, snapshot.columns)) {
                    sum += cellCode(snapshot, row - 1, column - 1)
                }
            }
            val reply = "\u001bP$id!~%04X\u001b\\".format(sum and 0xFFFF)
            // Not a reply vtfx makes, so it is written here rather than through HostWriter.
            out.write(reply.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        private fun cellCode(snapshot: TerminalSnapshot, row: Int, column: Int): Int {
            val line = snapshot.line(row)
            // The right half of a wide character counts as nothing; a blank cell as a space.
            if (line.widthAt(column) == 0) return 0
            val text = line.textAt(column) ?: return ' '.code
            return text.codePointAt(0)
        }
    }
}
