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

/** Which mouse events the host asked to receive (DECSET 9, 1000, 1002, 1003). */
public enum class MouseTracking {
    None,
    /** Button presses only (DECSET 9). */
    X10,
    /** Presses and releases (DECSET 1000). */
    Normal,
    /** Presses, releases, and motion while a button is held (DECSET 1002). */
    ButtonEvent,
    /** Presses, releases, and all motion (DECSET 1003). */
    AnyEvent,
}

/** How mouse reports are encoded (DECSET 1005, 1006, 1015). */
public enum class MouseEncoding {
    Default,
    Utf8,
    Sgr,
    Urxvt,
}

/** The shape of the cursor, set by DECSCUSR. */
public enum class CursorShape {
    Block,
    Underline,
    Bar,
}

/** The modes that affect input and display, as set by the host. */
public data class TerminalModes(
    /** DECCKM: cursor keys send application sequences (ESC O A instead of CSI A). */
    val applicationCursorKeys: Boolean = false,
    /** DECKPAM: the keypad sends application sequences. */
    val applicationKeypad: Boolean = false,
    /** LNM: Enter sends CR LF. */
    val newLine: Boolean = false,
    /** DECTCEM: the cursor is visible. */
    val cursorVisible: Boolean = true,
    /** DECSCUSR: the shape of the cursor. */
    val cursorShape: CursorShape = CursorShape.Block,
    /** DECSCUSR or DECSET 12: the cursor blinks. */
    val cursorBlinking: Boolean = false,
    /** DECSCNM: the screen is displayed in reverse video. */
    val reverseVideo: Boolean = false,
    /** Mode 2004: pasted text is bracketed. */
    val bracketedPaste: Boolean = false,
    /** Mode 1004: focus changes are reported. */
    val focusEvents: Boolean = false,
    /** Mode 2026: the host is in the middle of a synchronized update. */
    val synchronizedOutput: Boolean = false,
    /** Modes 9, 1000, 1002, 1003: which mouse events are reported. */
    val mouseTracking: MouseTracking = MouseTracking.None,
    /** Modes 1005, 1006, 1015: how mouse events are encoded. */
    val mouseEncoding: MouseEncoding = MouseEncoding.Default,
)
