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

package com.google.vtfx.compose

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.SearchQuery

// See docs/decisions/0031, and 0047 for the search.
/**
 * The state of a [Terminal] that the app can read and change: the selection, for the app's own Copy
 * command or shortcut, and the search, for the app's own search field. Create it with
 * [rememberTerminalState] and pass it to [Terminal].
 *
 * The state acts on the [Terminal] it is passed to while that is in the composition. Before and
 * after, there is nothing to act on: [hasSelection] is false, [searchResult] is null, and the
 * functions do nothing. If it is passed to two terminals at once, it acts on the one that entered
 * the composition last; when that one leaves, it acts on none until a terminal enters again. The
 * selection and the search belong to the terminal, and go when it leaves the composition.
 *
 * The selection is in what the terminal displays, so the terminal drops it whenever the display
 * changes: on output from the host, on scrolling, on typing, and on resizing. The search stays, and
 * follows the output.
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
     * each line are left out. Concealed text (SGR 8), which the terminal does not draw, is copied
     * as blanks. After [selectAll], the text is that of the newest lines of the scrollback and the
     * screen, at most 10,000 lines (fewer when there are more than 2,097,152 cells in them), cut to
     * the newest lines that fit in 262,144 characters, so that the system's clipboard can take it.
     * Empty lines at the end are left out.
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

    /**
     * What the search found, or null if there is no search. A snapshot state: composables that read
     * it are recomposed when it changes, also when output adds or removes matches.
     */
    public val searchResult: TerminalSearchResult?
        get() = display?.searchResult

    /**
     * Searches the scrollback and the screen for [text], or only the screen when the host uses the
     * alternate screen, and highlights the matches. Call it again as the user types: it keeps the
     * current match if it still matches, or else moves to the nearest match above it, or above the
     * bottom of the display at first, or else to the first match below. The terminal scrolls to
     * show the current match. An empty [text] ends the search, as [clearSearch].
     *
     * The text is matched as it is, ignoring the case of letters if [ignoreCase], within a line of
     * the terminal, through the rows that autowrap joins, but not across a line break. The blanks
     * at the end of a line are not text, as in copying, and concealed text (SGR 8), which the
     * terminal does not draw, is not matched. Only the newest 1,000 matches are kept
     * ([TerminalSearchResult.isMatchCountCapped]).
     *
     * The search follows the output: new lines are searched as they arrive, and matches go with the
     * lines that leave the scrollback, without moving the display. Resizing the terminal searches
     * the reflowed lines again.
     */
    public fun search(text: String, ignoreCase: Boolean) {
        display?.search(if (text.isEmpty()) null else SearchQuery(text, ignoreCase))
    }

    /**
     * Moves to the match below the current one, or from the last match to the first, and scrolls to
     * show it.
     */
    public fun findNext() {
        display?.findNext()
    }

    /**
     * Moves to the match above the current one, or from the first match to the last, and scrolls to
     * show it.
     */
    public fun findPrevious() {
        display?.findPrevious()
    }

    /** Ends the search, if any, and removes its highlights. */
    public fun clearSearch() {
        display?.search(null)
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
