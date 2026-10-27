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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.compose.TerminalSearchResult
import com.google.vtfx.compose.TerminalState

/**
 * A search field for the terminal of [state], built on the public API (docs/usage.md): each change
 * of the text searches again, Enter and the down arrow move to the next match, Shift+Enter and the
 * up arrow to the previous one, and closing ends the search.
 */
@Composable
fun SearchBar(state: TerminalState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Saved as the state saves its search, so that the field shows the text after recreation.
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val close = {
        state.clearSearch()
        onClose()
    }
    Row(
        modifier.fillMaxWidth().background(Color(0xFF202020)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                state.search(it, ignoreCase = true)
            },
            modifier =
                Modifier.weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Enter && event.key != Key.Escape) {
                            return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                event.key == Key.Escape -> close()
                                event.isShiftPressed -> state.findPrevious()
                                else -> state.findNext()
                            }
                        }
                        true
                    },
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.findNext() }),
        )
        val result = state.searchResult
        if (result != null) Label(position(result))
        Button("↑") { state.findPrevious() }
        Button("↓") { state.findNext() }
        Button(stringResource(R.string.close_search)) { close() }
    }
}

/** The current match and the number of matches, as "3/17", or "0/1000+" when capped. */
private fun position(result: TerminalSearchResult): String {
    val current = result.currentMatchIndex?.plus(1) ?: 0
    val count = if (result.isMatchCountCapped) "${result.matchCount}+" else "${result.matchCount}"
    return "$current/$count"
}

@Composable
private fun Label(text: String) {
    BasicText(
        text,
        Modifier.padding(horizontal = 8.dp),
        style = TextStyle(color = Color.Gray, fontSize = 14.sp),
    )
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 16.sp))
    }
}
