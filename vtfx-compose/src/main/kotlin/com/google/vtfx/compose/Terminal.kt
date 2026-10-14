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

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.viewinterop.AndroidView
import com.google.vtfx.core.Hyperlink
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LinkFinder
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// The touch input follows docs/decisions/0012, and the links and the keys 0019.
/**
 * Displays [session] and sends the user's input to it.
 *
 * The number of columns and rows follows the size of this composable, inside the padding of
 * [style][TerminalStyle.padding]; the session reports them through
 * [TerminalSessionListener.onResize] when the composable is first laid out, even if they are the
 * session's size, and then when they change. A tap focuses the terminal and shows the keyboard, and
 * a vertical swipe scrolls through the scrollback (or, when the host asks for them, sends mouse
 * wheel events or arrow keys).
 *
 * A tap on a link calls [onLinkClick] instead of showing the keyboard, unless the host asked for
 * mouse reports, which then get the tap. The [TerminalLinkClick] tells the kind of pointer and the
 * modifier keys held, so that the app can, for example, open links only on Ctrl+click. Links are
 * OSC 8 hyperlinks ([TerminalLink.Explicit]) and the text that [linkPatterns] match
 * ([TerminalLink.Matched]), both underlined; where they overlap, the hyperlink wins, and so does
 * the earlier pattern of two. A match that a hyperlink removes still hides the later patterns'
 * matches that it overlaps; so does a match over concealed text (SGR 8), which is not a link. The
 * URL of any link may differ from what the user sees: an OSC 8 hyperlink's text and URL come from
 * the host apart, and text in the background's color or out of view can be part of a match. Show
 * the URL to the user, and call [TerminalSession.openUrl] only when they confirm. Without
 * [onLinkClick], links are not clickable, and [linkPatterns] are not matched.
 *
 * Hardware keys go to the focused terminal. An app takes keys for its own shortcuts, such as
 * Ctrl+Shift+C and Ctrl+Shift+V for copy and paste, with [onPreImeKeyEvent], which sees them before
 * the IME. `Modifier.onPreviewKeyEvent` on an ancestor of this composable is not enough: it sees
 * only the keys that the IME passes on, and an IME such as Gboard takes the keys of a physical
 * keyboard and sends them itself.
 *
 * A long press selects the word under it, and shows a menu to copy, paste, and select all. The app
 * reads and changes the selection through [state], for its own Copy command or shortcut.
 *
 * With a mouse, the primary button selects: a drag selects the text it goes over, a double click a
 * word, and a triple click a line, and Shift+click extends the selection; a click without a drag is
 * a tap. The wheel scrolls as a swipe does. The secondary button calls [onContextClick], or shows
 * the menu of the long press. When the host asks for mouse reports, it gets the buttons, the wheel,
 * and the motion that it asked for, on links too, unless Shift is held: Shift leaves the mouse to
 * the terminal, as in xterm, so Shift+click on a link calls [onLinkClick]. While the display shows
 * the scrollback, the mouse and touches are the terminal's too, as a report could not tell its rows
 * from the screen's, until the display is back at the bottom.
 *
 * @param session The session to display, which may outlive this composable.
 * @param modifier The modifier to apply to the terminal. Its size sets the number of columns and
 *   rows. Where the constraints leave the size free, the terminal takes 80 columns and 24 rows, or
 *   as much of that as the constraints allow; give it a size, such as with `fillMaxSize` or
 *   `weight`, to fill the space instead. Laid out with no width or height, it logs a warning and
 *   leaves the session's size as it was. A `focusRequester` here lets the app focus the terminal,
 *   which then gets the hardware keys; a tap also shows the software keyboard.
 * @param state The selection, which the app can read and change. By default, a state that the app
 *   cannot reach.
 * @param style The appearance of the terminal.
 * @param linkPatterns The text to show as links, besides OSC 8 hyperlinks, in order of priority. By
 *   default, the URLs in the text. The patterns run on the main thread when the display changes,
 *   without a bound on their work; see [LinkPattern] for the patterns to give.
 * @param onLinkClick Called on the main thread with the link that the user clicked and how, or null
 *   to make links not clickable.
 * @param onContextClick Called on the main thread when the user clicks the secondary mouse button
 *   and the host does not get the click, with the position of the click in pixels from the top left
 *   of this composable (within the padding that [modifier] may add), for the app's own menu; it can
 *   offer Copy while [TerminalState.hasSelection] of [state] is true. Null to show the menu of the
 *   long press at the click.
 * @param onPreImeKeyEvent Called on the main thread with each hardware key event while the terminal
 *   has the focus, before the IME and the session see it: presses, repeated presses, and releases.
 *   Return true to consume the event, which then reaches neither the IME nor the session, and
 *   consume a key's release with its press. Return false for the keys the app does not use,
 *   including system keys such as Back. Keys from a software keyboard and keys sent with
 *   [TerminalSession.sendKey] do not pass through it.
 */
@Composable
public fun Terminal(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    state: TerminalState = rememberTerminalState(),
    style: TerminalStyle = TerminalStyle(),
    linkPatterns: List<LinkPattern> = listOf(LinkPattern.Urls),
    onLinkClick: ((TerminalLinkClick) -> Unit)? = null,
    onContextClick: ((Offset) -> Unit)? = null,
    onPreImeKeyEvent: ((KeyEvent) -> Boolean)? = null,
) {
    val display = remember(session) { TerminalDisplay(session) }
    Terminal(
        display,
        modifier,
        state,
        style,
        linkPatterns,
        onLinkClick,
        onContextClick,
        onPreImeKeyEvent,
    )
}

/** Displays [display]'s session; [Terminal] with the display state given, for tests. */
@Composable
internal fun Terminal(
    display: TerminalDisplay,
    modifier: Modifier = Modifier,
    state: TerminalState = rememberTerminalState(),
    style: TerminalStyle = TerminalStyle(),
    linkPatterns: List<LinkPattern> = listOf(LinkPattern.Urls),
    onLinkClick: ((TerminalLinkClick) -> Unit)? = null,
    onContextClick: ((Offset) -> Unit)? = null,
    onPreImeKeyEvent: ((KeyEvent) -> Boolean)? = null,
) {
    val session = display.session
    DisposableEffect(display) {
        display.attach()
        onDispose { display.detach() }
    }
    DisposableEffect(state, display) {
        state.attach(display)
        onDispose { state.detach(display) }
    }
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val typefaces =
        TextTypefaces.rememberTextTypefaces(
            remember(style.font) { style.font.toFontFamily() },
            fontFamilyResolver,
        )
    val paints = remember(style, density, typefaces) { TextPaints(style, density, typefaces) }
    val metrics = remember(paints) { CellMetrics.measure(paints) }
    val renderer =
        remember(paints, style, metrics, density) {
            TerminalRenderer(paints, style, metrics, density)
        }
    var focused by remember { mutableStateOf(false) }
    SideEffect {
        session.reportedColors = style.colors.reportedColors
        session.setDefaultCursorStyle(style.cursorShape, style.cursorBlinking)
    }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val toolbar = LocalTextToolbar.current
    // The cursor blinks while focused, if the host asked for it (DECSCUSR or mode 12).
    var cursorOn by remember { mutableStateOf(true) }
    // Derived, so that a screen update recomposes only when the blinking changes.
    val blinking by
        remember(display) {
            derivedStateOf {
                focused && display.observeVersion().let { session.modes().cursorBlinking }
            }
        }
    LaunchedEffect(blinking) {
        cursorOn = true
        if (!blinking) return@LaunchedEffect
        // The user's input shows the cursor and starts its phase again, so that it stays on while
        // the user types, also when the host does not echo; so does output that moves the cursor,
        // as in xterm.js. Output that leaves the cursor where it was, such as a redraw that
        // returns it, does not (docs/decisions/0012). Collected rather than read in the
        // composition, so that typing and output do not recompose the terminal.
        snapshotFlow {
            display.observeVersion()
            Pair(display.observeEditorVersion(), session.cursorPosition())
        }
            .collectLatest {
                cursorOn = true
                while (true) {
                    delay(CURSOR_BLINK_MILLIS)
                    cursorOn = !cursorOn
                }
            }
    }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    // The rows that fit, for the accessibility nodes; the session's size follows with a delay.
    var gridRows by remember { mutableIntStateOf(0) }
    val displayText = remember(display) { DisplayText(display) }
    val linkFinder = remember(linkPatterns) { LinkFinder(linkPatterns) }
    // The gesture detector outlives recompositions, so it reads the latest values.
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val currentLinkFinder by rememberUpdatedState(linkFinder)
    val currentOnPreImeKeyEvent by rememberUpdatedState(onPreImeKeyEvent)
    val currentOnContextClick by rememberUpdatedState(onContextClick)
    // The terminal's position in the root, where the mouse's positions start.
    var origin by remember { mutableStateOf(Offset.Zero) }
    // The pointer icon: a hand over a link, the arrow while the host gets the mouse.
    var hoverIcon by remember { mutableStateOf(PointerIcon.Text) }
    val onTap: () -> Unit = {
        toolbar.hide()
        inputView?.showKeyboard()
    }
    // Returns whether a link took the click.
    val onLinkTap =
        { snapshot: TerminalSnapshot, cell: Cell, type: PointerType, keys: PointerKeyboardModifiers
            ->
            val click = currentOnLinkClick
            val link = if (click != null) linkAt(snapshot, cell, currentLinkFinder) else null
            if (click != null && link != null) {
                toolbar.hide()
                click(TerminalLinkClick(link, type, keys))
            }
            link != null
        }

    // The padding takes the grid's default background, which reverse video (DECSCNM) swaps.
    val reverseVideo by
        remember(display) {
            derivedStateOf { display.observeVersion().let { session.modes().reverseVideo } }
        }
    val layoutDirection = LocalLayoutDirection.current

    Box(
        modifier
            .preferredGridSize(metrics, style.padding)
            .drawBehind {
                drawRect(if (reverseVideo) style.colors.foreground else style.colors.background)
            }
            // Over the padding too, before the touch gestures, which then do not see the mouse.
            .terminalMouse(
                display,
                metrics,
                style.padding,
                layoutDirection,
                onClick = onTap,
                onLinkClick = onLinkTap,
                onContextClick = { position ->
                    val click = currentOnContextClick
                    if (click != null) {
                        toolbar.hide()
                        click(position)
                    } else {
                        showMenu(
                            toolbar,
                            display,
                            Rect(origin + position, Size(1f, metrics.height)),
                        )
                    }
                },
                onHover = { snapshot, cell ->
                    hoverIcon =
                        when {
                            snapshot == null || cell == null -> PointerIcon.Default
                            currentOnLinkClick != null &&
                                linkAt(snapshot, cell, currentLinkFinder) != null ->
                                PointerIcon.Hand
                            else -> PointerIcon.Text
                        }
                },
            )
            .pointerHoverIcon(hoverIcon)
            .paddingTaps(style.padding, layoutDirection, toolbar, onTap = onTap)
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        // Behind the grid with the same bounds, so that it does not take the touches and its
        // coordinates are the grid's.
        AndroidView(
            factory = { context ->
                TerminalInputView(context).also { view ->
                    view.onFocusChange = { focused = it }
                    view.onPreImeKeyEvent = {
                        currentOnPreImeKeyEvent?.invoke(KeyEvent(it)) ?: false
                    }
                    inputView = view
                }
            },
            modifier = Modifier.matchParentSize().padding(style.padding),
            update = { view ->
                view.metrics = metrics
                view.bind(display)
            },
            onRelease = { view -> view.bind(null) },
        )
        Canvas(
            Modifier.matchParentSize()
                .padding(style.padding)
                .onSizeChanged { size ->
                    if (size.width == 0 || size.height == 0) {
                        // The session keeps its size: resizing the host to one cell would reflow
                        // its output for nothing (docs/decisions/0032).
                        Log.w(LOG_TAG, "Terminal laid out with no room for the grid: $size")
                        gridRows = 0
                        return@onSizeChanged
                    }
                    val columns = (size.width / metrics.width).toInt().coerceIn(1, MAX_DIMENSION)
                    val rows = (size.height / metrics.height).toInt().coerceIn(1, MAX_DIMENSION)
                    gridRows = rows
                    display.resize(columns, rows)
                }
                .onGloballyPositioned { gridOrigin = it.positionInRoot() }
                .terminalGestures(
                    display,
                    metrics,
                    onTap = onTap,
                    onLinkTap = onLinkTap,
                    onSelectionEnd = { position ->
                        showMenu(
                            toolbar,
                            display,
                            Rect(gridOrigin + position, Size(1f, metrics.height)),
                        )
                    },
                )
        ) {
            display.observeVersion()
            display.observeEditorVersion()
            val snapshot = display.displaySnapshot()
            val links = if (onLinkClick != null) linkFinder.find(snapshot) else emptyList()
            with(renderer) {
                draw(
                    snapshot,
                    links,
                    session.edit { it.composingText },
                    session.ambiguousWidth,
                    focused,
                    display.selection,
                    cursorOn,
                )
            }
        }
        // Over the grid, without handling touches, so that a tap reaches the grid.
        RowSemantics(gridRows, metrics, Modifier.matchParentSize().padding(style.padding)) { row ->
            TerminalRowSemanticsElement(displayText, row)
        }
    }
}

/**
 * Exposes the displayed text to accessibility services as one node per row, so that a screen reader
 * can move through it line by line (requirement 6.3, docs/decisions/0015).
 *
 * The rows have no click action: Compose would extend the bounds of a clickable node to the minimum
 * touch target, and the rows, shorter than that, would be reported over each other. A screen
 * reader's double tap on a row without the action becomes a tap at the row, as on the grid.
 */
@Composable
private fun RowSemantics(
    rows: Int,
    metrics: CellMetrics,
    modifier: Modifier,
    element: (Int) -> Modifier,
) {
    Layout(
        content = { repeat(rows) { row -> Box(element(row)) } },
        modifier = modifier,
    ) { measurables, constraints ->
        // Rows do not overlap, although the cell height may be fractional.
        fun top(row: Int) = (row * metrics.height).roundToInt()
        val placeables = measurables.mapIndexed { row, measurable ->
            measurable.measure(Constraints.fixed(constraints.maxWidth, top(row + 1) - top(row)))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { row, placeable -> placeable.place(0, top(row)) }
        }
    }
}

/**
 * Sizes the terminal to [DEFAULT_COLUMNS] by [DEFAULT_ROWS] cells of [metrics] and [padding], as
 * close as the incoming constraints allow, the way text sizes to its content (docs/decisions/0032).
 */
private fun Modifier.preferredGridSize(metrics: CellMetrics, padding: PaddingValues): Modifier =
    layout { measurable, constraints ->
        // Rounded as Modifier.padding rounds them, so that the cells fit in the grid's bounds.
        val horizontal =
            padding.calculateLeftPadding(layoutDirection).roundToPx() +
                padding.calculateRightPadding(layoutDirection).roundToPx()
        val vertical =
            padding.calculateTopPadding().roundToPx() + padding.calculateBottomPadding().roundToPx()
        val columns = ceil(DEFAULT_COLUMNS * metrics.width).toInt()
        val rows = ceil(DEFAULT_ROWS * metrics.height).toInt()
        val width = constraints.constrainWidth(columns + horizontal)
        val height = constraints.constrainHeight(rows + vertical)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/**
 * Calls [onTap] for a tap in [padding], outside the grid, which handles its own touches; a tap
 * there focuses the terminal as one on the grid does. [keys] restart the detector when they change.
 */
private fun Modifier.paddingTaps(
    padding: PaddingValues,
    layoutDirection: LayoutDirection,
    vararg keys: Any?,
    onTap: () -> Unit,
): Modifier =
    pointerInput(padding, layoutDirection, *keys) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val grid =
                Rect(
                    padding.calculateLeftPadding(layoutDirection).roundToPx().toFloat(),
                    padding.calculateTopPadding().roundToPx().toFloat(),
                    size.width -
                        padding.calculateRightPadding(layoutDirection).roundToPx().toFloat(),
                    size.height - padding.calculateBottomPadding().roundToPx().toFloat(),
                )
            if (grid.contains(down.position)) return@awaitEachGesture
            // terminalMouse handles a mouse in the padding too, and consumes its events.
            if (down.isConsumed) return@awaitEachGesture
            if (waitForUpOrCancellation() != null) onTap()
        }
    }

/**
 * Shows the text toolbar at [rect], in the root's coordinates, with the commands for [display]'s
 * selection: Copy, which drops the selection, while there is one; Paste; and Select all, which
 * shows the toolbar again to copy.
 */
private fun showMenu(toolbar: TextToolbar, display: TerminalDisplay, rect: Rect) {
    toolbar.showMenu(
        rect = rect,
        onCopyRequested = if (display.selection != null) ({ display.copySelection() }) else null,
        onPasteRequested = { display.session.pasteFromClipboard() },
        onSelectAllRequested = {
            display.selectAll()
            showMenu(toolbar, display, rect)
        },
    )
}

/**
 * Returns the link at [cell] of [snapshot]: its OSC 8 hyperlink, else a match of [finder], or null.
 */
private fun linkAt(snapshot: TerminalSnapshot, cell: Cell, finder: LinkFinder): TerminalLink? {
    snapshot.line(cell.row).hyperlinkAt(cell.column)?.let {
        return TerminalLink.Explicit(hyperlinkText(snapshot, cell, it), it)
    }
    val found =
        finder.find(snapshot).firstOrNull { it.contains(cell.column, cell.row) } ?: return null
    return TerminalLink.Matched(found.text, found.url, found.pattern)
}

/**
 * Returns the text of the cells around [cell] of [snapshot] that have [link], in reading order,
 * across the rows that autowrap joins.
 */
private fun hyperlinkText(snapshot: TerminalSnapshot, cell: Cell, link: Hyperlink): String {
    val columns = snapshot.columns
    fun hasLink(row: Int, column: Int) = snapshot.line(row).hyperlinkAt(column) == link
    // The first cell of the link, going back over the rows that wrap into the next.
    var row = cell.row
    var column = cell.column
    while (true) {
        if (column > 0 && hasLink(row, column - 1)) {
            column--
        } else if (
            column == 0 &&
                row > 0 &&
                snapshot.line(row - 1).isWrapped &&
                hasLink(row - 1, columns - 1)
        ) {
            row--
            column = columns - 1
        } else {
            break
        }
    }
    return buildString {
        while (hasLink(row, column)) {
            val line = snapshot.line(row)
            // The right half of a wide character adds nothing; a blank cell is a space.
            if (line.widthAt(column) > 0) append(line.textAt(column) ?: " ")
            if (column < columns - 1) {
                column++
            } else if (line.isWrapped && row < snapshot.rows - 1) {
                row++
                column = 0
            } else {
                break
            }
        }
    }
}

private const val MAX_DIMENSION = com.google.vtfx.core.TerminalLimits.MAX_DIMENSION
private const val CURSOR_BLINK_MILLIS = 500L
private const val LOG_TAG = "vtfx"

/** The size that the terminal takes when the constraints leave it free, as xterm's default. */
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * The text of each displayed row, made once per change of the display and shared by the rows'
 * accessibility nodes. Made only when they ask, which is when an accessibility service or a test
 * asks for the semantics.
 */
private class DisplayText(private val display: TerminalDisplay) {
    private var version = -1
    private var scrollOffset = -1
    private var lines = emptyList<String>()

    /** Reads the state that the text depends on, so that an observer is told when it changes. */
    fun observe() {
        display.observeVersion()
        display.scrollOffset
    }

    /**
     * Returns the text of [row] without trailing blanks, or an empty string if there is no such
     * row.
     */
    fun line(row: Int): String {
        val currentVersion = display.observeVersion()
        val currentOffset = display.scrollOffset
        if (currentVersion != version || currentOffset != scrollOffset) {
            val snapshot = display.displaySnapshot()
            lines = List(snapshot.rows) { snapshot.line(it).text().trimEnd() }
            version = currentVersion
            scrollOffset = currentOffset
        }
        return lines.getOrElse(row) { "" }
    }
}

private data class TerminalRowSemanticsElement(
    private val displayText: DisplayText,
    private val row: Int,
) : ModifierNodeElement<TerminalRowSemanticsNode>() {
    override fun create() = TerminalRowSemanticsNode(displayText, row)

    override fun update(node: TerminalRowSemanticsNode) {
        node.displayText = displayText
        node.row = row
        node.invalidateSemantics()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalRowSemantics"
        properties["row"] = row
    }
}

/** The semantics of one row, invalidated when the display changes. */
private class TerminalRowSemanticsNode(
    var displayText: DisplayText,
    var row: Int,
) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
    override fun onAttach() {
        observeDisplay()
    }

    override fun onObservedReadsChanged() {
        observeDisplay()
        invalidateSemantics()
    }

    private fun observeDisplay() {
        observeReads { displayText.observe() }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        text = AnnotatedString(displayText.line(row))
        // The host may show secrets. Android then shows the row only to services that declare
        // isAccessibilityTool, such as TalkBack (docs/decisions/0015).
        isSensitiveData = true
    }
}
