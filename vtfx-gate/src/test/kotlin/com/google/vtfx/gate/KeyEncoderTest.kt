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
     * given before it encoded keys itself (docs/decisions/0005). This holds for the forms of the
     * kitty keyboard protocol too, whose CSI u carries a code point only in digits
     * (docs/decisions/0070).
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
            (0 until 32).map {
                terminalModes(
                    applicationCursorKeys = it and 1 != 0,
                    applicationKeypad = it and 2 != 0,
                    newLine = it and 4 != 0,
                    disambiguateEscapeCodes = it and 8 != 0,
                    reportAlternateKeys = it and 16 != 0,
                )
            }
        val codePoints = (0..0x3FF) + listOf(0x3042, 0xD800, 0xDFFF, 0xFFFD, 0x1F600, 0x10FFFF)
        // Unshifted keys and keys of the base layout that differ from the character.
        val others = listOf(0, 0x1B, 0x41, 0x61, 0x9B, 0xD800, 0xDC00, 0x1F600, 0x10FFFF)
        val strokes =
            TerminalKey.entries.flatMap { key -> modifiers.map { KeyStroke.Special(key, it) } } +
                codePoints.flatMap { codePoint ->
                    modifiers.map { KeyStroke.Character(codePoint, it, codePoint, null) }
                } +
                others.flatMap { codePoint ->
                    others.flatMap { other ->
                        modifiers.map { KeyStroke.Character(codePoint, it, other, codePoint) } +
                            modifiers.map { KeyStroke.Character(codePoint, it, other, other) }
                    }
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
        /**
         * A bound on the length of a key. The longest, such as ESC [ 1 1 1 4 1 1 1 : 1 1 1 4 1 1 1
         * : 1 1 1 4 1 1 1 ; 1 6 u for U+10FFFF with every modifier and both alternate keys in the
         * kitty keyboard protocol, are 29 bytes.
         */
        private const val MAX_KEY_LENGTH = 29
        private val KEY_SHAPE =
            Regex("(?:\u001b?(?:\u001b\\[[0-9;:]*[A-Za-z~]|\u001bO[A-Za-z]|\r\n|(?s:.)))?")

        private val normal = terminalModes()
        private val application = terminalModes(applicationCursorKeys = true)
        private val newLine = terminalModes(newLine = true)
        private val keypad = terminalModes(applicationKeypad = true)
        private val kitty = terminalModes(disambiguateEscapeCodes = true)
        private val kittyApplication =
            terminalModes(
                applicationCursorKeys = true,
                applicationKeypad = true,
                disambiguateEscapeCodes = true,
            )
        private val kittyNewLine = terminalModes(newLine = true, disambiguateEscapeCodes = true)
        private val alternateKeys =
            terminalModes(disambiguateEscapeCodes = true, reportAlternateKeys = true)
        private val onlyAlternateKeys = terminalModes(reportAlternateKeys = true)
        private val shift = KeyModifiers.of(shift = true)
        private val ctrl = KeyModifiers.of(ctrl = true)
        private val alt = KeyModifiers.of(alt = true)
        private val ctrlShift = KeyModifiers.of(ctrl = true, shift = true)
        private val altShift = KeyModifiers.of(alt = true, shift = true)
        private val meta = KeyModifiers.of(meta = true)

        private fun special(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.None) =
            KeyStroke.Special(key, modifiers)

        /** A character key with no place on a keyboard, as a key bar of an app sends. */
        private fun char(c: Char, modifiers: KeyModifiers = KeyModifiers.None) =
            KeyStroke.Character(c.code, modifiers, Character.toLowerCase(c.code), null)

        /** A character key whose key without modifiers is [unshifted]. */
        private fun key(c: Char, unshifted: Char, modifiers: KeyModifiers) =
            KeyStroke.Character(c.code, modifiers, unshifted.code, null)

        /** A character key with the key [base] at its place on a US keyboard. */
        private fun key(c: Char, unshifted: Char, base: Char, modifiers: KeyModifiers) =
            KeyStroke.Character(c.code, modifiers, unshifted.code, base.code)

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
                // The keypad's keys with NumLock off are the digits at their places.
                case(special(TerminalKey.KeypadLeft), "4"),
                case(special(TerminalKey.KeypadDelete), "."),
                case(special(TerminalKey.KeypadBegin), "\\eOu", keypad, "DECKPAM"),
                case(special(TerminalKey.KeypadInsert, ctrl), "\\e[1;5p", keypad, "DECKPAM"),
            ) + kittyCases()

        /**
         * Flag 1 of the kitty keyboard protocol, as kitty encodes the keys (docs/decisions/0070).
         */
        private fun kittyCases(): List<Case> {
            fun kitty(stroke: KeyStroke, expected: String) = case(stroke, expected, kitty, "kitty")
            return listOf(
                kitty(special(TerminalKey.Escape), "\\e[27u"),
                kitty(special(TerminalKey.Escape, alt), "\\e[27;3u"),
                // Enter, Tab, and Backspace keep their legacy bytes without modifiers.
                kitty(special(TerminalKey.Enter), "\r"),
                case(special(TerminalKey.Enter), "\r\n", kittyNewLine, "kitty LNM"),
                kitty(special(TerminalKey.Enter, shift), "\\e[13;2u"),
                kitty(special(TerminalKey.Enter, ctrl), "\\e[13;5u"),
                kitty(special(TerminalKey.Tab), "\t"),
                kitty(special(TerminalKey.Tab, shift), "\\e[9;2u"),
                kitty(special(TerminalKey.Tab, ctrlShift), "\\e[9;6u"),
                kitty(special(TerminalKey.Backspace), "\u007f"),
                kitty(special(TerminalKey.Backspace, ctrl), "\\e[127;5u"),
                kitty(special(TerminalKey.Backspace, alt), "\\e[127;3u"),
                kitty(special(TerminalKey.Up), "\\e[A"),
                kitty(special(TerminalKey.Up, ctrl), "\\e[1;5A"),
                // DECCKM does not apply.
                case(special(TerminalKey.Up), "\\e[A", kittyApplication, "kitty DECCKM"),
                case(special(TerminalKey.Home), "\\e[H", kittyApplication, "kitty DECCKM"),
                kitty(special(TerminalKey.End, shift), "\\e[1;2F"),
                kitty(special(TerminalKey.Delete, shift), "\\e[3;2~"),
                kitty(special(TerminalKey.PageUp), "\\e[5~"),
                kitty(special(TerminalKey.F1), "\\e[P"),
                kitty(special(TerminalKey.F1, shift), "\\e[1;2P"),
                kitty(special(TerminalKey.F3), "\\e[13~"),
                kitty(special(TerminalKey.F3, ctrl), "\\e[13;5~"),
                kitty(special(TerminalKey.F4), "\\e[S"),
                kitty(special(TerminalKey.F12, meta), "\\e[24;9~"),
                // Characters without Ctrl, Alt, or Meta are text.
                kitty(char('a'), "a"),
                kitty(char('A', shift), "A"),
                kitty(char('c', ctrl), "\\e[99;5u"),
                kitty(char('i', ctrl), "\\e[105;5u"),
                kitty(char('[', ctrl), "\\e[91;5u"),
                kitty(char(' ', ctrl), "\\e[32;5u"),
                kitty(char('x', alt), "\\e[120;3u"),
                kitty(char('x', meta), "\\e[120;9u"),
                // The code is the unshifted key: Shift is reported if held, and Caps Lock is not.
                kitty(key('A', 'a', ctrlShift), "\\e[97;6u"),
                kitty(key('A', 'a', altShift), "\\e[97;4u"),
                kitty(key('C', 'c', ctrl), "\\e[99;5u"),
                kitty(key('!', '1', ctrlShift), "\\e[49;6u"),
                kitty(key('Я', 'я', ctrlShift), "\\e[1103;6u"),
                kitty(char('é', ctrl), "\\e[233;5u"),
                // Controls send nothing and unpaired surrogates are U+FFFD, as in the legacy keys.
                kitty(char('\u001b', ctrl), ""),
                kitty(char('\u009b', alt), ""),
                kitty(char('\ud800', alt), "\\e[65533;3u"),
                kitty(key('a', '\udc00', ctrl), "\\e[65533;5u"),
                // The keypad's keys that produce text send it unless Ctrl, Alt, or Meta is held.
                kitty(special(TerminalKey.Keypad5), "5"),
                kitty(special(TerminalKey.Keypad5, shift), "5"),
                kitty(special(TerminalKey.Keypad5, ctrl), "\\e[57404;5u"),
                kitty(special(TerminalKey.KeypadDivide, alt), "\\e[57410;3u"),
                kitty(special(TerminalKey.KeypadEquals, meta), "\\e[57415;9u"),
                // DECKPAM does not apply.
                case(special(TerminalKey.Keypad0), "0", kittyApplication, "kitty DECKPAM"),
                case(special(TerminalKey.KeypadPlus), "+", kittyApplication, "kitty DECKPAM"),
                // The keys with NumLock off produce no text.
                kitty(special(TerminalKey.KeypadLeft), "\\e[57417u"),
                kitty(special(TerminalKey.KeypadRight), "\\e[57418u"),
                kitty(special(TerminalKey.KeypadUp), "\\e[57419u"),
                kitty(special(TerminalKey.KeypadDown), "\\e[57420u"),
                kitty(special(TerminalKey.KeypadPageUp), "\\e[57421u"),
                kitty(special(TerminalKey.KeypadPageDown), "\\e[57422u"),
                kitty(special(TerminalKey.KeypadHome), "\\e[57423u"),
                kitty(special(TerminalKey.KeypadEnd), "\\e[57424u"),
                kitty(special(TerminalKey.KeypadInsert), "\\e[57425u"),
                kitty(special(TerminalKey.KeypadDelete, shift), "\\e[57426;2u"),
                kitty(special(TerminalKey.KeypadBegin), "\\e[57427u"),
                kitty(special(TerminalKey.KeypadEnter), "\\e[57414u"),
                kitty(special(TerminalKey.KeypadEnter, shift), "\\e[57414;2u"),
            ) + alternateKeyCases()
        }

        /** Flag 4 of the kitty keyboard protocol, report alternate keys (docs/decisions/0070). */
        private fun alternateKeyCases(): List<Case> {
            fun alternate(stroke: KeyStroke, expected: String) =
                case(stroke, expected, alternateKeys, "kitty alternate keys")
            return listOf(
                // The shifted key, only with Shift and when it differs.
                alternate(key('!', '1', '1', ctrlShift), "\\e[49:33;6u"),
                alternate(key('A', 'a', 'a', ctrlShift), "\\e[97:65;6u"),
                alternate(key('C', 'c', 'c', ctrl), "\\e[99;5u"),
                alternate(key('1', '1', '1', ctrlShift), "\\e[49;6u"),
                // The key of the base layout, only when it is known and differs.
                alternate(key('с', 'с', 'c', ctrl), "\\e[1089::99;5u"),
                alternate(key('С', 'с', 'c', ctrlShift), "\\e[1089:1057:99;6u"),
                alternate(key('a', 'a', 'q', alt), "\\e[97::113;3u"),
                alternate(key('c', 'c', ctrl), "\\e[99;5u"),
                // Unpaired surrogates are U+FFFD.
                alternate(key('\ud800', 'a', '\udc00', ctrlShift), "\\e[97:65533:65533;6u"),
                // Keys that are not sent as CSI u do not change.
                alternate(key('A', 'a', 'q', shift), "A"),
                alternate(special(TerminalKey.Escape, ctrlShift), "\\e[27;6u"),
                alternate(special(TerminalKey.Keypad1, ctrl), "\\e[57400;5u"),
                // Without flag 1, the legacy encoding has no CSI u.
                case(key('!', '1', '1', ctrlShift), "!", onlyAlternateKeys, "alternate keys alone"),
                case(key('c', 'c', 'c', ctrl), "\u0003", onlyAlternateKeys, "alternate keys alone"),
            )
        }
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
