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

package com.google.vtfx.compose

import android.view.KeyEvent
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class KeyEventHandlerTest {
    private fun key(keyCode: Int, metaState: Int = 0) =
        KeyEventHandler.toKeyStroke(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))

    @Test
    fun specialKeys() {
        assertEquals(KeyStroke.Special(TerminalKey.Left), key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(KeyStroke.Special(TerminalKey.Backspace), key(KeyEvent.KEYCODE_DEL))
        assertEquals(KeyStroke.Special(TerminalKey.Delete), key(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(KeyStroke.Special(TerminalKey.F5), key(KeyEvent.KEYCODE_F5))
        assertEquals(
            KeyStroke.Special(TerminalKey.Tab, KeyModifiers.of(shift = true)),
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        )
    }

    @Test
    fun charactersApplyShiftOnly() {
        assertEquals(KeyStroke.Character('a'.code), key(KeyEvent.KEYCODE_A))
        assertEquals(
            KeyStroke.Character('A'.code),
            key(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        )
        assertEquals(
            KeyStroke.Character('c'.code, KeyModifiers.of(ctrl = true)),
            key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        )
        assertEquals(
            KeyStroke.Character('x'.code, KeyModifiers.of(alt = true)),
            key(KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        )
    }

    @Test
    fun nonTerminalKeysAreNotHandled() {
        assertNull(key(KeyEvent.KEYCODE_BACK))
        assertNull(key(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
