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

package com.google.vtfx.input

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyStroke

/**
 * Input for the host, produced from the user's key strokes and IME edits. vtfx-gate turns each into
 * bytes.
 *
 * @suppress
 */
@InternalVtfxApi
public sealed interface HostInput {
    /** Committed [text]. Never contains line breaks, which are sent as [Key] Enter. */
    @InternalVtfxApi
    public data class Text(val text: String) : HostInput {
        init {
            require(text.isNotEmpty()) { "empty text" }
        }
    }

    /**
     * [count] DEL characters (0x7f), which delete text already sent (docs/design/ime-contract.md
     * 4.1).
     */
    @InternalVtfxApi
    public data class Delete(val count: Int) : HostInput {
        init {
            require(count > 0) { "count must be positive: $count" }
        }
    }

    /** A key, [stroke], which vtfx-gate encodes under the terminal modes. */
    @InternalVtfxApi public data class Key(val stroke: KeyStroke) : HostInput

    /**
     * Pasted [text], which vtfx-gate sanitizes and brackets if the host enabled bracketed paste.
     */
    @InternalVtfxApi public data class Paste(val text: String) : HostInput
}
