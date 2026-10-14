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

package com.google.vtfx.compose

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.session.TerminalSession

/**
 * Handles Android key events, from the IME (`sendKeyEvent`) and from hardware keyboards alike
 * (docs/design/ime-contract.md section 6.3).
 */
internal class KeyEventHandler(private val session: TerminalSession) {
    /**
     * Handles [event] and returns whether it is a terminal key. Releases have no effect but are
     * consumed for the keys whose presses are.
     */
    fun handle(event: KeyEvent): Boolean {
        val stroke = toKeyStroke(event) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) session.edit { it.onKeyDown(stroke) }
        return true
    }

    companion object {
        /** Returns the key stroke for [event], or null if it is not a terminal key (e.g. Back). */
        fun toKeyStroke(event: KeyEvent): KeyStroke? {
            val modifiers =
                KeyModifiers.of(
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    ctrl = event.isCtrlPressed,
                    meta = event.isMetaPressed,
                )
            SPECIAL_KEYS[event.keyCode]?.let {
                return KeyStroke.Special(it, modifiers)
            }
            // Shift is applied to the character; the other modifiers are sent separately.
            val metaState = event.metaState and NON_SHIFT_MODIFIERS.inv()
            val unicode = event.getUnicodeChar(metaState)
            if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return null
            return KeyStroke.Character(
                unicode,
                KeyModifiers.of(alt = modifiers.alt, ctrl = modifiers.ctrl, meta = modifiers.meta),
            )
        }

        private const val NON_SHIFT_MODIFIERS =
            KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK

        private val SPECIAL_KEYS =
            mapOf(
                KeyEvent.KEYCODE_ESCAPE to TerminalKey.Escape,
                KeyEvent.KEYCODE_TAB to TerminalKey.Tab,
                KeyEvent.KEYCODE_ENTER to TerminalKey.Enter,
                KeyEvent.KEYCODE_NUMPAD_ENTER to TerminalKey.KeypadEnter,
                KeyEvent.KEYCODE_NUMPAD_0 to TerminalKey.Keypad0,
                KeyEvent.KEYCODE_NUMPAD_1 to TerminalKey.Keypad1,
                KeyEvent.KEYCODE_NUMPAD_2 to TerminalKey.Keypad2,
                KeyEvent.KEYCODE_NUMPAD_3 to TerminalKey.Keypad3,
                KeyEvent.KEYCODE_NUMPAD_4 to TerminalKey.Keypad4,
                KeyEvent.KEYCODE_NUMPAD_5 to TerminalKey.Keypad5,
                KeyEvent.KEYCODE_NUMPAD_6 to TerminalKey.Keypad6,
                KeyEvent.KEYCODE_NUMPAD_7 to TerminalKey.Keypad7,
                KeyEvent.KEYCODE_NUMPAD_8 to TerminalKey.Keypad8,
                KeyEvent.KEYCODE_NUMPAD_9 to TerminalKey.Keypad9,
                KeyEvent.KEYCODE_NUMPAD_DOT to TerminalKey.KeypadDecimal,
                KeyEvent.KEYCODE_NUMPAD_ADD to TerminalKey.KeypadPlus,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT to TerminalKey.KeypadMinus,
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY to TerminalKey.KeypadMultiply,
                KeyEvent.KEYCODE_NUMPAD_DIVIDE to TerminalKey.KeypadDivide,
                KeyEvent.KEYCODE_NUMPAD_EQUALS to TerminalKey.KeypadEquals,
                KeyEvent.KEYCODE_DEL to TerminalKey.Backspace,
                KeyEvent.KEYCODE_INSERT to TerminalKey.Insert,
                KeyEvent.KEYCODE_FORWARD_DEL to TerminalKey.Delete,
                KeyEvent.KEYCODE_MOVE_HOME to TerminalKey.Home,
                KeyEvent.KEYCODE_MOVE_END to TerminalKey.End,
                KeyEvent.KEYCODE_PAGE_UP to TerminalKey.PageUp,
                KeyEvent.KEYCODE_PAGE_DOWN to TerminalKey.PageDown,
                KeyEvent.KEYCODE_DPAD_UP to TerminalKey.Up,
                KeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.Down,
                KeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.Left,
                KeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.Right,
                KeyEvent.KEYCODE_F1 to TerminalKey.F1,
                KeyEvent.KEYCODE_F2 to TerminalKey.F2,
                KeyEvent.KEYCODE_F3 to TerminalKey.F3,
                KeyEvent.KEYCODE_F4 to TerminalKey.F4,
                KeyEvent.KEYCODE_F5 to TerminalKey.F5,
                KeyEvent.KEYCODE_F6 to TerminalKey.F6,
                KeyEvent.KEYCODE_F7 to TerminalKey.F7,
                KeyEvent.KEYCODE_F8 to TerminalKey.F8,
                KeyEvent.KEYCODE_F9 to TerminalKey.F9,
                KeyEvent.KEYCODE_F10 to TerminalKey.F10,
                KeyEvent.KEYCODE_F11 to TerminalKey.F11,
                KeyEvent.KEYCODE_F12 to TerminalKey.F12,
            )
    }
}
