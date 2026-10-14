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

package com.google.vtfx.session

import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.LineSnapshot
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalEvent
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalModes
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.ClipboardAccess
import com.google.vtfx.gate.GatePolicy
import com.google.vtfx.gate.HostConnection
import com.google.vtfx.gate.HostWriter
import com.google.vtfx.gate.MouseReport
import com.google.vtfx.gate.ReportedColors
import com.google.vtfx.gate.TerminalGate
import com.google.vtfx.gate.UrlOpener
import com.google.vtfx.input.HostInput
import com.google.vtfx.input.ImeEditor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives notifications from a [TerminalSession]. Each is called on the thread that caused it,
 * during the call that caused it, without the session's locks held: it may call the session.
 *
 * [onResize], [onTitleChanged], and [onStickyModifiersChanged] report the latest value, so that the
 * last one reported is the current one even when several threads change it. While one thread is
 * reporting a change, another thread's change of the same value is reported after it, on the first
 * thread, and changes made in the meantime are reported together as the latest. A listener that
 * changes the value is told again after it returns, not inside its own call.
 */
public interface TerminalSessionListener {
    /**
     * The terminal size is [columns] by [rows]. Tell the host, for example with an SSH
     * window-change request or TIOCSWINSZ on a PTY. Called on the thread that resized the session;
     * a Compose `Terminal` resizes it on the main thread.
     *
     * The first [TerminalSession.resize] of a session reports its size even if it is the size the
     * session was created with, so an app can wait for this first call to start the host with the
     * size that the display fits. A `Terminal` makes that call when it is first laid out. After it,
     * only changes are reported.
     */
    public fun onResize(columns: Int, rows: Int) {}

    /**
     * The host rang the bell. Called on the thread that called [TerminalSession.feed], once per
     * call however many bells the output has.
     */
    public fun onBell() {}

    /**
     * The host set the window title to [title], which [TerminalSession.title] now returns.
     * Untrusted text. Called on the thread that called [TerminalSession.feed], once per call with
     * the last title the output set.
     */
    public fun onTitleChanged(title: String) {}

    /**
     * [TerminalSession.stickyModifiers] changed to [modifiers]: set by the app, or cleared after
     * they applied to a key. Called on the thread that changed them.
     */
    public fun onStickyModifiersChanged(modifiers: KeyModifiers) {}
}

// The threads follow docs/decisions/0024 and 0026.
/**
 * A terminal session: the terminal state and the input from the user, connected to a host.
 *
 * The app owns the session. On Android, a Compose `Terminal` displays it, and the session can
 * outlive the composable, across configuration changes for example. A JVM program can use it
 * without a display, to emulate a terminal: [feed] it the host's output, look at the screen with
 * [snapshot], and type with [sendKey] and [sendText]. The app gives the session its connection to
 * the host; vtfx sends everything through it, built or checked by vtfx-gate. The colors belong to
 * the display, so a session that no `Terminal` has displayed does not answer the host's color
 * queries (OSC 4, 10, 11, and 12).
 *
 * Thread-safe: every method may be called from any thread, and the calls are serialized. The
 * session calls the app's code on the thread of the call that caused it, during that call: the
 * listener and the code behind `clipboard` and `urlOpener` without the session's locks held, and
 * `connection` holding only a lock that keeps the user's input in the order typed. Replies to the
 * host's queries are sent on the thread that calls [feed].
 *
 * @param connection The connection to the host, which receives the user's input and the replies to
 *   the host's queries. See [HostConnection] for its contract.
 * @param columns The number of columns until a display sets the size that fits it, or until
 *   [resize]. Start the host either with this size, or with the size of the first
 *   [TerminalSessionListener.onResize], which the first [resize] reports even when it is this size.
 *   From 1 to [TerminalLimits.MAX_DIMENSION].
 * @param rows The number of rows, in the same way as `columns`.
 * @param limits Bounds on the resources that host output can make the terminal use.
 * @param ambiguousWidth The width of East Asian Ambiguous characters. It must match the host's
 *   setting (for example, vim's 'ambiwidth'), or the cursor goes out of place.
 * @param policy Which requests from the host with external effects are allowed: setting and reading
 *   the clipboard with OSC 52, and the URL schemes that [openUrl] opens.
 * @param clipboard The clipboard, for OSC 52 (as the policy allows) and for pasting, such as an
 *   `AndroidClipboard`; or null to refuse OSC 52 and paste nothing from the clipboard.
 * @param urlOpener Opens the URLs that [openUrl] allows, such as an `AndroidUrlOpener`; or null to
 *   open none.
 * @param listener Receives notifications from the session.
 * @throws IllegalArgumentException if `columns` or `rows` is out of range.
 */
public class TerminalSession(
    connection: HostConnection,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    limits: TerminalLimits = TerminalLimits(),
    // The width of ambiguous characters follows docs/decisions/0011.
    ambiguousWidth: AmbiguousWidth = AmbiguousWidth.Narrow,
    policy: GatePolicy = GatePolicy(),
    private val clipboard: ClipboardAccess? = null,
    urlOpener: UrlOpener? = null,
    private val listener: TerminalSessionListener = object : TerminalSessionListener {},
) {
    // Only vtfx-gate builds what is sent to the host: the session hands the connection over at once
    // (docs/decisions/0027).
    private val writer = HostWriter(connection)

    /** Guards the state of the session: the terminal, the IME editing model, and the fields. */
    private val lock = Any()

    /**
     * Serializes the user's input with its sending, so that the host receives it in the order that
     * the IME editing model made it, also from several threads. Taken before [lock], and held while
     * the app's code behind [writer] runs.
     */
    private val inputLock = Any()

    /**
     * The width of East Asian Ambiguous characters, which a display needs to draw the composing
     * text.
     *
     * @suppress
     */
    @InternalVtfxApi public val ambiguousWidth: AmbiguousWidth = ambiguousWidth

    /**
     * The colors of the display, which replies to color queries report, or null to not answer them.
     * Set by a display.
     *
     * @suppress
     */
    @InternalVtfxApi @Volatile public var reportedColors: ReportedColors? = null

    private val gate =
        TerminalGate(
            writer,
            policy,
            clipboard,
            urlOpener,
            colors = { target -> reportedColors?.rgb(target) },
        )
    private val terminal = Terminal(columns, rows, limits, ambiguousWidth) { onTerminalEvent(it) }

    /** Tell [listener] the latest values. Each value is recorded with [lock] held, in order. */
    private val sizeNotifier =
        LatestValueNotifier<Pair<Int, Int>> { (columns, rows) ->
            listener.onResize(columns, rows)
        }
    private val titleNotifier = LatestValueNotifier<String> { listener.onTitleChanged(it) }
    private val stickyModifiersNotifier =
        LatestValueNotifier<KeyModifiers> { listener.onStickyModifiersChanged(it) }

    /** The displays of this session, which add themselves while they show the session. */
    private val observers = CopyOnWriteArrayList<SessionObserver>()

    /**
     * Whether the host rang the bell, and the title it set last, during the current [feed]. Guarded
     * by [lock]. They are reported after the write, once per feed.
     */
    private var bellRung = false
    private var titleSet: String? = null

    /**
     * Whether [resize] has resized the session, so that only its first call reports an unchanged
     * size. Guarded by [lock].
     */
    private var resized = false

    /**
     * The requests to the gate (replies and OSC 52) during the current [feed], in order. Guarded by
     * [lock]. They are handled after the lock is released, since the gate calls the app's code
     * (docs/decisions/0024).
     */
    private val gateRequests = ArrayList<TerminalEvent>()

    /**
     * What the current input operation sends to the host, in order. Guarded by [lock]. Sent after
     * the lock is released, holding only [inputLock].
     */
    private val outbox = ArrayList<() -> Unit>()

    /** Whether the IME editing model asked to restart the input during the current operation. */
    private var restartRequested = false

    /** Whether to reset the IME editing model after the current operation. */
    private var resetRequested = false

    private var titleState = ""

    /** The window title set by the host. Untrusted text. */
    public val title: String
        get() = synchronized(lock) { titleState }

    private var stickyModifiersState = KeyModifiers.None

    /**
     * Modifiers to apply to the next key or typed character, for an auxiliary key bar with Ctrl and
     * Alt keys. Cleared once applied. [TerminalSessionListener.onStickyModifiersChanged] reports
     * the changes. Setting modifiers first sends the composing text, which was typed before them,
     * without them: they apply only to what is typed after.
     */
    public var stickyModifiers: KeyModifiers
        get() = synchronized(lock) { stickyModifiersState }
        set(value) {
            // The modifiers are changed inside the input operation, which reports the change.
            input(notifySent = true) { editor ->
                if (value == stickyModifiersState) return@input
                // Text typed before the modifiers must not take them (docs/decisions/0033).
                if (!value.isEmpty) editor.sendPendingInput()
                stickyModifiersState = value
            }
        }

    private var directInputState = false

    // Direct input is chosen in docs/decisions/0017.
    /**
     * Whether the IME is asked for plain characters, which it commits one at a time, instead of
     * text with suggestions and composition. Some IMEs keep a whole English word as composing text
     * until a space, so that an app that acts on each key, such as vim in normal mode, gets nothing
     * until then; direct input avoids that, but most IMEs then offer no Japanese or Korean input.
     * Off by default. Changing it commits the composing text and restarts the input, for example
     * from a toggle on an auxiliary key bar.
     */
    public var directInput: Boolean
        get() = synchronized(lock) { directInputState }
        set(value) {
            val changed = input { editor ->
                if (value == directInputState) return@input false
                directInputState = value
                editor.finishComposingText()
                editor.requestReset()
                true
            }
            if (changed) notifyUserInput()
        }

    /** Whether to send keys without the sticky modifiers, during [sendScrollKey]. */
    private var keepStickyModifiers = false

    /** Used only within [input], with [lock] held. */
    private val editor =
        ImeEditor(
            object : ImeEditor.Listener {
                override fun send(input: HostInput) {
                    val modes = terminal.modes
                    when (input) {
                        is HostInput.Text ->
                            if (stickyModifiersState.isEmpty)
                                outbox += { writer.sendText(input.text) }
                            else sendTextWithModifiers(input.text, modes)
                        is HostInput.Delete -> {
                            // The count is bounded by the history's limit and the DELs before the
                            // history, twice the limit, far below what the writer takes at once.
                            // It is split anyway, so that the editor's limit is not tied to the
                            // writer's.
                            var remaining = input.count
                            while (remaining > 0) {
                                val count = minOf(remaining, DELETES_PER_WRITE)
                                outbox += { writer.sendDeletes(count) }
                                remaining -= count
                            }
                        }
                        is HostInput.Key -> {
                            val stroke =
                                if (keepStickyModifiers) input.stroke
                                else withStickyModifiers(input.stroke)
                            // The editing model decided on the key without the modifiers, and
                            // keeps the history after a plain BS. With them, the host's line can
                            // no longer be predicted (ime-contract.md section 5.1).
                            if (stroke != input.stroke) resetRequested = true
                            outbox += { writer.sendKey(stroke, modes) }
                        }
                        is HostInput.Paste -> outbox += {
                                writer.sendPaste(input.text, modes.bracketedPaste)
                            }
                    }
                }

                override fun restartInput() {
                    restartRequested = true
                }
            }
        )

    /** Sends the first character of [text] with [stickyModifiers], and the rest as text. */
    private fun sendTextWithModifiers(text: String, modes: TerminalModes) {
        val first = text.codePointAt(0)
        val stroke = withStickyModifiers(KeyStroke.Character(first))
        outbox += { writer.sendKey(stroke, modes) }
        val rest = text.substring(Character.charCount(first))
        if (rest.isNotEmpty()) outbox += { writer.sendText(rest) }
        // The host did not get the character the IME's history has. Reset after the edit, since
        // the editing model is in the middle of it.
        resetRequested = true
    }

    private fun withStickyModifiers(stroke: KeyStroke): KeyStroke {
        val modifiers = stickyModifiersState
        if (modifiers.isEmpty) return stroke
        stickyModifiersState = KeyModifiers.None
        return when (stroke) {
            is KeyStroke.Special -> stroke.copy(modifiers = stroke.modifiers.union(modifiers))
            is KeyStroke.Character -> stroke.copy(modifiers = stroke.modifiers.union(modifiers))
        }
    }

    /**
     * Runs [operation] on the IME editing model, sends what it made to the host, and then tells the
     * displays and the listener about its effects, without the locks held. Returns what [operation]
     * returns. With [notifySent], the displays are also told of the user's input if it sent any.
     */
    private fun <T> input(notifySent: Boolean = false, operation: (ImeEditor) -> T): T {
        val result: T
        val restart: Boolean
        val sent: Boolean
        synchronized(inputLock) {
            val sends: List<() -> Unit>
            synchronized(lock) {
                check(outbox.isEmpty() && !restartRequested && !resetRequested)
                val stickyModifiersBefore = stickyModifiersState
                try {
                    result = operation(editor)
                    if (resetRequested) {
                        resetRequested = false
                        editor.requestReset()
                    }
                } finally {
                    sends = outbox.toList()
                    outbox.clear()
                    restart = restartRequested
                    restartRequested = false
                    resetRequested = false
                }
                if (stickyModifiersState != stickyModifiersBefore) {
                    stickyModifiersNotifier.record(stickyModifiersState)
                }
            }
            sent = sends.isNotEmpty()
            for (send in sends) send()
        }
        if (restart) for (observer in observers) observer.onRestartInput()
        stickyModifiersNotifier.tell()
        if (notifySent && sent) notifyUserInput()
        return result
    }

    /**
     * Runs [operation] on the IME editing model, as an IME adapter does for each call of the IME,
     * and sends what it made to the host. Returns what [operation] returns. [operation] must not
     * keep the editing model or call back into this session.
     *
     * The displays are told of the user's input only if [operation] sent some: the IME also calls
     * without any effect, for example with a key event of a modifier key alone, and such a call
     * must not move the display or drop its selection.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun <T> edit(operation: (ImeEditor) -> T): T = input(notifySent = true, operation)

    /**
     * Interprets output from the host: the [length] bytes of [bytes] from [offset]. Calls are
     * serialized, and the session does not keep [bytes]. Must not be called from the app's code
     * that sends to the host, behind [HostConnection], and throws [IllegalStateException] there:
     * hand the output to another task instead.
     *
     * @throws IllegalArgumentException if [offset] and [length] are not within [bytes].
     */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        // Checked even when the output asks for no reply, so that the mistake does not depend on
        // what the host sends.
        check(!writer.isWritingOnCurrentThread()) {
            "feed must not be called while sending to the host; post the output instead"
        }
        var synchronizing = false
        var scrolledLines = 0L
        var scrollbackShrank = false
        var rang = false
        var requests: List<TerminalEvent> = emptyList()
        val moved =
            synchronized(lock) {
                val lineChangesBefore = terminal.lineChanges
                val scrollbackBefore = terminal.scrollbackSize
                val pushesBefore = terminal.scrollbackPushes
                terminal.write(bytes, offset, length)
                scrolledLines = terminal.scrollbackPushes - pushesBefore
                scrollbackShrank = terminal.scrollbackSize < scrollbackBefore
                synchronizing = terminal.modes.synchronizedOutput
                rang = bellRung
                titleSet?.let {
                    titleState = it
                    titleNotifier.record(it)
                }
                bellRung = false
                titleSet = null
                if (gateRequests.isNotEmpty()) {
                    requests = gateRequests.toList()
                    gateRequests.clear()
                }
                // Following the echo of long input over autowrapped rows is not a move to another
                // line, while switching the screen or a reset is (docs/design/ime-contract.md
                // section 5.1). Counting them during the write also catches a switch there and
                // back.
                terminal.lineChanges != lineChangesBefore
            }
        if (moved) input { it.requestReset() }
        for (observer in observers) observer.onOutput(
            scrolledLines,
            scrollbackShrank,
            synchronizing,
        )
        if (rang) listener.onBell()
        titleNotifier.tell()
        // Last, so that an exception from the app's code leaves the session up to date.
        for (request in requests) gate.handle(request)
    }

    /**
     * Returns a copy of the screen, scrolled back by [scrollOffset] lines into the scrollback, as a
     * display shows it. The offset is clamped to [scrollbackSize]; with the default of 0, the
     * snapshot is the screen.
     */
    public fun snapshot(scrollOffset: Int = 0): TerminalSnapshot =
        synchronized(lock) { terminal.snapshot(scrollOffset) }

    /**
     * Returns copies of the last [maxLines] lines of the scrollback and the screen, oldest first,
     * taken at once, for a display's selection of all the text. On the alternate screen, which has
     * no scrollback, they are the screen's rows.
     *
     * @throws IllegalArgumentException if [maxLines] is negative.
     * @suppress
     */
    @InternalVtfxApi
    public fun lines(maxLines: Int): List<LineSnapshot> =
        synchronized(lock) { terminal.lines(maxLines) }

    /**
     * The number of lines in the scrollback that [snapshot] can show: none on the alternate screen,
     * which has no scrollback.
     */
    public val scrollbackSize: Int
        get() =
            synchronized(lock) { if (terminal.isAlternateScreen) 0 else terminal.scrollbackSize }

    /**
     * Resizes the terminal to [columns] and [rows] at once, and calls
     * [TerminalSessionListener.onResize] if the size changed, or if this is the first resize of the
     * session, even when the size is the one it was created with. A Compose `Terminal` resizes the
     * session to fit itself as soon as it is laid out; call it only for a session without a
     * display.
     *
     * @throws IllegalArgumentException if [columns] or [rows] is not from 1 to
     *   [TerminalLimits.MAX_DIMENSION].
     */
    public fun resize(columns: Int, rows: Int) {
        val (report, changed) =
            synchronized(lock) {
                val changed = terminal.columns != columns || terminal.rows != rows
                if (changed) terminal.resize(columns, rows)
                val report = changed || !resized
                resized = true
                if (report) sizeNotifier.record(Pair(columns, rows))
                Pair(report, changed)
            }
        if (report) sizeNotifier.tell()
        if (changed) for (observer in observers) observer.onScreenChanged()
    }

    /**
     * Opens [url] if the URL schemes of the gate's policy allow it, and returns whether it did.
     * Call it only for an explicit action of the user, after showing them the URL, for example in a
     * confirmation dialog for a tapped link, so that a link cannot pass for another site.
     */
    public fun openUrl(url: String): Boolean = gate.openUrl(url)

    /**
     * Returns whether [openUrl] would open [url]: the session has a `urlOpener`, and the URL
     * schemes of the gate's policy allow it. Check it before asking the user to confirm a link, so
     * as not to ask about one that cannot be opened.
     */
    public fun canOpenUrl(url: String): Boolean = gate.canOpenUrl(url)

    /** Sends a key, for example from an auxiliary key bar. Composing text is committed first. */
    public fun sendKey(stroke: KeyStroke) {
        input { it.onKeyDown(stroke) }
        notifyUserInput()
    }

    // Sticky modifiers apply to the next typed key or character (docs/decisions/0012).
    /**
     * Sends a key that a scroll gesture makes, such as an arrow key on the alternate screen, as
     * [sendKey] does, but without [stickyModifiers], which are for the keys the user types: they
     * stay armed.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendScrollKey(stroke: KeyStroke) {
        input { editor ->
            keepStickyModifiers = true
            try {
                editor.onKeyDown(stroke)
            } finally {
                keepStickyModifiers = false
            }
        }
        notifyUserInput()
    }

    /** Sends text as if typed. Composing text is committed first. */
    public fun sendText(text: String) {
        input {
            it.finishComposingText()
            it.commitText(text)
        }
        notifyUserInput()
    }

    /**
     * Pastes [text], bracketed if the host enabled bracketed paste. Composing text is committed and
     * sent first.
     */
    public fun paste(text: String) {
        input { it.paste(text) }
        notifyUserInput()
    }

    /** Pastes the text on the clipboard, if there is a clipboard and it has text. */
    public fun pasteFromClipboard() {
        val text = clipboard?.read() ?: return
        paste(text)
    }

    /**
     * Sets the app's cursor style, which the host's DECSCUSR overrides.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun setDefaultCursorStyle(shape: CursorShape, blinking: Boolean) {
        val changed = synchronized(lock) { terminal.setDefaultCursorStyle(shape, blinking) }
        if (changed) for (observer in observers) observer.onScreenChanged()
    }

    /**
     * Copies [text] to the clipboard, for a display's selection. Returns whether there is a
     * clipboard to copy it to.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun copyToClipboard(text: String): Boolean {
        val clipboard = clipboard ?: return false
        clipboard.write(text)
        return true
    }

    /**
     * Sends a mouse report, if the host asked for this kind of event.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendMouse(report: MouseReport) {
        input {
            val modes = terminal.modes
            outbox += { writer.sendMouse(report, modes.mouseTracking, modes.mouseEncoding) }
        }
    }

    /**
     * Tells the session whether the display that gets the user's input has the focus: reports it to
     * the host if the host asked for focus events, and resets the IME editing model on a loss.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun onFocusChanged(focused: Boolean) {
        input { editor ->
            if (terminal.modes.focusEvents) outbox += { writer.sendFocus(focused) }
            if (!focused) editor.requestReset()
        }
    }

    /**
     * Returns the terminal modes that the host set.
     *
     * @suppress
     */
    @InternalVtfxApi public fun modes(): TerminalModes = synchronized(lock) { terminal.modes }

    /**
     * Returns whether the alternate screen is shown.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun isAlternateScreen(): Boolean = synchronized(lock) { terminal.isAlternateScreen }

    /**
     * Returns the number of columns and rows.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun size(): Pair<Int, Int> = synchronized(lock) { Pair(terminal.columns, terminal.rows) }

    /**
     * Returns the cursor as (column, row).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun cursorPosition(): Pair<Int, Int> =
        synchronized(lock) {
            val cursor = terminal.cursor
            Pair(cursor.column, cursor.row)
        }

    /**
     * Starts telling [observer] about the changes that a display shows.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun addObserver(observer: SessionObserver) {
        observers += observer
    }

    /**
     * Stops telling [observer] about changes.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun removeObserver(observer: SessionObserver) {
        observers -= observer
    }

    /** Tells the displays that the user's input was sent, so that they return to the bottom. */
    private fun notifyUserInput() {
        for (observer in observers) observer.onUserInput()
    }

    /** Called with the lock held, on the thread that called [feed]. */
    private fun onTerminalEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.TitleChanged -> titleSet = event.title
            TerminalEvent.Bell -> bellRung = true
            else -> gateRequests += event
        }
    }

    public companion object {
        /** The number of columns until the session is resized. */
        public const val DEFAULT_COLUMNS: Int = 80

        /** The number of rows until the session is resized. */
        public const val DEFAULT_ROWS: Int = 24

        /** DELs per call of `HostWriter.sendDeletes`, well below its limit. */
        private const val DELETES_PER_WRITE = 4096
    }
}

/**
 * Receives the changes of a [TerminalSession] that a display shows. Called on the thread that made
 * the change, without the session's locks held.
 *
 * @suppress
 */
@InternalVtfxApi
public interface SessionObserver {
    /**
     * The host's output changed the screen: [scrolledLines] lines entered the scrollback, and
     * [scrollbackShrank] if lines left it. While [synchronizing], the host is in the middle of a
     * synchronized update (mode 2026), which should be drawn when it ends.
     */
    public fun onOutput(scrolledLines: Long, scrollbackShrank: Boolean, synchronizing: Boolean)

    /** The screen changed otherwise, for example by a resize. */
    public fun onScreenChanged()

    /** The user's input was sent to the host. */
    public fun onUserInput()

    /** The IME edit buffer was reset, so the IME must restart its input. */
    public fun onRestartInput()
}
