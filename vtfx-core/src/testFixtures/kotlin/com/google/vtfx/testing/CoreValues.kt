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

package com.google.vtfx.testing

import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.Hyperlink
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.MouseEncoding
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.TerminalModes

// Only vtfx-core makes these values (docs/decisions/0037); the test fixtures, compiled as a friend
// of vtfx-core, make them for the tests of the other modules.

/** Returns [TerminalModes] with the given values. */
public fun terminalModes(
    applicationCursorKeys: Boolean = false,
    applicationKeypad: Boolean = false,
    newLine: Boolean = false,
    cursorVisible: Boolean = true,
    cursorShape: CursorShape = CursorShape.Block,
    cursorBlinking: Boolean = false,
    reverseVideo: Boolean = false,
    bracketedPaste: Boolean = false,
    focusEvents: Boolean = false,
    synchronizedOutput: Boolean = false,
    mouseTracking: MouseTracking = MouseTracking.None,
    mouseEncoding: MouseEncoding = MouseEncoding.Default,
    disambiguateEscapeCodes: Boolean = false,
): TerminalModes =
    TerminalModes(
        applicationCursorKeys,
        applicationKeypad,
        newLine,
        cursorVisible,
        cursorShape,
        cursorBlinking,
        reverseVideo,
        bracketedPaste,
        focusEvents,
        synchronizedOutput,
        mouseTracking,
        mouseEncoding,
        disambiguateEscapeCodes,
    )

/** Returns a [Hyperlink] to [uri] with the id parameter [id]. */
public fun hyperlink(uri: String, id: String?): Hyperlink = Hyperlink(uri, id)
