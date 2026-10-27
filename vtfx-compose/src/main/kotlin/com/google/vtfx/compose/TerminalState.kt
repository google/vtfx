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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.SearchMatch
import com.google.vtfx.core.SearchQuery
import com.google.vtfx.session.TerminalSession
import java.util.UUID
import java.util.WeakHashMap

// See docs/decisions/0085.
/**
 * The state of a [Terminal] that the app can read and change: the selection, for the app's own Copy
 * command or shortcut, and the search, for the app's own search field. Create it with
 * [rememberTerminalState] and pass it to [Terminal].
 *
 * The state acts on the [Terminal] it is passed to while that is in the composition. Before and
 * after, there is nothing to act on: [hasSelection] is false, [searchResult] is null, and the
 * functions do nothing. If it is passed to two terminals at once, it acts on the one that entered
 * the composition last; when that one leaves, it acts on none until a terminal enters again.
 *
 * When the terminal it acts on leaves the composition, as when the user switches to another tab or
 * screen, the state keeps the search and the lines of the scrollback the terminal showed, and gives
 * them to the next terminal it is passed to: a terminal coming back shows the same lines, and the
 * same search with the same current match, as long as its session is the same and its lines were
 * not numbered again since (by resizing, clearing the scrollback, or switching between the main and
 * the alternate screen); otherwise it shows the bottom, and searches the new lines for the same
 * text. [rememberTerminalState] also saves them with the instance state, so that they survive a
 * configuration change and a `SaveableStateHolder`, as navigation keeps the screens in the back
 * stack. The selection is not kept.
 *
 * The selection stays on the text the user selected as output and scrolling move it, and the
 * terminal drops it when that text changes, on typing, and on resizing. The search stays too, and
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
     * What the last display that this state acted on showed when it left the composition, for the
     * next one that enters, or null. Null while a display is attached, which holds the view then.
     */
    private var savedView: SavedView? = null

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
     * screen, at most 10,000 lines (fewer when there are more than 2,097,152 cells in them), and
     * empty lines at the end are left out. The text is cut to the newest lines that fit in 262,144
     * characters, so that the system's clipboard can take it.
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

    /**
     * Starts acting on [display], the display of a terminal that entered the composition, and gives
     * it the view that the last display left, if any.
     */
    internal fun attach(display: TerminalDisplay) {
        val view = savedView
        if (view != null) {
            savedView = null
            display.restore(view)
        }
        this.display = display
    }

    /** Stops acting on [display], if it still acts on it, and keeps what it shows. */
    internal fun detach(display: TerminalDisplay) {
        if (this.display !== display) return
        savedView = display.view()
        this.display = null
    }

    /** What the attached display shows, or what the last one left, or null. */
    internal fun view(): SavedView? {
        val display = display
        return if (display != null) display.view() else savedView
    }

    internal companion object {
        /** Saves the view in the instance state ([SavedView.toSaveable]). */
        val Saver: Saver<TerminalState, Any> =
            Saver(
                save = { state -> state.view()?.toSaveable() },
                restore = { saved ->
                    TerminalState().apply { savedView = SavedView.fromSaveable(saved) }
                },
            )
    }
}

/**
 * Creates a [TerminalState] and remembers it across recompositions, and, with the instance state,
 * across configuration changes and while its place in the composition is kept by a
 * `SaveableStateHolder`, as when navigation keeps the screens in the back stack.
 */
@Composable
public fun rememberTerminalState(): TerminalState =
    rememberSaveable(saver = TerminalState.Saver) { TerminalState() }

/**
 * What a display showed, for the display that takes its place (docs/decisions/0095).
 *
 * @property sessionKey The key of the session that the display showed ([sessionKey]).
 * @property generation The generation of the line numbers of [topLine] and [currentMatch].
 * @property topLine The number of the line at the top of the display, or null if the display
 *   followed the output at the bottom.
 * @property query The text that the display searched for, or null.
 * @property currentMatch The current match of the search, or null.
 */
internal class SavedView(
    val sessionKey: String,
    val generation: Long,
    val topLine: Long?,
    val query: SearchQuery?,
    val currentMatch: SearchMatch?,
) {
    /** Returns this as values that a `Bundle` can hold, for [fromSaveable]. */
    fun toSaveable(): ArrayList<Any?> =
        arrayListOf(
            sessionKey,
            generation,
            topLine,
            query?.text,
            query?.ignoreCase,
            currentMatch?.firstLine,
            currentMatch?.startColumn,
            currentMatch?.lastLine,
            currentMatch?.endColumn,
        )

    companion object {
        /** Returns the view that [toSaveable] returned [saved] for. */
        fun fromSaveable(saved: Any): SavedView {
            val values = saved as List<*>
            val text = values[3] as String?
            val firstLine = values[5] as Long?
            return SavedView(
                sessionKey = values[0] as String,
                generation = values[1] as Long,
                topLine = values[2] as Long?,
                query = if (text == null) null else SearchQuery(text, values[4] as Boolean),
                currentMatch =
                    if (firstLine == null) null
                    else
                        SearchMatch(
                            firstLine,
                            values[6] as Int,
                            values[7] as Long,
                            values[8] as Int,
                        ),
            )
        }
    }
}

/**
 * The key of each session that a display has shown, for telling whether a saved view is of the same
 * session. Random, so that a view saved before the process was killed does not match the new
 * session of the same app; weak, so that the sessions can go. Used on the main thread.
 */
private val sessionKeys = WeakHashMap<TerminalSession, String>()

/** The key of [session] in the views of its displays. */
@MainThread
internal fun sessionKey(session: TerminalSession): String =
    sessionKeys.getOrPut(session) { UUID.randomUUID().toString() }
