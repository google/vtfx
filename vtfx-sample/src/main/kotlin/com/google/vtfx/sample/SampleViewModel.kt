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

package com.google.vtfx.sample

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.SanitizedFont
import com.google.vtfx.gate.android.AndroidClipboard
import com.google.vtfx.gate.android.AndroidUrlOpener
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds the session, so that it survives configuration changes, as the library expects apps to do
 * (docs/decisions/0007).
 */
class SampleViewModel(application: Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Whether to log every byte sent to the host, for the end-to-end tests. Off by default: the
     * bytes are what the user types.
     */
    @Volatile var logHostInput: Boolean = false

    /** The font size in sp, made smaller when a recording needs more columns or rows. */
    var fontSize: Float by mutableFloatStateOf(DEFAULT_FONT_SIZE)
        private set

    /**
     * The session's sticky modifiers, as Compose state for the key bar. The session clears them
     * when they apply to a key, and reports it to the listener.
     */
    var stickyModifiers: KeyModifiers by mutableStateOf(KeyModifiers.None)
        private set

    /**
     * The fonts that the user imported with the `font` command, sanitized, in the order of import:
     * each is a family of the terminal's font, tried in turn. Empty for the system font.
     */
    var fonts: List<SanitizedFont> by mutableStateOf(emptyList())
        private set

    /**
     * The face to import from the file that the user picks next, or null for the first one that
     * vtfx reads, set by `font import`. [MainActivity] shows the picker when it changes.
     */
    var fontRequest: FontRequest? by mutableStateOf(null)
        private set

    /** A request to pick a font file, distinct from the one before. */
    class FontRequest(val face: Int?)

    /** Reads and sanitizes fonts, which takes seconds for a CJK font, off the main thread. */
    private val fontExecutor = Executors.newSingleThreadExecutor()

    /** The saved fonts, touched only on the font thread; [fonts] is a copy for the main thread. */
    private val fontStore = FontStore(File(application.filesDir, "fonts"), LIMITS)

    private var columns = 0
    private var rows = 0

    val session: TerminalSession =
        TerminalSession(
            connection = { bytes ->
                if (logHostInput) Log.i(HOST_INPUT_TAG, "{\"hex\":\"${bytes.toHex()}\"}")
                // A real host answers asynchronously; so does the pseudo host.
                mainHandler.post { host.receive(bytes) }
            },
            clipboard = AndroidClipboard(application),
            urlOpener = AndroidUrlOpener(application),
            listener =
                object : TerminalSessionListener {
                    override fun onResize(columns: Int, rows: Int) {
                        this@SampleViewModel.columns = columns
                        this@SampleViewModel.rows = rows
                        host.onResize(columns, rows)
                    }

                    override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                        stickyModifiers = modifiers
                    }
                },
        )

    // Declared after the session, which its output goes to. The session calls back into it only
    // after construction.
    private val host =
        EchoHost(
            output = { bytes -> session.feed(bytes) },
            recording = { name -> readRecording(application, name) },
            requestSize = { wantedColumns, wantedRows -> shrinkFontFor(wantedColumns, wantedRows) },
            font = ::fontCommand,
        )

    init {
        host.start()
        fontExecutor.execute {
            val loaded = fontStore.load()
            mainHandler.post { fonts = loaded }
        }
    }

    override fun onCleared() {
        fontExecutor.shutdownNow()
    }

    private fun fontCommand(arguments: String) {
        val words = arguments.split(' ').filter { it.isNotEmpty() }
        when (words.firstOrNull()) {
            null -> host.report(describeFonts(fonts))
            "import" -> {
                val face = words.getOrNull(1)?.toIntOrNull()
                if (words.size > 2 || (words.size == 2 && face == null)) {
                    host.report("Usage: font import [FACE]")
                } else {
                    fontRequest = FontRequest(face)
                }
            }
            "reset" ->
                fontExecutor.execute {
                    fontStore.save(emptyList())
                    mainHandler.post {
                        fonts = emptyList()
                        host.report(describeFonts(emptyList()))
                    }
                }
            else -> host.report("Usage: font, font import [FACE], or font reset")
        }
    }

    /**
     * Imports the font file at [uri], which the user picked for [request], on the font thread, and
     * reports how it went to the terminal. Only the sanitized font is kept, never the file.
     */
    fun importFont(request: FontRequest, uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        fontExecutor.execute {
            when (val result = fontStore.import({ resolver.openInputStream(uri) }, request.face)) {
                is FontStore.ImportResult.Imported ->
                    mainHandler.post {
                        fonts = result.fonts
                        host.report(result.report + describeFonts(result.fonts))
                    }
                is FontStore.ImportResult.Failed -> mainHandler.post { host.report(result.message) }
            }
        }
    }

    private fun describeFonts(fonts: List<SanitizedFont>): String =
        if (fonts.isEmpty()) {
            "Fonts: the system's monospace font."
        } else {
            "Fonts, in order:\n" +
                fonts.withIndex().joinToString("\n") { (i, font) ->
                    "  $i: ${font.size} bytes, ${font.widthClass}"
                }
        }

    private fun shrinkFontFor(wantedColumns: Int, wantedRows: Int) {
        if (columns == 0 || rows == 0) return
        val scale = minOf(columns.toFloat() / wantedColumns, rows.toFloat() / wantedRows)
        // A little smaller than the exact ratio, since cells are measured with rounding.
        fontSize = maxOf(MIN_FONT_SIZE, fontSize * scale * 0.97f)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Reads a recording of tools/recordings, bundled as an asset, or returns null. The recordings
     * of programs that show images are in graphics/; they write where the cursor is, so the screen
     * is cleared before them.
     */
    private fun readRecording(application: Application, name: String): ByteArray? {
        if (!name.matches(Regex("[a-z0-9-]+"))) return null
        try {
            return application.assets.open("$name.out").use { it.readBytes() }
        } catch (_: java.io.IOException) {}
        return try {
            "\u001b[2J\u001b[H".toByteArray() +
                application.assets.open("graphics/$name.out").use { it.readBytes() }
        } catch (_: java.io.IOException) {
            null
        }
    }

    companion object {
        /** The logcat tag of the bytes sent to the host, read by scripts/e2e_ime.py. */
        const val HOST_INPUT_TAG = "VtfxSampleHost"

        private const val DEFAULT_FONT_SIZE = 14f

        private val LIMITS = FontLimits()
        private const val MIN_FONT_SIZE = 4f
    }
}
