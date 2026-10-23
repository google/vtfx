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

import android.content.ClipboardManager
import android.content.ContextWrapper
import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalQuery
import com.google.vtfx.gate.android.AndroidClipboard
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Reproduction tests for the 2026-10-22 review of vtfx-gate and vtfx-gate-android. Every test here
 * fails at the reviewed commit and should pass once the corresponding bug is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewReproTest {
    /**
     * R1: [TerminalGate.isAllowedUrl] skips `\` and any number of `/` after `:` before scanning the
     * authority of a special-scheme URL (`http`, `https`, `ftp`, `ws`, `wss`), so it allows URLs
     * such as `https://\evil.example`, `https:\\evil.example`, `https:///evil.example`,
     * `https:/evil.example`, `https:evil.example`, and `https://`, where `android.net.Uri` (and RFC
     * 3986 parsers) and browsers (the WHATWG URL Standard) disagree on the host.
     */
    @Test
    fun specialSchemeUrlsWithBackslashOrMissingDoubleSlashInAuthorityAreRejected() {
        val gate = TerminalGate(HostWriter {})
        for (url in
            listOf(
                "https://\\evil.example",
                "https:\\\\evil.example",
                "https:/\\evil.example",
                "https:///evil.example",
                "https:/evil.example",
                "https:evil.example",
                "https://",
            )) {
            assertFalse(gate.isAllowedUrl(url), url)
        }
    }

    /**
     * R2: [TerminalGate.handle] performs only the last allowed OSC 52 write in a single write of
     * host output (`index == lastWrite`), skipping earlier allowed writes even when an allowed OSC
     * 52 read appears between them. The read then replies to the host with the old clipboard text
     * from before the first write instead of the text that the preceding write set.
     */
    @Test
    fun osc52ReadBetweenTwoWritesInOneFeedRepliesWithThePrecedingWrite() {
        val sent = mutableListOf<String>()
        var clipboardText: String? = "secret from another app"
        val clipboard =
            object : ClipboardAccess {
                override fun write(text: String) {
                    clipboardText = text
                }

                override fun read(): String? = clipboardText
            }
        val session =
            TerminalSession(
                { sent += String(it, Charsets.UTF_8) },
                policy = GatePolicy(allowClipboardWrite = true, allowClipboardRead = true),
                clipboard = clipboard,
            )
        val first = Base64.getEncoder().encodeToString("first".toByteArray(Charsets.UTF_8))
        val second = Base64.getEncoder().encodeToString("second".toByteArray(Charsets.UTF_8))

        session.feed("\u001b]52;c;$first\u0007\u001b]52;c;?\u0007\u001b]52;c;$second\u0007".toByteArray())

        assertEquals(listOf("\u001b]52;c;$first\u001b\\"), sent)
        assertEquals("second", clipboardText)
    }

    /**
     * R3: [Replies.color] calls [ReportedColors.rgb] before checking that a [ColorTarget.Palette]
     * index is in `0..255` (section 3 of gate-policy.md). When `colors` is null, an out-of-range
     * palette index does not fail; when `colors` indexes a 256-entry palette (as
     * `TerminalColors.reportedColors` does), it throws [IndexOutOfBoundsException] instead of
     * [IllegalArgumentException].
     */
    @Test
    fun paletteIndexOutOfRangeIsCheckedBeforeCallingReportedColors() {
        val writer = HostWriter {}
        val palette = IntArray(256)

        assertFailsWith<IllegalArgumentException> {
            writer.sendReply(
                TerminalQuery.Color(ColorTarget.Palette(256)),
                colors = { target -> palette[(target as ColorTarget.Palette).index] },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            writer.sendReply(TerminalQuery.Color(ColorTarget.Palette(256)), colors = null)
        }
    }

    /**
     * R4: [AndroidClipboard] dereferences `context.getSystemService(ClipboardManager::class.java)`
     * without a null check, so [AndroidClipboard.write] and [AndroidClipboard.read] throw
     * [NullPointerException] when the clipboard service is unavailable, against the contract of
     * [ClipboardAccess] ("Implementations must be thread-safe and must not throw").
     */
    @Test
    fun androidClipboardDoesNotThrowWhenClipboardServiceIsUnavailable() {
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getSystemService(name: String): Any? =
                    if (
                        name == CLIPBOARD_SERVICE ||
                            name == ClipboardManager::class.java.name
                    ) {
                        null
                    } else {
                        super.getSystemService(name)
                    }
            }
        val clipboard = AndroidClipboard(context)

        clipboard.write("copied")

        assertNull(clipboard.read())
    }

    /**
     * R5: [TerminalSession.feed] calls [TerminalGate.handle] outside the session lock after
     * notifying [TerminalSessionListener]. When `onBell` (which the contract of
     * [TerminalSessionListener] allows to call the session) feeds output or sends input, the
     * nested call's gate actions run before those of the outer `feed`, reversing the order of
     * replies and OSC 52 writes relative to the host's output.
     */
    @Test
    fun repliesAreSentInInputOrderWhenListenerCallsSessionDuringFeed() {
        val sent = mutableListOf<String>()
        lateinit var session: TerminalSession
        val listener =
            object : TerminalSessionListener {
                override fun onBell() {
                    session.feed("\u001b[>c".toByteArray(Charsets.UTF_8))
                }
            }
        session = TerminalSession({ sent += String(it, Charsets.UTF_8) }, listener = listener)

        session.feed("\u0007\u001b[5n".toByteArray(Charsets.UTF_8))

        // DSR 5 (`\u001b[5n`) was fed before DA2 (`\u001b[>c`), so its reply (`\u001b[0n`) must
        // be sent before DA2's reply (`\u001b[>0;10;1c`).
        assertEquals(listOf("\u001b[0n", "\u001b[>0;10;1c"), sent)
    }
}
