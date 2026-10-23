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

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalModes
import com.google.vtfx.testing.terminalModes
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class KeyEncoderTest {
    @Test
    fun encodes(@TestParameter(valuesProvider = Cases::class) case: Case) {
        val actual = String(KeyEncoder.encode(case.stroke, case.modes), Charsets.UTF_8)
        assertEquals(case.expected, actual.replace("\u001b", "\\e"))
    }

    /**
     * Every key, with any modifiers, under any modes, encodes to the shape of a key: a CSI or SS3
     * sequence, CR LF, or one character, optionally after ESC for Alt, or nothing. So no key can
     * send a string (OSC, DCS) or several characters, which the gate checked of the bytes it was
     * given before it encoded keys itself (docs/decisions/0005).
     */
    @Test
    fun everyKeyHasTheShapeOfAKey() {
        val modifiers =
            (0 until 16).map {
                KeyModifiers.of(
                    shift = it and 1 != 0,
                    alt = it and 2 != 0,
                    ctrl = it and 4 != 0,
                    meta = it and 8 != 0,
                )
            }
        val modes =
            (0 until 8).map {
                terminalModes(
                    applicationCursorKeys = it and 1 != 0,
                    applicationKeypad = it and 2 != 0,
                    newLine = it and 4 != 0,
                )
            }
        val codePoints = (0..0x3FF) + listOf(0x3042, 0xD800, 0xDFFF, 0xFFFD, 0x1F600, 0x10FFFF)
        val strokes =
            TerminalKey.entries.flatMap { key -> modifiers.map { KeyStroke.Special(key, it) } } +
                codePoints.flatMap { codePoint ->
                    modifiers.map { KeyStroke.Character(codePoint, it, codePoint, null) }
                }
        for (stroke in strokes) {
            for (mode in modes) {
                val bytes = KeyEncoder.encode(stroke, mode)
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                assertTrue(
                    bytes.size <= MAX_KEY_LENGTH && KEY_SHAPE.matches(text),
                    "$stroke under $mode: ${text.replace("\u001b", "\\e")}",
                )
            }
        }
    }

    private companion object {
        /** A bound on the length of a key. The longest, such as ESC [ 2 4 ; 1 6 ~, are 8 bytes. */
        private const val MAX_KEY_LENGTH = 16
        private val KEY_SHAPE =
            Regex("(?:\u001b?(?:\u001b\\[[0-9;]*[A-Za-z~]|\u001bO[A-Za-z]|\r\n|(?s:.)))?")

        private val normal = terminalModes()
        private val application = terminalModes(applicationCursorKeys = true)
        private val newLine = terminalModes(newLine = true)
        private val keypad = terminalModes(applicationKeypad = true)
        private val shift = KeyModifiers.of(shift = true)
        private val ctrl = KeyModifiers.of(ctrl = true)
        private val alt = KeyModifiers.of(alt = true)
        private val ctrlShift = KeyModifiers.of(ctrl = true, shift = true)

        private fun special(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.None) =
            KeyStroke.Special(key, modifiers)

        /** A character key with no place on a keyboard, as a key bar of an app sends. */
        private fun char(c: Char, modifiers: KeyModifiers = KeyModifiers.None) =
            KeyStroke.Character(c.code, modifiers, Character.toLowerCase(c.code), null)

        private fun case(
            stroke: KeyStroke,
            expected: String,
            modes: TerminalModes = normal,
            modeName: String = "",
        ) = Case(stroke, modes, modeName, expected)

        fun cases(): List<Case> =
            listOf(
                case(special(TerminalKey.Up), "\\e[A"),
                case(special(TerminalKey.Left), "\\e[D"),
                case(special(TerminalKey.Up), "\\eOA", application, "DECCKM"),
                case(special(TerminalKey.Home), "\\e[H"),
                case(special(TerminalKey.End), "\\eOF", application, "DECCKM"),
                case(special(TerminalKey.Right, ctrl), "\\e[1;5C"),
                case(special(TerminalKey.Right, ctrl), "\\e[1;5C", application, "DECCKM"),
                case(special(TerminalKey.Up, ctrlShift), "\\e[1;6A"),
                case(special(TerminalKey.Insert), "\\e[2~"),
                case(special(TerminalKey.Delete), "\\e[3~"),
                case(special(TerminalKey.Delete, shift), "\\e[3;2~"),
                case(special(TerminalKey.PageUp), "\\e[5~"),
                case(special(TerminalKey.PageDown, alt), "\\e[6;3~"),
                case(special(TerminalKey.F1), "\\eOP"),
                case(special(TerminalKey.F4, shift), "\\e[1;2S"),
                case(special(TerminalKey.F5), "\\e[15~"),
                case(special(TerminalKey.F12, ctrl), "\\e[24;5~"),
                case(special(TerminalKey.Escape), "\\e"),
                case(special(TerminalKey.Escape, alt), "\\e\\e"),
                case(special(TerminalKey.Tab), "\t"),
                case(special(TerminalKey.Tab, shift), "\\e[Z"),
                case(special(TerminalKey.Enter), "\r"),
                case(special(TerminalKey.Enter), "\r\n", newLine, "LNM"),
                case(special(TerminalKey.Enter, alt), "\\e\r"),
                case(special(TerminalKey.Backspace), "\u007f"),
                case(special(TerminalKey.Backspace, ctrl), "\b"),
                case(special(TerminalKey.Backspace, alt), "\\e\u007f"),
                case(char('a'), "a"),
                case(char('あ'), "あ"),
                case(char('c', ctrl), "\u0003"),
                case(char('C', ctrl), "\u0003"),
                case(char(' ', ctrl), "\u0000"),
                case(char('[', ctrl), "\\e"),
                case(char('\\', ctrl), "\u001c"),
                case(char(']', ctrl), "\u001d"),
                case(char('_', ctrl), "\u001f"),
                case(char('?', ctrl), "\u007f"),
                case(char('2', ctrl), "\u0000"),
                case(char('x', alt), "\\ex"),
                case(char('c', KeyModifiers.of(ctrl = true, alt = true)), "\\e\u0003"),
                case(char('é', ctrl), "é"),
                // Controls are dropped, as from text, so that a character key cannot carry ESC or
                // C1 CSI; only Ctrl makes a control from a character.
                case(char('\u001b'), ""),
                case(char('\u001b', alt), ""),
                case(char('\u0000'), ""),
                case(char('\r', ctrl), ""),
                case(char('\u007f'), ""),
                case(char('\u009b'), ""),
                // Unpaired surrogates become U+FFFD, as in text.
                case(char('\ud800'), "\ufffd"),
                case(char('\udc00', alt), "\\e\ufffd"),
                case(special(TerminalKey.Keypad5), "5"),
                case(special(TerminalKey.KeypadPlus), "+"),
                case(special(TerminalKey.KeypadEnter), "\r"),
                case(special(TerminalKey.KeypadEnter), "\r\n", newLine, "LNM"),
                case(special(TerminalKey.Keypad5), "\\eOu", keypad, "DECKPAM"),
                case(special(TerminalKey.KeypadEnter), "\\eOM", keypad, "DECKPAM"),
                case(special(TerminalKey.KeypadMultiply, shift), "\\e[1;2j", keypad, "DECKPAM"),
            )
    }

    /** A key under [modes], named [modeName], and what it encodes to, with ESC as `\e`. */
    class Case(
        val stroke: KeyStroke,
        val modes: TerminalModes,
        val modeName: String,
        val expected: String,
    )

    private class Cases : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<*> =
            cases().map { value(it).withName("${it.stroke} ${it.modeName} -> ${it.expected}") }
    }
}
