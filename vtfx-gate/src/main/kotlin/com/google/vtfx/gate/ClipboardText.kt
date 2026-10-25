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

import com.google.vtfx.core.InternalVtfxApi

/**
 * The text on the clipboard, read for the user's paste. Only vtfx-gate reads it, so that the code
 * that asks for the paste can only send it to the host with [HostWriter.sendPaste], as one paste,
 * and cannot by mistake send it elsewhere or send its lines one by one (docs/decisions/0091).
 *
 * Immutable.
 *
 * @suppress
 */
@InternalVtfxApi public class ClipboardText internal constructor(internal val text: String)
