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

import com.google.vtfx.gate.ClipboardAccess

/** A clipboard that records what was copied to it, and has nothing to paste. */
internal class RecordingClipboard : ClipboardAccess {
    val copied = mutableListOf<String>()

    override fun write(text: String) {
        copied += text
    }

    override fun read(): String? = null
}
