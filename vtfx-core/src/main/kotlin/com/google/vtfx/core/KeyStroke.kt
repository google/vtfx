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

package com.google.vtfx.core

/** A key that does not produce a character by itself. */
public enum class TerminalKey {
    Escape,
    Tab,
    Enter,
    Backspace,
    Insert,
    Delete,
    Home,
    End,
    PageUp,
    PageDown,
    Up,
    Down,
    Left,
    Right,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,

    /** Keys of the numeric keypad, which DECKPAM switches to application sequences. */
    Keypad0,
    Keypad1,
    Keypad2,
    Keypad3,
    Keypad4,
    Keypad5,
    Keypad6,
    Keypad7,
    Keypad8,
    Keypad9,
    KeypadDecimal,
    KeypadPlus,
    KeypadMinus,
    KeypadMultiply,
    KeypadDivide,
    KeypadEquals,
    KeypadEnter,
}

/** The modifier keys held with a key. */
@JvmInline
public value class KeyModifiers private constructor(private val bits: Int) {
    /** Whether Shift is held. */
    public val shift: Boolean
        get() = bits and SHIFT != 0

    /** Whether Alt is held. */
    public val alt: Boolean
        get() = bits and ALT != 0

    /** Whether Ctrl is held. */
    public val ctrl: Boolean
        get() = bits and CTRL != 0

    /** Whether Meta is held. */
    public val meta: Boolean
        get() = bits and META != 0

    /** Whether no modifier is held. */
    public val isEmpty: Boolean
        get() = bits == 0

    /** Returns the modifiers held in this or [other]. */
    public fun union(other: KeyModifiers): KeyModifiers = KeyModifiers(bits or other.bits)

    override fun toString(): String = buildList {
        if (shift) add("Shift")
        if (alt) add("Alt")
        if (ctrl) add("Ctrl")
        if (meta) add("Meta")
    }
        .joinToString("+", prefix = "KeyModifiers(", postfix = ")")

    public companion object {
        private const val SHIFT = 1
        private const val ALT = 2
        private const val CTRL = 4
        private const val META = 8

        /** No modifier held. */
        public val None: KeyModifiers = KeyModifiers(0)

        /** Returns the modifiers with the given keys held. */
        public fun of(
            shift: Boolean = false,
            alt: Boolean = false,
            ctrl: Boolean = false,
            meta: Boolean = false,
        ): KeyModifiers =
            KeyModifiers(
                (if (shift) SHIFT else 0) or
                    (if (alt) ALT else 0) or
                    (if (ctrl) CTRL else 0) or
                    (if (meta) META else 0)
            )
    }
}

/** A key pressed by the user, independent of the platform's key events. */
public sealed interface KeyStroke {
    /** The modifier keys held with the key. */
    public val modifiers: KeyModifiers

    /**
     * A key that does not produce a character, such as an arrow key.
     *
     * @property key The key.
     */
    public data class Special(
        val key: TerminalKey,
        override val modifiers: KeyModifiers = KeyModifiers.None,
    ) : KeyStroke

    /**
     * A key that produces a character. Shift is already applied to
     * [codePoint][KeyStroke.Character.codePoint], so [modifiers][KeyStroke.Character.modifiers]
     * should not include it unless the platform reports it separately.
     *
     * @property codePoint The Unicode code point of the character.
     */
    public data class Character(
        val codePoint: Int,
        override val modifiers: KeyModifiers = KeyModifiers.None,
    ) : KeyStroke {
        init {
            require(java.lang.Character.isValidCodePoint(codePoint)) {
                "invalid code point: $codePoint"
            }
        }

        override fun toString(): String =
            "Character(${String(java.lang.Character.toChars(codePoint))}, $modifiers)"
    }
}
