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

package com.google.vtfx.gate

import com.google.vtfx.core.InternalVtfxApi
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Tests for what [TerminalGate.openUrl] returns. Which URLs it allows is in
 * [AttackTest.Hyperlinks].
 */
class OpenUrlTest {
    @Test
    fun openUrlReturnsThatNoAppOpensTheUrl() {
        // A device without an app for the URL.
        val gate = TerminalGate(HostWriter {}, GatePolicy(), urlOpener = { OpenUrlResult.NoApp })

        val result = gate.openUrl("https://example.com")

        assertEquals(OpenUrlResult.NoApp, result)
    }

    @Test
    fun openUrlReturnsThatTheSystemRefusedTheApp() {
        // A device whose app for the URL requires a permission.
        val gate = TerminalGate(HostWriter {}, GatePolicy(), urlOpener = { OpenUrlResult.Refused })

        val result = gate.openUrl("https://example.com")

        assertEquals(OpenUrlResult.Refused, result)
    }

    @Test
    fun openUrlWithoutAUrlOpenerIsNotAllowed() {
        val gate = TerminalGate(HostWriter {}, GatePolicy(), urlOpener = null)

        val result = gate.openUrl("https://example.com")

        assertEquals(OpenUrlResult.NotAllowed, result)
    }
}
