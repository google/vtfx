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

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.google.vtfx.compose.Terminal
import com.google.vtfx.compose.TerminalFont
import com.google.vtfx.compose.TerminalState
import com.google.vtfx.compose.TerminalStyle
import com.google.vtfx.compose.rememberTerminalState
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.font.gate.SanitizedFamily
import com.google.vtfx.gate.OpenUrlResult
import com.google.vtfx.session.TerminalSession

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[SampleViewModel::class.java]
        val options =
            LaunchOptions.read(
                debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                profileable = applicationInfo.isProfileableByShell,
                booleanExtra = { intent.getBooleanExtra(it, false) },
                stringExtra = { intent.getStringExtra(it) },
            )
        if (options.logHostInput) viewModel.logHostInput = true
        if (options.benchmark && savedInstanceState == null) {
            RenderBenchmark(this, viewModel.session).start()
        }
        // The locale of Han glyphs, such as "ja", for checking them on a device in another
        // language.
        val locale = options.locale?.let { LocaleList(it) }
        setContent {
            val state = rememberTerminalState()
            var searching by rememberSaveable { mutableStateOf(false) }
            val fontRequest = viewModel.fontRequest
            val pickFont =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null && fontRequest != null) viewModel.importFont(fontRequest, uri)
                }
            LaunchedEffect(fontRequest) { if (fontRequest != null) pickFont.launch(arrayOf("*/*")) }
            val fonts = viewModel.fonts
            val font =
                if (fonts.isEmpty()) TerminalFont.System()
                else TerminalFont.Sanitized(fonts.map { SanitizedFamily(it) })
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                if (searching) SearchBar(state, onClose = { searching = false })
                Terminal(
                    viewModel.session,
                    Modifier.fillMaxWidth().weight(1f),
                    state = state,
                    style =
                        TerminalStyle(
                            font = font,
                            fontSize = viewModel.fontSize.sp,
                            locale = locale,
                        ),
                    linkPatterns = LINK_PATTERNS,
                    onLinkClick = { click -> confirmOpening(viewModel.session, click.link.url) },
                    onPreImeKeyEvent = {
                        interceptShortcut(it, state, onFind = { searching = true })
                    },
                )
                // The keys a software keyboard lacks, only while it is shown.
                if (WindowInsets.isImeVisible) {
                    KeyBar(
                        viewModel.session,
                        viewModel.stickyModifiers,
                        onFind = { searching = true },
                    )
                }
            }
        }
    }

    /**
     * Takes Ctrl+Shift+K before the IME and the terminal, as an app's own shortcut would
     * (docs/usage.md), Ctrl+Shift+C to copy the selection of [state], and Ctrl+Shift+F to call
     * [onFind]. A release is consumed with its press, so that the session sees neither.
     */
    private fun interceptShortcut(
        event: KeyEvent,
        state: TerminalState,
        onFind: () -> Unit,
    ): Boolean {
        if (!event.isCtrlPressed || !event.isShiftPressed) return false
        val press = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.K ->
                if (press) Toast.makeText(this, R.string.shortcut_taken, Toast.LENGTH_SHORT).show()
            Key.C ->
                if (press) {
                    // Nothing to tell the user when there is no selection.
                    val unused = state.copySelection()
                }
            Key.F -> if (press) onFind()
            else -> return false
        }
        return true
    }

    /**
     * Shows [url] and opens it only if the user confirms. The text of a link comes from the host
     * and may name another site than [url] (requirement 6.1: spoofed links).
     */
    private fun confirmOpening(session: TerminalSession, url: String) {
        if (!session.canOpenUrl(url)) {
            Toast.makeText(this, R.string.link_refused, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link_title)
            .setMessage(url)
            .setPositiveButton(R.string.open_link) { _, _ ->
                val failure =
                    when (session.openUrl(url)) {
                        OpenUrlResult.Opened -> null
                        OpenUrlResult.NotAllowed -> R.string.link_refused
                        OpenUrlResult.NoApp -> R.string.link_no_app
                        OpenUrlResult.Refused -> R.string.link_refused_by_system
                    }
                if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** The URLs in the text, and Chromium's short bug links as an example of an app's own. */
        private val LINK_PATTERNS =
            listOf(
                LinkPattern.Urls,
                LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}"),
            )
    }
}
