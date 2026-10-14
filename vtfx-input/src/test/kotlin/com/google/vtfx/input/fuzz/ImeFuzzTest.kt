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

package com.google.vtfx.input.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.input.HostileIme
import com.google.vtfx.input.ImeEditor

/**
 * Fuzzes the IME editing model with the calls of an untrusted IME (requirement 6.1), and checks the
 * invariants of [HostileIme] after each (docs/design/testing.md section 7).
 *
 * In a normal test run, this replays the fixed corpus in the resources directory
 * ImeFuzzTestInputs/calls. The `fuzz` Gradle task fuzzes; see scripts/fuzz.sh.
 *
 * Input format, so that seeds can be written by hand:
 * - Byte 0: the history's limit, 1 + (byte % 32).
 * - The rest: one byte for each choice of [HostileIme.call], the call first and then its arguments,
 *   as the byte modulo the number of choices. Missing bytes choose 0.
 */
class ImeFuzzTest {
    @FuzzTest
    fun calls(input: ByteArray) {
        if (input.isEmpty()) return
        val maxHistoryLength = 1 + (input[0].toInt() and 0xFF) % 32
        var position = 1
        val choose = { count: Int ->
            if (position < input.size) (input[position++].toInt() and 0xFF) % count else 0
        }

        val host = HostileIme.Host("$ ")
        val editor = ImeEditor(host, maxHistoryLength)
        val calls = mutableListOf<String>()
        while (position < input.size) {
            calls += HostileIme.call(editor, choose)
            HostileIme.checkInvariants(
                editor,
                host,
                maxHistoryLength,
                "calls ${calls.joinToString("; ")}",
            )
        }
        editor.closeConnection()
        HostileIme.checkDeleteBound(
            host,
            calls.size,
            maxHistoryLength,
            "calls ${calls.joinToString("; ")}",
        )
    }
}
