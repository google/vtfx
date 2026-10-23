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

package com.google.vtfx.sample

import android.net.Uri
import android.os.Looper
import com.google.vtfx.compose.TerminalFont
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.MouseEncoding
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.SanitizedFamily
import com.google.vtfx.font.gate.SanitizedFont
import com.google.vtfx.session.TerminalSession
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleReproTest {
    @get:Rule val folder = TemporaryFolder()

    // R9: Exiting a replay in EchoHost does not reset mouse tracking, focus reporting, bracketed
    // paste, or application cursor keys that recordings such as vim.out enable.
    @Test
    fun exitingReplayResetsMouseTrackingBracketedPasteAndFocusReporting() {
        lateinit var session: TerminalSession
        val host =
            EchoHost(
                output = { session.feed(it) },
                recording = { name ->
                    if (name == "vim") {
                        // Modes enabled by recordings/vim.out.
                        "\u001b[?1049h\u001b[?1h\u001b[?2004h\u001b[?1004h\u001b[?1002h\u001b[?1006hVIM"
                            .toByteArray()
                    } else {
                        null
                    }
                },
                requestSize = { _, _ -> },
                font = {},
            )
        session = TerminalSession({ host.receive(it) })
        host.start()

        host.receive("replay vim\r".toByteArray())
        assertEquals(MouseTracking.ButtonEvent, session.snapshot().modes.mouseTracking)

        // Any key exits the replay and returns to the shell.
        host.receive("q".toByteArray())

        val modes = session.snapshot().modes
        assertEquals(MouseTracking.None, modes.mouseTracking)
        assertEquals(MouseEncoding.Default, modes.mouseEncoding)
        assertFalse(modes.focusEvents)
        assertFalse(modes.bracketedPaste)
        assertFalse(modes.applicationCursorKeys)
    }

    // R10: FontStore allows importing more than TerminalFont.Sanitized.MAX_FAMILIES (64) fonts,
    // causing MainActivity's TerminalFont.Sanitized(fonts.map { SanitizedFamily(it) }) to throw
    // IllegalArgumentException on import and on every subsequent startup.
    @Test
    fun importingBeyondMaxFamiliesDoesNotCrashTerminalFontSanitized() {
        val limits = FontLimits()
        val fontBytes =
            checkNotNull(javaClass.getResourceAsStream(FONT)) { "missing $FONT" }.use {
                it.readBytes()
            }
        val canonicalFont = SanitizedFont.fromCanonical(fontBytes, limits)
        val store = FontStore(File(folder.root, "fonts"), limits)
        store.save(List(TerminalFont.Sanitized.MAX_FAMILIES) { canonicalFont })

        store.import({ ByteArrayInputStream(fontBytes) }, null)
        val loaded = store.load().fonts

        // MainActivity passes loaded fonts directly to TerminalFont.Sanitized.
        val terminalFont = TerminalFont.Sanitized(loaded.map { SanitizedFamily(it) })
        assertTrue(terminalFont.families.size <= TerminalFont.Sanitized.MAX_FAMILIES)
    }

    // R11: SampleViewModel.fontRequest is set by "font import" and never cleared, so a subsequent
    // Activity recreation (such as device rotation) sees the stale non-null fontRequest in
    // LaunchedEffect(fontRequest) and launches the system file picker again.
    @Test
    fun fontRequestIsClearedAfterImportFont() {
        val app = RuntimeEnvironment.getApplication()
        val fontBytes =
            checkNotNull(javaClass.getResourceAsStream(FONT)) { "missing $FONT" }.use {
                it.readBytes()
            }
        val uri = Uri.parse("content://example/font.ttf")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(fontBytes))
        val viewModel = SampleViewModel(app)
        try {
            viewModel.session.sendText("font import\r")
            shadowOf(Looper.getMainLooper()).idle()
            val request = viewModel.fontRequest
            assertNotNull(request)

            viewModel.importFont(request!!, uri)
            // Wait for fontExecutor to finish importing the font and post to mainHandler.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (viewModel.fonts.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(1, viewModel.fonts.size)

            assertNull(viewModel.fontRequest)
        } finally {
            val onCleared = SampleViewModel::class.java.getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(viewModel)
        }
    }

    private companion object {
        const val FONT = "/com/google/vtfx/font/gate/canonical/variations.ttf"
    }
}
