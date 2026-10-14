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

package com.google.vtfx.compose

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// See docs/decisions/0031.
/**
 * The state of a [Terminal] that the app can read and change: the selection, for the app's own Copy
 * command or shortcut. Create it with [rememberTerminalState] and pass it to [Terminal].
 *
 * The state acts on the [Terminal] it is passed to while that is in the composition. Before and
 * after, there is nothing to act on: [hasSelection] is false, and the functions do nothing. If it
 * is passed to two terminals at once, it acts on the one that entered the composition last; when
 * that one leaves, it acts on none until a terminal enters again.
 *
 * The selection is in what the terminal displays, so the terminal drops it whenever the display
 * changes: on output from the host, on scrolling, on typing, and on resizing.
 *
 * Use it on the main thread.
 */
@Stable
@MainThread
public class TerminalState {
    /** The display of the terminal that this state acts on, or null. */
    private var display: TerminalDisplay? by mutableStateOf(null)

    /**
     * Whether the user selected text in the terminal. A snapshot state: composables that read it
     * are recomposed when it changes.
     */
    public val hasSelection: Boolean
        get() = display?.selection != null

    /**
     * Copies the selected text to the clipboard that the session was given, and keeps the
     * selection. Returns whether there was a selection to copy and a clipboard to copy it to.
     *
     * Lines are separated by "\n", except where autowrap joined them, and the trailing blanks of
     * each line are left out. After [selectAll], the text is that of the newest lines of the
     * scrollback and the screen, at most 10,000 lines (fewer when there are more than 2,097,152
     * cells in them), cut to the newest lines that fit in 262,144 characters, so that the system's
     * clipboard can take it. Empty lines at the end are left out.
     */
    public fun copySelection(): Boolean = display?.copySelection(clear = false) ?: false

    /**
     * Selects all the text of the terminal: the scrollback and the screen, or only the screen when
     * the host uses the alternate screen, which has no scrollback. The terminal highlights what it
     * displays. [copySelection] says how much of it is copied.
     */
    public fun selectAll() {
        display?.selectAll()
    }

    /** Drops the selection, if any. */
    public fun clearSelection() {
        display?.selection = null
    }

    /** Starts acting on [display], the display of a terminal that entered the composition. */
    internal fun attach(display: TerminalDisplay) {
        this.display = display
    }

    /** Stops acting on [display], if it still acts on it. */
    internal fun detach(display: TerminalDisplay) {
        if (this.display === display) this.display = null
    }
}

/** Creates a [TerminalState] and remembers it across recompositions. */
@Composable public fun rememberTerminalState(): TerminalState = remember { TerminalState() }
