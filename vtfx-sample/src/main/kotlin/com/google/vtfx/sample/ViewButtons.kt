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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.vtfx.compose.TerminalState

/**
 * Buttons over the terminal for the view of [state], each shown only when it does something: one
 * that shows the whole terminal while it is zoomed, and one that scrolls to the bottom while the
 * terminal shows the scrollback.
 */
@Composable
internal fun ViewButtons(state: TerminalState, modifier: Modifier = Modifier) {
    Row(modifier.padding(8.dp)) {
        if (state.zoom != 1f) ViewButton(stringResource(R.string.reset_zoom)) { state.resetZoom() }
        if (state.isScrolledBack) {
            ViewButton(stringResource(R.string.scroll_to_bottom)) { state.scrollToBottom() }
        }
    }
}

@Composable
private fun ViewButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 8.dp)
            .background(Color(0xCC333333), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 14.sp))
    }
}
