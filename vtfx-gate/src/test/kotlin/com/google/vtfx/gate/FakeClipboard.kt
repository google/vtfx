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

/**
 * A clipboard that holds one text in memory, as the system clipboard does, and records the writes
 * and counts the reads.
 */
class FakeClipboard(var text: String?) : ClipboardAccess {
    val writes = mutableListOf<String>()
    var reads = 0

    override fun write(text: String) {
        writes += text
        this.text = text
    }

    override fun read(): String? {
        reads++
        return text
    }
}
