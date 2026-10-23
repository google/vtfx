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
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalModes

/**
 * Encodes key strokes into the bytes that xterm sends for them (xterm ctlseqs, "PC-Style Function
 * Keys"), or, when the host set flag 1 of the kitty keyboard protocol, into the forms of that
 * protocol, with the alternate keys of flag 4 if the host set it too (docs/decisions/0070). The
 * only inputs are the key, the modifiers, and the terminal modes: no text from the host can reach
 * the output.
 */
internal object KeyEncoder {
    private const val ESC = 0x1B
    private const val REPLACEMENT_CHARACTER = 0xFFFD

    /** Returns the bytes for [stroke] under [modes]. */
    fun encode(stroke: KeyStroke, modes: TerminalModes): ByteArray =
        if (modes.disambiguateEscapeCodes) {
            when (stroke) {
                is KeyStroke.Special -> disambiguateSpecial(stroke.key, stroke.modifiers, modes)
                is KeyStroke.Character -> disambiguateCharacter(stroke, modes)
            }
        } else {
            when (stroke) {
                is KeyStroke.Special -> encodeSpecial(stroke.key, stroke.modifiers, modes)
                is KeyStroke.Character -> encodeCharacter(stroke.codePoint, stroke.modifiers)
            }
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
            // The encoding ignores NumLock: the keys with it off are the digits at their places.
            TerminalKey.KeypadInsert -> keypad('0', 'p', m, modes)
            TerminalKey.KeypadEnd -> keypad('1', 'q', m, modes)
            TerminalKey.KeypadDown -> keypad('2', 'r', m, modes)
            TerminalKey.KeypadPageDown -> keypad('3', 's', m, modes)
            TerminalKey.KeypadLeft -> keypad('4', 't', m, modes)
            TerminalKey.KeypadBegin -> keypad('5', 'u', m, modes)
            TerminalKey.KeypadRight -> keypad('6', 'v', m, modes)
            TerminalKey.KeypadHome -> keypad('7', 'w', m, modes)
            TerminalKey.KeypadUp -> keypad('8', 'x', m, modes)
            TerminalKey.KeypadPageUp -> keypad('9', 'y', m, modes)
            TerminalKey.KeypadDelete -> keypad('.', 'n', m, modes)
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

    // --- Flag 1 of the kitty keyboard protocol, "disambiguate escape codes" ---

    /**
     * Encodes a key that does not produce a character as kitty does with flag 1. Enter, Tab, and
     * Backspace without modifiers keep their legacy bytes, so that a user can still type `reset`
     * after a program left the flag set. No key takes an SS3 form, with or without DECCKM and
     * DECKPAM.
     */
    private fun disambiguateSpecial(
        key: TerminalKey,
        m: KeyModifiers,
        modes: TerminalModes,
    ): ByteArray =
        when (key) {
            TerminalKey.Escape -> csiU(27, m)
            TerminalKey.Tab -> if (m.isEmpty) byteArrayOf(0x09) else csiU(9, m)
            TerminalKey.Enter ->
                when {
                    !m.isEmpty -> csiU(13, m)
                    modes.newLine -> byteArrayOf(0x0D, 0x0A)
                    else -> byteArrayOf(0x0D)
                }
            TerminalKey.Backspace -> if (m.isEmpty) byteArrayOf(0x7F) else csiU(127, m)
            TerminalKey.Up -> letter('A', m)
            TerminalKey.Down -> letter('B', m)
            TerminalKey.Right -> letter('C', m)
            TerminalKey.Left -> letter('D', m)
            TerminalKey.Home -> letter('H', m)
            TerminalKey.End -> letter('F', m)
            TerminalKey.Insert -> tilde(2, m)
            TerminalKey.Delete -> tilde(3, m)
            TerminalKey.PageUp -> tilde(5, m)
            TerminalKey.PageDown -> tilde(6, m)
            TerminalKey.F1 -> letter('P', m)
            TerminalKey.F2 -> letter('Q', m)
            // CSI R would be a cursor position report.
            TerminalKey.F3 -> tilde(13, m)
            TerminalKey.F4 -> letter('S', m)
            TerminalKey.F5 -> tilde(15, m)
            TerminalKey.F6 -> tilde(17, m)
            TerminalKey.F7 -> tilde(18, m)
            TerminalKey.F8 -> tilde(19, m)
            TerminalKey.F9 -> tilde(20, m)
            TerminalKey.F10 -> tilde(21, m)
            TerminalKey.F11 -> tilde(23, m)
            TerminalKey.F12 -> tilde(24, m)
            TerminalKey.Keypad0 -> disambiguateKeypad('0', 57399, m)
            TerminalKey.Keypad1 -> disambiguateKeypad('1', 57400, m)
            TerminalKey.Keypad2 -> disambiguateKeypad('2', 57401, m)
            TerminalKey.Keypad3 -> disambiguateKeypad('3', 57402, m)
            TerminalKey.Keypad4 -> disambiguateKeypad('4', 57403, m)
            TerminalKey.Keypad5 -> disambiguateKeypad('5', 57404, m)
            TerminalKey.Keypad6 -> disambiguateKeypad('6', 57405, m)
            TerminalKey.Keypad7 -> disambiguateKeypad('7', 57406, m)
            TerminalKey.Keypad8 -> disambiguateKeypad('8', 57407, m)
            TerminalKey.Keypad9 -> disambiguateKeypad('9', 57408, m)
            TerminalKey.KeypadDecimal -> disambiguateKeypad('.', 57409, m)
            TerminalKey.KeypadDivide -> disambiguateKeypad('/', 57410, m)
            TerminalKey.KeypadMultiply -> disambiguateKeypad('*', 57411, m)
            TerminalKey.KeypadMinus -> disambiguateKeypad('-', 57412, m)
            TerminalKey.KeypadPlus -> disambiguateKeypad('+', 57413, m)
            // Enter produces no text, so Keypad Enter is always its own key.
            TerminalKey.KeypadEnter -> csiU(57414, m)
            TerminalKey.KeypadEquals -> disambiguateKeypad('=', 57415, m)
            // The keys with NumLock off produce no text.
            TerminalKey.KeypadLeft -> csiU(57417, m)
            TerminalKey.KeypadRight -> csiU(57418, m)
            TerminalKey.KeypadUp -> csiU(57419, m)
            TerminalKey.KeypadDown -> csiU(57420, m)
            TerminalKey.KeypadPageUp -> csiU(57421, m)
            TerminalKey.KeypadPageDown -> csiU(57422, m)
            TerminalKey.KeypadHome -> csiU(57423, m)
            TerminalKey.KeypadEnd -> csiU(57424, m)
            TerminalKey.KeypadInsert -> csiU(57425, m)
            TerminalKey.KeypadDelete -> csiU(57426, m)
            TerminalKey.KeypadBegin -> csiU(57427, m)
        }

    /**
     * A keypad key that produces text sends its [character], as kitty sends it, unless Ctrl, Alt,
     * or Meta is held: then it is the key [code] of the protocol. DECKPAM does not apply; the keys
     * with NumLock off are other keys.
     */
    private fun disambiguateKeypad(character: Char, code: Int, m: KeyModifiers): ByteArray =
        if (!m.ctrl && !m.alt && !m.meta) {
            encodeCharacter(character.code, m)
        } else {
            csiU(code, m)
        }

    /**
     * Encodes a character key as kitty does with flag 1. Without Ctrl, Alt, or Meta, the character
     * is text, as in the legacy encoding. Otherwise it is `CSI code ; modifiers u`, where the code
     * is the unshifted key, and Shift is reported if [KeyStroke.Character.modifiers] have it. With
     * flag 4, report alternate keys, the code is followed by the shifted key, the character, if
     * Shift is held and it differs from the code, and by the key of the base layout, if it is known
     * and differs from the code: `CSI code : shifted : base ; modifiers u`, with the shifted key
     * left empty if only the base key is given. A character key of a control sends nothing, as in
     * the legacy encoding, and an unpaired surrogate is U+FFFD.
     */
    private fun disambiguateCharacter(
        stroke: KeyStroke.Character,
        modes: TerminalModes,
    ): ByteArray {
        val m = stroke.modifiers
        if (!m.ctrl && !m.alt && !m.meta) return encodeCharacter(stroke.codePoint, m)
        if (isControl(stroke.codePoint)) return ByteArray(0)
        val code = withoutSurrogate(stroke.unshiftedCodePoint)
        if (!modes.reportAlternateKeys) return csiU(code, m)
        val shifted = withoutSurrogate(stroke.codePoint).takeIf { m.shift && it != code }
        val base = stroke.baseLayoutCodePoint?.let(::withoutSurrogate)?.takeIf { it != code }
        val key =
            when {
                base != null -> "$code:${shifted ?: ""}:$base"
                shifted != null -> "$code:$shifted"
                else -> "$code"
            }
        return csi("$key;${m.xtermParameter}u")
    }

    /** `CSI code u`, or `CSI code ; modifiers u` with modifiers. */
    private fun csiU(code: Int, m: KeyModifiers): ByteArray =
        if (m.isEmpty) csi("${code}u") else csi("$code;${m.xtermParameter}u")

    /** `CSI final`, or `CSI 1 ; modifiers final` with modifiers. */
    private fun letter(final: Char, m: KeyModifiers): ByteArray =
        if (m.isEmpty) csi("$final") else csi("1;${m.xtermParameter}$final")

    /** Alt (and Meta) prefix the bytes with ESC, as xterm does with metaSendsEscape. */
    private fun withAlt(bytes: ByteArray, m: KeyModifiers): ByteArray =
        if (m.alt || m.meta) byteArrayOf(ESC.toByte()) + bytes else bytes

    private fun csi(rest: String): ByteArray =
        byteArrayOf(ESC.toByte(), '['.code.toByte()) + rest.toByteArray(Charsets.US_ASCII)

    private fun ss3(final: Char): ByteArray =
        byteArrayOf(ESC.toByte(), 'O'.code.toByte(), final.code.toByte())

    private fun utf8(codePoint: Int): ByteArray =
        String(Character.toChars(withoutSurrogate(codePoint))).toByteArray(Charsets.UTF_8)

    /** Replaces an unpaired surrogate with U+FFFD. */
    private fun withoutSurrogate(codePoint: Int): Int =
        if (codePoint in 0xD800..0xDFFF) REPLACEMENT_CHARACTER else codePoint

    /**
     * The modifier parameter of xterm's sequences: 1 + the sum of the held modifiers. The kitty
     * keyboard protocol has the same bits, with Meta as its Super.
     */
    private val KeyModifiers.xtermParameter: Int
        get() =
            1 +
                (if (shift) 1 else 0) +
                (if (alt) 2 else 0) +
                (if (ctrl) 4 else 0) +
                (if (meta) 8 else 0)
}
