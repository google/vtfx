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

package com.google.vtfx.core.parser

/** Receives the actions of [Parser]. The names follow the DEC ANSI parser state machine. */
internal interface ParserHandler {
    /** Displays a graphic character. */
    fun print(codePoint: Int)

    /**
     * Displays the printable ASCII characters (0x20 to 0x7E) in [from, to) of [bytes]. The same as
     * [print] for each, for the common case of plain text.
     */
    fun printAscii(bytes: ByteArray, from: Int, to: Int) {
        for (i in from until to) print(bytes[i].toInt())
    }

    /** Executes a C0 control function. */
    fun execute(control: Int)

    /**
     * Dispatches an escape sequence. [intermediates] packs up to two intermediate bytes, the first
     * in bits 8-15.
     */
    fun escDispatch(intermediates: Int, final: Int)

    /**
     * Dispatches a control sequence. [prefix] is the private parameter marker ('<', '=', '>', or
     * '?') or 0.
     */
    fun csiDispatch(params: Params, prefix: Int, intermediates: Int, final: Int)

    /** Dispatches an operating system command. Not called if the string exceeded the limit. */
    fun oscDispatch(data: CharSequence)

    /** Dispatches a device control string. Not called if the data exceeded the limit. */
    fun dcsDispatch(params: Params, prefix: Int, intermediates: Int, final: Int, data: CharSequence)

    /**
     * Dispatches an application program command, such as a kitty graphics command. Not called if
     * the string exceeded the limit.
     */
    fun apcDispatch(data: CharSequence)
}

/**
 * Splits host output into control functions and graphic characters.
 *
 * The state machine is Paul Williams' DEC ANSI parser (https://vt100.net/emu/dec_ansi_parser),
 * applied to code points after UTF-8 decoding, with these deviations (docs/decisions/0079):
 * - C1 controls (U+0080 to U+009F) are ignored, since in UTF-8 they can only arrive as two-byte
 *   sequences that legacy software does not produce.
 * - ':' in parameters separates sub-parameters instead of invalidating the sequence.
 * - OSC is also terminated by BEL, as in xterm.
 * - A character beyond ASCII where an escape or control sequence expects its final byte ends the
 *   sequence without dispatching it.
 *
 * Strings (OSC, DCS, and APC) longer than [maxStringLength] UTF-16 code units are discarded. APC is
 * ended only by ST, and its control characters are dropped; SOS and PM are ignored. Every state
 * handles every input in constant time, so the parser always makes progress.
 */
internal class Parser(
    private val handler: ParserHandler,
    private val maxStringLength: Int,
) : CodePointSink {
    private val decoder = Utf8Decoder()
    private val params = Params()
    private val string = StringBuilder()
    private var stringOverflowed = false
    private var state = GROUND
    private var prefix = 0
    private var intermediates = 0
    private var intermediatesOverflowed = false
    private var dcsFinal = 0

    fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val end = offset + length
        var i = offset
        while (i < end) {
            if (state == GROUND && decoder.isIdle) {
                // A run of printable ASCII in the ground state goes to the handler at once.
                var j = i
                while (j < end && bytes[j] in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST) j++
                if (j > i) {
                    handler.printAscii(bytes, i, j)
                    i = j
                    continue
                }
                // Decode up to the next printable ASCII byte, which may start another run.
                j = i + 1
                while (j < end && bytes[j] !in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST) j++
                decoder.decode(bytes, i, j - i, this)
                i = j
            } else if (
                state == APC_STRING &&
                    decoder.isIdle &&
                    bytes[i] in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST
            ) {
                // The base64 payload of graphics commands, which can be megabytes, goes in runs.
                var j = i + 1
                while (j < end && bytes[j] in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST) j++
                putAscii(bytes, i, j)
                i = j
            } else {
                val byte = bytes[i].toInt()
                if (
                    (state == CSI_ENTRY || state == CSI_PARAM) &&
                        byte in '0'.code..';'.code &&
                        decoder.isIdle
                ) {
                    // Parameters, the bulk of control sequences such as SGR, taken up to the
                    // first other byte. A pending UTF-8 sequence goes first, which the byte ends
                    // as U+FFFD.
                    i = params.accept(bytes, i, end)
                    state = CSI_PARAM
                    continue
                } else if (byte >= 0 && decoder.isIdle) {
                    // Inside a sequence, ASCII needs no decoding.
                    advance(byte)
                } else {
                    decoder.decode(bytes, i, 1, this)
                }
                i++
            }
        }
    }

    /**
     * Returns to the ground state, dropping a sequence, a string, or a UTF-8 sequence in progress
     * without dispatching it.
     */
    fun reset() {
        decoder.reset()
        clear()
        string.setLength(0)
        stringOverflowed = false
        state = GROUND
    }

    override fun accept(codePoint: Int) {
        // In the ground state, a non-ASCII graphic character is printed whatever else holds.
        if (state == GROUND && codePoint >= 0xA0) {
            handler.print(codePoint)
            return
        }
        advance(codePoint)
    }

    private fun advance(c: Int) {
        // Transitions from anywhere.
        when (c) {
            CAN,
            SUB -> {
                // Unlike the original state machine, cancelling a string discards it, as in xterm.
                string.setLength(0)
                stringOverflowed = false
                handler.execute(c)
                state = GROUND
                return
            }
            ESC -> {
                exitState()
                enterEscape()
                return
            }
        }
        if (c in 0x80..0x9F) return

        when (state) {
            GROUND ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c == DEL -> {}
                    else -> handler.print(c)
                }
            ESCAPE ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c in 0x20..0x2F -> {
                        collect(c)
                        state = ESCAPE_INTERMEDIATE
                    }
                    c == '['.code -> enterCsi()
                    c == ']'.code -> enterString(OSC_STRING)
                    c == 'P'.code -> enterDcs()
                    c == '_'.code -> enterString(APC_STRING)
                    c == 'X'.code || c == '^'.code -> state = SOS_PM_STRING
                    c == DEL -> {}
                    else -> escDispatch(c)
                }
            ESCAPE_INTERMEDIATE ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c in 0x20..0x2F -> collect(c)
                    c == DEL -> {}
                    else -> escDispatch(c)
                }
            CSI_ENTRY ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c in 0x20..0x2F -> {
                        collect(c)
                        state = CSI_INTERMEDIATE
                    }
                    c in 0x30..0x3B -> {
                        param(c)
                        state = CSI_PARAM
                    }
                    c in 0x3C..0x3F -> {
                        prefix = c
                        state = CSI_PARAM
                    }
                    c == DEL -> {}
                    else -> csiDispatch(c)
                }
            CSI_PARAM ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c in 0x20..0x2F -> {
                        collect(c)
                        state = CSI_INTERMEDIATE
                    }
                    c in 0x30..0x3B -> param(c)
                    c in 0x3C..0x3F -> state = CSI_IGNORE
                    c == DEL -> {}
                    else -> csiDispatch(c)
                }
            CSI_INTERMEDIATE ->
                when {
                    c < 0x20 -> handler.execute(c)
                    c in 0x20..0x2F -> collect(c)
                    c in 0x30..0x3F -> state = CSI_IGNORE
                    c == DEL -> {}
                    else -> csiDispatch(c)
                }
            CSI_IGNORE ->
                when {
                    c < 0x20 -> handler.execute(c)
                    // A character beyond ASCII ends the sequence, as it does in the other states.
                    c in 0x40..0x7E || c > DEL -> state = GROUND
                    else -> {}
                }
            DCS_ENTRY ->
                when {
                    c < 0x20 -> {}
                    c in 0x20..0x2F -> {
                        collect(c)
                        state = DCS_INTERMEDIATE
                    }
                    c in 0x30..0x3B -> {
                        param(c)
                        state = DCS_PARAM
                    }
                    c in 0x3C..0x3F -> {
                        prefix = c
                        state = DCS_PARAM
                    }
                    c == DEL -> {}
                    else -> dcsHook(c)
                }
            DCS_PARAM ->
                when {
                    c < 0x20 -> {}
                    c in 0x20..0x2F -> {
                        collect(c)
                        state = DCS_INTERMEDIATE
                    }
                    c in 0x30..0x3B -> param(c)
                    c in 0x3C..0x3F -> state = DCS_IGNORE
                    c == DEL -> {}
                    else -> dcsHook(c)
                }
            DCS_INTERMEDIATE ->
                when {
                    c < 0x20 -> {}
                    c in 0x20..0x2F -> collect(c)
                    c in 0x30..0x3F -> state = DCS_IGNORE
                    c == DEL -> {}
                    else -> dcsHook(c)
                }
            DCS_PASSTHROUGH -> if (c != DEL) put(c)
            DCS_IGNORE -> {}
            OSC_STRING ->
                when (c) {
                    BEL -> {
                        exitState()
                        state = GROUND
                    }
                    in 0x00..0x1F,
                    DEL -> {}
                    else -> put(c)
                }
            APC_STRING -> if (c >= 0x20 && c != DEL) put(c)
            SOS_PM_STRING -> {}
        }
    }

    /** Runs the exit action of the current state, before a transition from anywhere. */
    private fun exitState() {
        when (state) {
            OSC_STRING -> if (!stringOverflowed) handler.oscDispatch(string)
            DCS_PASSTHROUGH ->
                if (!stringOverflowed && !intermediatesOverflowed) {
                    handler.dcsDispatch(params, prefix, intermediates, dcsFinal, string)
                }
            APC_STRING -> if (!stringOverflowed) handler.apcDispatch(string)
        }
        if (string.isNotEmpty()) string.setLength(0)
        stringOverflowed = false
    }

    private fun clear() {
        params.clear()
        prefix = 0
        intermediates = 0
        intermediatesOverflowed = false
    }

    private fun enterEscape() {
        clear()
        state = ESCAPE
    }

    private fun enterCsi() {
        clear()
        state = CSI_ENTRY
    }

    private fun enterDcs() {
        clear()
        state = DCS_ENTRY
    }

    private fun enterString(newState: Int) {
        string.setLength(0)
        stringOverflowed = false
        state = newState
    }

    private fun collect(c: Int) {
        if (intermediates > 0xFF) {
            intermediatesOverflowed = true
        } else {
            intermediates = (intermediates shl 8) or c
        }
    }

    private fun param(c: Int) {
        when (c) {
            ';'.code -> params.nextParameter(isSubParameter = false)
            ':'.code -> params.nextParameter(isSubParameter = true)
            else -> params.addDigit(c - '0'.code)
        }
    }

    private fun put(c: Int) {
        if (stringOverflowed) return
        if (string.length + Character.charCount(c) > maxStringLength) {
            stringOverflowed = true
            string.setLength(0)
            return
        }
        string.appendCodePoint(c)
    }

    /** Puts the printable ASCII characters in [from, to) of [bytes], as [put] does for each. */
    private fun putAscii(bytes: ByteArray, from: Int, to: Int) {
        if (stringOverflowed) return
        if (string.length + (to - from) > maxStringLength) {
            stringOverflowed = true
            string.setLength(0)
            return
        }
        for (k in from until to) string.append(bytes[k].toInt().toChar())
    }

    private fun escDispatch(final: Int) {
        state = GROUND
        // Final bytes are ASCII. Other characters end the sequence, rather than being taken for
        // the ASCII character that shares their low 16 bits (U+1006D for 'm').
        if (final > ASCII_LAST) return
        if (!intermediatesOverflowed) handler.escDispatch(intermediates, final)
    }

    private fun csiDispatch(final: Int) {
        state = GROUND
        if (final > ASCII_LAST) return
        params.finish()
        if (!intermediatesOverflowed) handler.csiDispatch(params, prefix, intermediates, final)
    }

    private fun dcsHook(final: Int) {
        // As in escDispatch: without this, a character beyond ASCII (U+FFFD from a truncated UTF-8
        // sequence) would start a string that takes the output after it.
        if (final > ASCII_LAST) {
            state = GROUND
            return
        }
        params.finish()
        dcsFinal = final
        enterString(DCS_PASSTHROUGH)
    }

    companion object {
        private const val PRINTABLE_ASCII_FIRST: Byte = 0x20
        private const val PRINTABLE_ASCII_LAST: Byte = 0x7E
        private const val BEL = 0x07
        private const val CAN = 0x18
        private const val SUB = 0x1A
        private const val ESC = 0x1B
        private const val DEL = 0x7F
        private const val ASCII_LAST = 0x7E

        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI_ENTRY = 3
        private const val CSI_PARAM = 4
        private const val CSI_INTERMEDIATE = 5
        private const val CSI_IGNORE = 6
        private const val DCS_ENTRY = 7
        private const val DCS_PARAM = 8
        private const val DCS_INTERMEDIATE = 9
        private const val DCS_PASSTHROUGH = 10
        private const val DCS_IGNORE = 11
        private const val OSC_STRING = 12
        private const val SOS_PM_STRING = 13
        private const val APC_STRING = 14
    }
}
