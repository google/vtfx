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

package com.google.vtfx.gate

import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalModes

/**
 * Encodes key strokes into the bytes that xterm sends for them (xterm ctlseqs, "PC-Style Function
 * Keys"). The only inputs are the key, the modifiers, and the terminal modes: no text from the host
 * can reach the output.
 */
internal object KeyEncoder {
    private const val ESC = 0x1B
    private const val REPLACEMENT_CHARACTER = 0xFFFD

    /** Returns the bytes for [stroke] under [modes]. */
    fun encode(stroke: KeyStroke, modes: TerminalModes): ByteArray =
        when (stroke) {
            is KeyStroke.Special -> encodeSpecial(stroke.key, stroke.modifiers, modes)
            is KeyStroke.Character -> encodeCharacter(stroke.codePoint, stroke.modifiers)
        }

    private fun encodeSpecial(
        key: TerminalKey,
        m: KeyModifiers,
        modes: TerminalModes,
    ): ByteArray =
        when (key) {
            TerminalKey.Escape -> withAlt(byteArrayOf(ESC.toByte()), m)
            TerminalKey.Tab -> if (m.shift) csi("Z") else withAlt(byteArrayOf(0x09), m)
            TerminalKey.Enter ->
                withAlt(if (modes.newLine) byteArrayOf(0x0D, 0x0A) else byteArrayOf(0x0D), m)
            // xterm sends DEL for Backspace, and BS with Ctrl.
            TerminalKey.Backspace -> withAlt(byteArrayOf(if (m.ctrl) 0x08 else 0x7F), m)
            TerminalKey.Up -> cursorKey('A', m, modes)
            TerminalKey.Down -> cursorKey('B', m, modes)
            TerminalKey.Right -> cursorKey('C', m, modes)
            TerminalKey.Left -> cursorKey('D', m, modes)
            TerminalKey.Home -> cursorKey('H', m, modes)
            TerminalKey.End -> cursorKey('F', m, modes)
            TerminalKey.Insert -> tilde(2, m)
            TerminalKey.Delete -> tilde(3, m)
            TerminalKey.PageUp -> tilde(5, m)
            TerminalKey.PageDown -> tilde(6, m)
            TerminalKey.F1 -> ss3OrModified('P', m)
            TerminalKey.F2 -> ss3OrModified('Q', m)
            TerminalKey.F3 -> ss3OrModified('R', m)
            TerminalKey.F4 -> ss3OrModified('S', m)
            TerminalKey.F5 -> tilde(15, m)
            TerminalKey.F6 -> tilde(17, m)
            TerminalKey.F7 -> tilde(18, m)
            TerminalKey.F8 -> tilde(19, m)
            TerminalKey.F9 -> tilde(20, m)
            TerminalKey.F10 -> tilde(21, m)
            TerminalKey.F11 -> tilde(23, m)
            TerminalKey.F12 -> tilde(24, m)
            TerminalKey.Keypad0 -> keypad('0', 'p', m, modes)
            TerminalKey.Keypad1 -> keypad('1', 'q', m, modes)
            TerminalKey.Keypad2 -> keypad('2', 'r', m, modes)
            TerminalKey.Keypad3 -> keypad('3', 's', m, modes)
            TerminalKey.Keypad4 -> keypad('4', 't', m, modes)
            TerminalKey.Keypad5 -> keypad('5', 'u', m, modes)
            TerminalKey.Keypad6 -> keypad('6', 'v', m, modes)
            TerminalKey.Keypad7 -> keypad('7', 'w', m, modes)
            TerminalKey.Keypad8 -> keypad('8', 'x', m, modes)
            TerminalKey.Keypad9 -> keypad('9', 'y', m, modes)
            TerminalKey.KeypadDecimal -> keypad('.', 'n', m, modes)
            TerminalKey.KeypadPlus -> keypad('+', 'k', m, modes)
            TerminalKey.KeypadMinus -> keypad('-', 'm', m, modes)
            TerminalKey.KeypadMultiply -> keypad('*', 'j', m, modes)
            TerminalKey.KeypadDivide -> keypad('/', 'o', m, modes)
            TerminalKey.KeypadEquals -> keypad('=', 'X', m, modes)
            // Enter follows LNM in numeric mode.
            TerminalKey.KeypadEnter ->
                if (modes.applicationKeypad) ss3OrModified('M', m)
                else encodeSpecial(TerminalKey.Enter, m, modes)
        }

    /**
     * Keypad keys send their [character], or with DECKPAM (application keypad), SS3 and the letter
     * [final], as xterm does with NumLock off.
     */
    private fun keypad(
        character: Char,
        final: Char,
        m: KeyModifiers,
        modes: TerminalModes,
    ): ByteArray =
        if (modes.applicationKeypad) ss3OrModified(final, m) else encodeCharacter(character.code, m)

    /** Cursor keys and Home/End: SS3 in application cursor mode, CSI 1;m with modifiers. */
    private fun cursorKey(final: Char, m: KeyModifiers, modes: TerminalModes): ByteArray =
        when {
            !m.isEmpty -> csi("1;${m.xtermParameter}$final")
            modes.applicationCursorKeys -> ss3(final)
            else -> csi("$final")
        }

    private fun ss3OrModified(final: Char, m: KeyModifiers): ByteArray =
        if (m.isEmpty) ss3(final) else csi("1;${m.xtermParameter}$final")

    private fun tilde(code: Int, m: KeyModifiers): ByteArray =
        if (m.isEmpty) csi("$code~") else csi("$code;${m.xtermParameter}~")

    /**
     * Encodes a character key. Only Ctrl makes a control: a key whose character is itself a control
     * (C0, DEL, or C1) encodes to nothing, as sendText drops it, since the code point comes from
     * the app or the IME, not from an enum. An unpaired surrogate becomes U+FFFD, as in text.
     */
    private fun encodeCharacter(codePoint: Int, m: KeyModifiers): ByteArray {
        val base = if (m.ctrl) controlCharacter(codePoint) else null
        if (base == null && isControl(codePoint)) return ByteArray(0)
        val bytes = if (base != null) byteArrayOf(base.toByte()) else utf8(codePoint)
        return withAlt(bytes, m)
    }

    private fun isControl(codePoint: Int): Boolean = codePoint < 0x20 || codePoint in 0x7F..0x9F

    /** Returns the C0 control that Ctrl with [codePoint] produces, as in xterm, or null. */
    private fun controlCharacter(codePoint: Int): Int? =
        when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in '@'.code..'_'.code -> codePoint - '@'.code
            ' '.code,
            '2'.code -> 0x00
            '3'.code -> 0x1B
            '4'.code -> 0x1C
            '5'.code -> 0x1D
            '6'.code -> 0x1E
            '7'.code,
            '/'.code -> 0x1F
            '8'.code,
            '?'.code -> 0x7F
            else -> null
        }

    /** Alt (and Meta) prefix the bytes with ESC, as xterm does with metaSendsEscape. */
    private fun withAlt(bytes: ByteArray, m: KeyModifiers): ByteArray =
        if (m.alt || m.meta) byteArrayOf(ESC.toByte()) + bytes else bytes

    private fun csi(rest: String): ByteArray =
        byteArrayOf(ESC.toByte(), '['.code.toByte()) + rest.toByteArray(Charsets.US_ASCII)

    private fun ss3(final: Char): ByteArray =
        byteArrayOf(ESC.toByte(), 'O'.code.toByte(), final.code.toByte())

    private fun utf8(codePoint: Int): ByteArray {
        val character = if (codePoint in 0xD800..0xDFFF) REPLACEMENT_CHARACTER else codePoint
        return String(Character.toChars(character)).toByteArray(Charsets.UTF_8)
    }

    /** The modifier parameter of xterm's sequences: 1 + the sum of the held modifiers. */
    private val KeyModifiers.xtermParameter: Int
        get() =
            1 +
                (if (shift) 1 else 0) +
                (if (alt) 2 else 0) +
                (if (ctrl) 4 else 0) +
                (if (meta) 8 else 0)
}
