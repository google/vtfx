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

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ParserTest {
    /** Records the actions as strings, merging consecutive printed characters. */
    private class Recorder : ParserHandler {
        val actions = mutableListOf<String>()
        private val text = StringBuilder()

        private fun flush() {
            if (text.isNotEmpty()) {
                actions += "print(${text})"
                text.setLength(0)
            }
        }

        override fun print(codePoint: Int) {
            text.appendCodePoint(codePoint)
        }

        override fun execute(control: Int) {
            flush()
            actions += "execute(%02x)".format(control)
        }

        override fun escDispatch(intermediates: Int, final: Int) {
            flush()
            actions += "esc(${chars(intermediates)}${final.toChar()})"
        }

        override fun csiDispatch(params: Params, prefix: Int, intermediates: Int, final: Int) {
            flush()
            val p = if (prefix == 0) "" else prefix.toChar().toString()
            actions += "csi($p$params${chars(intermediates)}${final.toChar()})"
        }

        override fun oscDispatch(data: CharSequence) {
            flush()
            actions += "osc($data)"
        }

        override fun dcsDispatch(
            params: Params,
            prefix: Int,
            intermediates: Int,
            final: Int,
            data: CharSequence,
        ) {
            flush()
            actions += "dcs($params${chars(intermediates)}${final.toChar()};$data)"
        }

        fun result(): List<String> {
            flush()
            return actions
        }

        private fun chars(packed: Int): String = buildString {
            if (packed > 0xFF) append((packed ushr 8).toChar())
            if (packed != 0) append((packed and 0xFF).toChar())
        }
    }

    private fun parse(vararg chunks: String, maxStringLength: Int = 100): List<String> {
        val recorder = Recorder()
        val parser = Parser(recorder, maxStringLength)
        for (chunk in chunks) parser.parse(chunk.toByteArray(Charsets.UTF_8))
        return recorder.result()
    }

    @Test
    fun printsTextAndExecutesControls() {
        assertEquals(
            listOf("print(ab)", "execute(0d)", "execute(0a)", "print(c)"),
            parse("ab\r\nc"),
        )
    }

    @Test
    fun ignoresDel() {
        assertEquals(listOf("print(ab)"), parse("a\u007fb"))
    }

    @Test
    fun dispatchesCsiWithParameters() {
        assertEquals(listOf("csi(1;22H)"), parse("\u001b[1;22H"))
        assertEquals(listOf("csi(H)"), parse("\u001b[H"))
        assertEquals(listOf("csi(0;5H)"), parse("\u001b[;5H"))
    }

    @Test
    fun dispatchesPrivateMarkerAndIntermediates() {
        assertEquals(listOf("csi(?1049h)"), parse("\u001b[?1049h"))
        assertEquals(listOf("csi(2 q)"), parse("\u001b[2 q"))
        assertEquals(listOf("csi(!p)"), parse("\u001b[!p"))
        assertEquals(listOf("csi(?2026\$p)"), parse("\u001b[?2026\$p"))
    }

    @Test
    fun keepsSubParameters() {
        assertEquals(listOf("csi(38:2:0:1:2:3m)"), parse("\u001b[38:2:0:1:2:3m"))
    }

    @Test
    fun ignoresPrivateMarkerAfterParameters() {
        assertEquals(listOf("print(x)"), parse("\u001b[1?hx"))
    }

    @Test
    fun ignoresSequencesWithTooManyIntermediates() {
        assertEquals(listOf("print(x)"), parse("\u001b[ !\"px"))
    }

    @Test
    fun aCharacterBeyondAsciiIsNotAFinalByte() {
        // U+1006D and U+10063 share their low 16 bits with 'm' and 'c'.
        assertEquals(listOf("print(x)"), parse("\u001b[31\uD800\uDC6Dx"))
        assertEquals(listOf("print(x)"), parse("\u001b\uD800\uDC63x"))
        assertEquals(listOf("print(x)"), parse("\u001b(\uD800\uDC30x"))
        assertEquals(listOf("print(x)"), parse("\u001b[1;2\u00e9x"))
    }

    @Test
    fun aTruncatedUtf8SequenceInACsiDoesNotTakeTheDigitsAfterIt() {
        val recorder = Recorder()
        val parser = Parser(recorder, 100)
        // ESC [, the lead byte of a two-byte sequence, then "31m": the digits end the sequence as
        // U+FFFD, which ends the control sequence, and are printed.
        parser.parse(byteArrayOf(0x1B, '['.code.toByte(), 0xC3.toByte()) + "31m".toByteArray())
        assertEquals(listOf("print(31m)"), recorder.result())
    }

    @Test
    fun clampsParameterValues() {
        assertEquals(listOf("csi(65535C)"), parse("\u001b[99999999999999999999C"))
    }

    @Test
    fun dropsParametersBeyondTheMaximum() {
        val many = (1..40).joinToString(";")
        val expected = (1..Params.MAX_COUNT).joinToString(";")
        assertEquals(listOf("csi(${expected}m)"), parse("\u001b[${many}m"))
    }

    @Test
    fun executesControlsInsideCsi() {
        assertEquals(listOf("execute(08)", "csi(1C)"), parse("\u001b[1\bC"))
    }

    @Test
    fun escapeRestartsSequence() {
        assertEquals(listOf("csi(3C)"), parse("\u001b[2\u001b[3C"))
    }

    @Test
    fun canCancelsSequence() {
        assertEquals(listOf("execute(18)", "print(Cx)"), parse("\u001b[2\u0018Cx"))
    }

    @Test
    fun dispatchesEscapeSequences() {
        assertEquals(listOf("esc(7)", "esc((0)", "esc(#8)"), parse("\u001b7\u001b(0\u001b#8"))
    }

    @Test
    fun dispatchesOscTerminatedByBelOrSt() {
        assertEquals(listOf("osc(0;title)", "print(a)"), parse("\u001b]0;title\u0007a"))
        assertEquals(listOf("osc(2;t)", "esc(\\)", "print(a)"), parse("\u001b]2;t\u001b\\a"))
    }

    @Test
    fun oscKeepsUtf8AndDropsControls() {
        assertEquals(listOf("osc(0;日本é)"), parse("\u001b]0;日\r本é\u0007"))
    }

    @Test
    fun discardsOscCancelledByCan() {
        assertEquals(listOf("execute(18)", "print(a)"), parse("\u001b]0;t\u0018a"))
    }

    @Test
    fun discardsOscLongerThanTheLimit() {
        assertEquals(listOf("print(a)"), parse("\u001b]0;123456789\u0007a", maxStringLength = 5))
        assertEquals(
            listOf("osc(0;12)", "print(a)"),
            parse("\u001b]0;12\u0007a", maxStringLength = 5),
        )
    }

    @Test
    fun countsTheStringLimitInUtf16Units() {
        // U+1F600 is two UTF-16 units: "0;" and two of them are 4 code points but 6 units.
        assertEquals(
            listOf("print(a)"),
            parse("\u001b]0;\uD83D\uDE00\uD83D\uDE00\u0007a", maxStringLength = 5),
        )
        assertEquals(
            listOf("osc(0;\uD83D\uDE00\uD83D\uDE00)", "print(a)"),
            parse("\u001b]0;\uD83D\uDE00\uD83D\uDE00\u0007a", maxStringLength = 6),
        )
    }

    @Test
    fun discardsAStringThatACharacterOfTwoUnitsTakesPastTheLimit() {
        // "0;1" is 3 units, one below the limit; U+1F600 would make it 5.
        assertEquals(
            listOf("print(a)"),
            parse("\u001b]0;1\uD83D\uDE00\u0007a", maxStringLength = 4),
        )
    }

    @Test
    fun dispatchesDcs() {
        assertEquals(listOf("dcs(\$q;m)", "esc(\\)"), parse("\u001bP\$qm\u001b\\"))
        assertEquals(
            listOf("dcs(1\$r;abc)", "esc(\\)", "print(b)"),
            parse("\u001bP1\$rabc\u001b\\b"),
        )
    }

    @Test
    fun ignoresSosPmApc() {
        assertEquals(listOf("esc(\\)", "print(a)"), parse("\u001b_hidden\u001b\\a"))
        assertEquals(listOf("esc(\\)", "print(a)"), parse("\u001b^x\u001b\\a"))
    }

    @Test
    fun ignoresC1Controls() {
        // U+009B would be CSI in an 8-bit environment.
        assertEquals(listOf("print(1Ca)"), parse("\u009b1Ca"))
    }

    @Test
    fun handlesSequencesSplitAcrossChunks() {
        assertEquals(listOf("csi(12;3H)", "print(あ)"), parse("\u001b[1", "2;", "3H", "あ"))
    }
}
