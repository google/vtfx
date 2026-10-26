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

package com.google.vtfx.sample

import com.google.vtfx.core.CharWidth

/**
 * A pseudo host: a tiny shell with a line editor, which echoes what it receives the way a shell in
 * a PTY does. It lets the sample app run without a real host. It can also replay the output of real
 * apps recorded by tools/recordings.
 *
 * Not thread-safe. [output] receives the host's output, [recording] returns the recorded output
 * with a name or null, [requestSize] asks the app for at least the given columns and rows, for
 * example by making the font smaller, and [font] receives the arguments of the `font` command,
 * which the app carries out and answers with [report].
 */
class EchoHost(
    private val output: (ByteArray) -> Unit,
    private val recording: (String) -> ByteArray? = { null },
    private val requestSize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    private val font: (arguments: String) -> Unit,
) {
    private val line = StringBuilder()
    private val utf8 = Utf8Accumulator()
    private var escape = Escape.None
    private var columns = 80
    private var rows = 24

    /** A recording waiting for the terminal to be large enough. */
    private var pendingReplay: ByteArray? = null

    /** Whether a recording is shown, until the next key. */
    private var showingReplay = false

    private enum class Escape {
        None,
        Esc,
        Csi,
        Ss3,
        String,
        StringEsc,
    }

    private fun output(text: String) = output(text.toByteArray(Charsets.UTF_8))

    fun start() {
        output("vtfx sample. Type 'help' for the commands.\r\n")
        prompt()
    }

    /** Shows [message] from the app above the prompt, keeping the line being edited. */
    fun report(message: String) {
        output("\r\u001b[K${message.replace("\n", "\r\n")}\r\n")
        prompt()
        output(line.toString())
    }

    fun onResize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        val pending = pendingReplay
        if (pending != null && isLargeEnoughForReplay()) {
            pendingReplay = null
            play(pending)
        }
    }

    fun receive(bytes: ByteArray) {
        if (showingReplay) {
            // The recordings contain queries, whose replies arrive here; they start with ESC.
            // Another key leaves the recording and returns to the shell.
            if (bytes.isEmpty() || bytes[0].toInt() == ESC) return
            showingReplay = false
            output(LEAVE_REPLAY)
            prompt()
            return
        }
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            when (escape) {
                Escape.Esc -> {
                    escape =
                        when (b) {
                            '['.code -> Escape.Csi
                            'O'.code -> Escape.Ss3
                            // Replies with strings: DCS (e.g. XTVERSION) and OSC (e.g. colors).
                            'P'.code,
                            ']'.code -> Escape.String
                            else -> Escape.None
                        }
                    continue
                }
                Escape.String -> {
                    escape =
                        when (b) {
                            BEL -> Escape.None
                            ESC -> Escape.StringEsc
                            else -> Escape.String
                        }
                    continue
                }
                Escape.StringEsc -> {
                    escape = if (b == '\\'.code) Escape.None else Escape.String
                    continue
                }
                // Keys and paste markers: not part of the line.
                Escape.Csi -> {
                    if (b in 0x40..0x7E) escape = Escape.None
                    continue
                }
                Escape.Ss3 -> {
                    escape = Escape.None
                    continue
                }
                Escape.None -> {}
            }
            when {
                b == ESC -> escape = Escape.Esc
                b == CR -> enter()
                b == DEL || b == BS -> backspace()
                b == CTRL_C -> {
                    output("^C\r\n")
                    line.setLength(0)
                    prompt()
                }
                b == CTRL_L -> {
                    output("\u001b[2J\u001b[H")
                    prompt()
                    output(line.toString())
                }
                b < 0x20 -> {}
                else ->
                    utf8.add(b)?.let { text ->
                        line.append(text)
                        output(text)
                    }
            }
        }
    }

    private fun backspace() {
        if (line.isEmpty()) return
        val start = line.offsetByCodePoints(line.length, -1)
        val width = maxOf(CharWidth.of(line.codePointAt(start)), 1)
        line.setLength(start)
        output("\b".repeat(width) + " ".repeat(width) + "\b".repeat(width))
    }

    private fun enter() {
        output("\r\n")
        val command = line.toString().trim()
        line.setLength(0)
        if (run(command)) prompt()
    }

    private fun prompt() {
        output("\u001b[32m$\u001b[0m ")
    }

    /** Runs [command] and returns whether to show the prompt. */
    private fun run(command: String): Boolean {
        val argument = command.substringAfter(' ', "").trim()
        when (command.substringBefore(' ')) {
            "" -> {}
            "help" -> output(HELP)
            "clear" -> output("\u001b[2J\u001b[H")
            "colors" -> output(colors())
            "unicode" -> output(UNICODE)
            "links" -> output(LINKS)
            "size" -> output("$columns x $rows\r\n")
            // The recording is shown instead of the prompt.
            "replay" -> if (replay(argument)) return false
            "reset" -> output("\u001bc")
            "font" -> font(argument)
            // Mouse reports (button events, SGR), to try a mouse as with vim's mouse=a.
            "mouse" ->
                output(
                    if (argument == "off") "\u001b[?1002l\u001b[?1006l"
                    else "\u001b[?1002h\u001b[?1006h"
                )
            else -> output("$command\r\n")
        }
        return true
    }

    /**
     * Writes a recording as is, and shows it until the next key. The apps were recorded at 80 x 24
     * and position their output for that size, so a smaller terminal is enlarged first.
     */
    private fun replay(name: String): Boolean {
        val data = recording(name)
        if (data == null) {
            output("No recording '$name'. Try: vim, less, htop, tmux, timg, icat, fastfetch\r\n")
            return false
        }
        if (isLargeEnoughForReplay()) {
            play(data)
        } else {
            pendingReplay = data
            requestSize(REPLAY_COLUMNS, REPLAY_ROWS)
        }
        return true
    }

    /**
     * Writes the recording into the top 24 rows: apps that scroll (less) expect the bottom margin
     * at row 24, while the screen may have more rows.
     */
    private fun play(data: ByteArray) {
        output("\u001b[1;${REPLAY_ROWS}r")
        output(data)
        showingReplay = true
    }

    private fun isLargeEnoughForReplay() = columns >= REPLAY_COLUMNS && rows >= REPLAY_ROWS

    private fun colors(): String = buildString {
        for (i in 0 until 16) append("\u001b[48;5;${i}m  ")
        append("\u001b[0m\r\n")
        for (i in 16 until 232) {
            append("\u001b[48;5;${i}m ")
            if ((i - 16) % 36 == 35) append("\u001b[0m\r\n")
        }
        for (i in 232 until 256) append("\u001b[48;5;${i}m ")
        append("\u001b[0m\r\n")
        append(
            "\u001b[1mbold\u001b[0m \u001b[3mitalic\u001b[0m \u001b[4munderline\u001b[0m \u001b[7minverse\u001b[0m\r\n"
        )
    }

    /** Decodes UTF-8 one byte at a time. Returns a character when one is complete. */
    private class Utf8Accumulator {
        private val bytes = ByteArray(4)
        private var length = 0
        private var expected = 0

        fun add(b: Int): String? {
            if (length == 0) {
                expected =
                    when {
                        b < 0x80 -> 1
                        b >= 0xF0 -> 4
                        b >= 0xE0 -> 3
                        b >= 0xC0 -> 2
                        else -> return null
                    }
            }
            bytes[length++] = b.toByte()
            if (length < expected) return null
            val text = String(bytes, 0, length, Charsets.UTF_8)
            length = 0
            return text
        }
    }

    private companion object {
        const val BEL = 0x07
        const val BS = 0x08
        const val CR = 0x0D
        const val ESC = 0x1B
        const val DEL = 0x7F
        const val CTRL_C = 0x03
        const val CTRL_L = 0x0C

        /**
         * Leaves a replay for the shell, undoing what the recording may have set: the alternate
         * screen, whose leaving restores the cursor; then DECSTR, which resets the scroll region,
         * the attributes, the cursor's visibility, the charsets, autowrap, the cursor and keypad
         * keys, and the keyboard flags; then the modes that DECSTR leaves: reverse video, the mouse
         * (tracking and encodings), focus events, bracketed paste, and synchronized output; and the
         * cursor style, back to the app's. Otherwise taps after `replay vim` would go to this host
         * as mouse reports.
         */
        const val LEAVE_REPLAY =
            "\u001b[?1049l\u001b[!p" +
                "\u001b[?5;9;1000;1002;1003;1004;1005;1006;1015;2004;2026l\u001b[0 q\r\n"

        /** The size the recordings were made at (tools/recordings/record.py). */
        const val REPLAY_COLUMNS = 80
        const val REPLAY_ROWS = 24

        const val HELP =
            "Commands:\r\n" +
                "  help     this text\r\n" +
                "  clear    clear the screen\r\n" +
                "  colors   show the colors\r\n" +
                "  unicode  show wide and combining characters\r\n" +
                "  links    show links to tap\r\n" +
                "  size     show the terminal size\r\n" +
                "  replay   show a recorded app until a key: vim, less, htop, tmux,\r\n" +
                "           timg, icat, icat-placeholder, fastfetch (images)\r\n" +
                "  reset    reset the terminal\r\n" +
                "  mouse    send mouse reports (mouse off: stop)\r\n" +
                "  font     font import [FACE]: add a font file to the fonts, sanitized;\r\n" +
                "           font reset: back to the system font; font: list them\r\n" +
                "Anything else is echoed back.\r\n"

        /**
         * Links to try the confirmation of the app: OSC 8 hyperlinks, one whose text is its target,
         * one whose text names another site, as a spoofed link would, and one that the gate
         * refuses; then a URL in plain text, and text that the app's pattern links.
         */
        const val LINKS =
            "\u001b]8;;https://example.com/\u001b\\https://example.com/\u001b]8;;\u001b\\\r\n" +
                "\u001b]8;;https://example.org/\u001b\\https://example.com/ (goes elsewhere)\u001b]8;;\u001b\\\r\n" +
                "\u001b]8;;javascript:alert(1)\u001b\\a script (refused)\u001b]8;;\u001b\\\r\n" +
                "plain text: https://example.net/\r\n" +
                "the app's pattern: crbug/1\r\n"

        const val UNICODE =
            "日本語のテキスト、한국어 텍스트\r\n" +
                "骨 直 角 説 (Han glyphs that differ by locale)\r\n" +
                "é (combining), 😀 (emoji)\r\n" +
                "┌──┐ box drawing\r\n" +
                "└──┘\r\n"
    }
}
