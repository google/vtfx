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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.session.TerminalSession

/**
 * An auxiliary key bar with the keys a software keyboard lacks, built on the public API
 * (requirement 5.4): [TerminalSession.sendKey] for keys, and [TerminalSession.stickyModifiers] for
 * Ctrl and Alt, which apply to the next key typed on the keyboard. "Direct" switches
 * [TerminalSession.directInput], for IMEs that would otherwise hold English words until a space.
 *
 * @param stickyModifiers The session's sticky modifiers, which the app follows with
 *   `TerminalSessionListener.onStickyModifiersChanged`.
 */
@Composable
fun KeyBar(session: TerminalSession, stickyModifiers: KeyModifiers, modifier: Modifier = Modifier) {
    // Only this key bar switches direct input, so it follows the session's state by itself.
    var directInput by remember(session) { mutableStateOf(session.directInput) }
    Row(modifier.fillMaxWidth().background(Color(0xFF202020))) {
        Key("Esc") { session.sendKey(KeyStroke.Special(TerminalKey.Escape)) }
        Key("Tab") { session.sendKey(KeyStroke.Special(TerminalKey.Tab)) }
        ModifierKey("Ctrl", session, stickyModifiers, KeyModifiers.of(ctrl = true))
        ModifierKey("Alt", session, stickyModifiers, KeyModifiers.of(alt = true))
        Key("←") { session.sendKey(KeyStroke.Special(TerminalKey.Left)) }
        Key("↓") { session.sendKey(KeyStroke.Special(TerminalKey.Down)) }
        Key("↑") { session.sendKey(KeyStroke.Special(TerminalKey.Up)) }
        Key("→") { session.sendKey(KeyStroke.Special(TerminalKey.Right)) }
        Key("Direct", directInput) {
            session.directInput = !directInput
            directInput = session.directInput
        }
    }
}

@Composable
private fun ModifierKey(
    label: String,
    session: TerminalSession,
    stickyModifiers: KeyModifiers,
    modifiers: KeyModifiers,
) {
    val active = stickyModifiers.union(modifiers) == stickyModifiers
    Key(label, active) {
        session.stickyModifiers =
            if (active) KeyModifiers.None else stickyModifiers.union(modifiers)
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clickable(onClick = onClick)
            .background(if (active) Color(0xFF4D90FE) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 16.sp))
    }
}
