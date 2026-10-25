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
import com.google.vtfx.core.ProtocolLimits
import com.google.vtfx.core.SearchLines
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalEvent
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalModes
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.gate.ClipboardAccess
import com.google.vtfx.gate.ClipboardText
import com.google.vtfx.gate.GatePolicy
import com.google.vtfx.gate.HostConnection
import com.google.vtfx.gate.HostWriter
import com.google.vtfx.gate.MouseAction
import com.google.vtfx.gate.MouseReport
import com.google.vtfx.gate.OpenUrlResult
import com.google.vtfx.gate.ReportedColors
import com.google.vtfx.gate.TerminalGate
import com.google.vtfx.gate.UrlOpener
import com.google.vtfx.input.HostInput
import com.google.vtfx.input.ImeEditor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
     * The terminal size is [columns] by [rows], and [widthPixels] by [heightPixels] in pixels, or 0
     * by 0 if no display has given the size of a cell. Programs that show images read the pixels
     * from the host's window size, so give them to the host too: the pixel fields of an SSH
     * window-change request, or `ws_xpixel` and `ws_ypixel` of TIOCSWINSZ. Called as the other
     * overload, which it calls by default, so that a listener overrides one of them.
     */
    public fun onResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
        onResize(columns, rows)
    }

    /**
     * The host rang the bell. Called on the thread that called [TerminalSession.feed], once per
     * call however many bells the output has.
     */
    public fun onBell() {}

    /**
     * The host set the window title to [title], which [TerminalSession.title] now returns.
     * Untrusted text, at most 1024 UTF-16 units long, without control, line separator, and
     * bidirectional formatting characters. Called on the thread that called [TerminalSession.feed],
     * once per call with the last title the output set.
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
 * listener and the code behind `urlOpener` and the user's copy and paste without the session's
 * locks held; `connection`, and `clipboard` for the host's OSC 52, holding only locks that keep the
 * user's input in the order typed and the effects of the host's output in its order. Replies to the
 * host's queries are sent on the thread that calls [feed], before the listener hears of the output.
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
    // The width of ambiguous characters follows docs/decisions/0079.
    ambiguousWidth: AmbiguousWidth = AmbiguousWidth.Narrow,
    policy: GatePolicy = GatePolicy(),
    clipboard: ClipboardAccess? = null,
    urlOpener: UrlOpener? = null,
    private val listener: TerminalSessionListener = object : TerminalSessionListener {},
) {
    // Only vtfx-gate builds what is sent to the host: the session hands the connection over at once
    // (docs/decisions/0027).
    private val writer = HostWriter(connection)

    /**
     * Guards the state of the session: the terminal, the IME editing model, and the fields. Fair,
     * so that a thread that waits for it, such as the main thread taking the screen for a frame,
     * gets it when the holder releases it: while output floods, [feed] takes it again for each
     * chunk at once, and an intrinsic lock may hand it back to [feed] for hundreds of milliseconds
     * (docs/decisions/0065).
     */
    private val lock = ReentrantLock(/* fair= */ true)

    /**
     * Serializes the user's input with its sending, so that the host receives it in the order that
     * the IME editing model made it, also from several threads. Taken before [lock], and held while
     * the app's code behind [writer] runs. Fair, so that the threads waiting for it while the
     * connection takes bytes take it in the order they came: an intrinsic lock may hand it to the
     * latest first.
     */
    private val inputLock = ReentrantLock(/* fair= */ true)

    /**
     * Serializes [feed] from the interpretation of the output to the end of the gate's handling of
     * its requests, so that the replies and the clipboard writes of each feed reach the app in the
     * order the output was interpreted, also from several threads, and before the listener, which
     * may call the session, hears of the output. Taken before [lock], and held while the app's code
     * behind the gate runs, but not while the session tells the observers or the listener; it
     * guards no state. Fair, like [inputLock]. An exception from the app's code behind the gate
     * propagates without the observers and the listener being told of the output (rule 4 of
     * docs/design/session-and-threads.md section 1.3).
     */
    private val outputLock = ReentrantLock(/* fair= */ true)

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

    // The session hands the clipboard and the URL opener to the gate without calling them, also for
    // the user's copy and paste (docs/decisions/0083).
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
        LatestValueNotifier<ReportedSize> { size ->
            listener.onResize(size.columns, size.rows, size.widthPixels, size.heightPixels)
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

    /** The size of a cell in pixels that a display gave, or 0. Guarded by [lock]. */
    private var cellWidth = 0
    private var cellHeight = 0

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

    /**
     * Whether the host's output moved the cursor to another line, so that the IME editing model is
     * reset before the next input operation. Guarded by [lock]. Set by [feed], which then runs an
     * input operation to apply it, unless another one takes the input lock first and applies it
     * before its edit.
     */
    private var hostResetPending = false

    /**
     * The thread running an input operation, or null. Written with [lock] held; read without it
     * only to compare with the current thread, which is the only thread that can store itself.
     */
    @Volatile private var editingThread: Thread? = null

    private var titleState = ""

    /** The window title set by the host. Untrusted text. */
    public val title: String
        get() = lock.withLock { titleState }

    private var stickyModifiersState = KeyModifiers.None

    /**
     * Modifiers to apply to the next key or typed character, for an auxiliary key bar with Ctrl and
     * Alt keys. Cleared once applied. [TerminalSessionListener.onStickyModifiersChanged] reports
     * the changes. Setting modifiers first sends the composing text, which was typed before them,
     * without them: they apply only to what is typed after.
     */
    public var stickyModifiers: KeyModifiers
        get() = lock.withLock { stickyModifiersState }
        set(value) {
            // The modifiers are changed inside the input operation, which reports the change.
            input(notifySent = true) { editor ->
                if (value == stickyModifiersState) return@input
                // Text typed before the modifiers must not take them, not even when the IME
                // corrects it later (docs/decisions/0082).
                if (!value.isEmpty) editor.sendPendingInput()
                stickyModifiersState = value
            }
        }

    private var directInputState = false

    // Direct input is chosen in docs/decisions/0082.
    /**
     * Whether the IME is asked for plain characters, which it commits one at a time, instead of
     * text with suggestions and composition. Some IMEs keep a whole English word as composing text
     * until a space, so that an app that acts on each key, such as vim in normal mode, gets nothing
     * until then; direct input avoids that, but most IMEs then offer no Japanese or Korean input.
     * Off by default. Changing it commits the composing text and restarts the input, for example
     * from a toggle on an auxiliary key bar.
     */
    public var directInput: Boolean
        get() = lock.withLock { directInputState }
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

    /**
     * The text on the clipboard that the current input operation of [pasteFromClipboard] pastes,
     * which only the gate reads, or null. Used only within [input], with [lock] held.
     */
    private var pastedClipboard: ClipboardText? = null

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
                        // The count is bounded by the history's limit and the DELs before the
                        // history, twice the default limit, far below what the writer takes.
                        is HostInput.Delete -> outbox += { writer.sendDeletes(input.count) }
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
                        HostInput.ClipboardPaste -> {
                            val text =
                                checkNotNull(pastedClipboard) { "no clipboard text to paste" }
                            pastedClipboard = null
                            outbox += { writer.sendPaste(text, modes.bracketedPaste) }
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
        // HT, the only control that the editing model's text has (line breaks are keys or a
        // paste), is the Tab key, which the key encoding has with every modifier: it drops a
        // control character with modifiers, which would send nothing. Text has no key on a
        // keyboard, so its unshifted key is the character in lower case (docs/decisions/0083).
        val stroke =
            withStickyModifiers(
                if (first == '\t'.code) KeyStroke.Special(TerminalKey.Tab)
                else
                    KeyStroke.Character(
                        first,
                        KeyModifiers.None,
                        Character.toLowerCase(first),
                        null,
                    )
            )
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
        // Checked before the locks, which are reentrant: a nested call would change the IME
        // editing model before HostWriter refused to send what it made, and the model would take
        // it for received by the host (docs/decisions/0024).
        check(!writer.isWritingOnCurrentThread()) {
            "input must not be sent while sending to the host; post it instead"
        }
        check(!isEditingOnCurrentThread()) {
            "an input operation must not call back into the session"
        }
        // The input would reach the host before the rest of the output's replies.
        check(!outputLock.isHeldByCurrentThread) {
            "input must not be sent from the app's code that handles the host's output"
        }
        val result: T
        val restart: Boolean
        val sent: Boolean
        inputLock.withLock {
            val sends: List<() -> Unit>
            lock.withLock {
                check(outbox.isEmpty() && !restartRequested && !resetRequested)
                val stickyModifiersBefore = stickyModifiersState
                editingThread = Thread.currentThread()
                try {
                    if (hostResetPending) {
                        hostResetPending = false
                        editor.requestReset()
                    }
                    result = operation(editor)
                    if (resetRequested) {
                        resetRequested = false
                        editor.requestReset()
                    }
                } finally {
                    editingThread = null
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

    private fun isEditingOnCurrentThread(): Boolean = editingThread === Thread.currentThread()

    /**
     * Runs [operation] on the IME editing model, as an IME adapter does for each call of the IME,
     * and sends what it made to the host. Returns what [operation] returns. [operation] must not
     * keep the editing model or call back into this session; sending input or feeding output from
     * it throws [IllegalStateException].
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
     * that sends to the host, behind [HostConnection], or that handles the host's output, behind
     * `clipboard` for OSC 52, and throws [IllegalStateException] there: hand the output to another
     * task instead.
     *
     * @throws IllegalArgumentException if [offset] and [length] are not within [bytes].
     */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        // Checked even when the output asks for no reply, so that the mistake does not depend on
        // what the host sends.
        check(!writer.isWritingOnCurrentThread()) {
            "feed must not be called while sending to the host; post the output instead"
        }
        // The output would change the terminal in the middle of an input operation.
        check(!isEditingOnCurrentThread()) {
            "an input operation must not call back into the session"
        }
        // The output lock is reentrant, so the nested output's replies would go before the rest of
        // this output's, such as from a ClipboardAccess that the gate calls.
        check(!outputLock.isHeldByCurrentThread) {
            "feed must not be called from the app's code that handles the host's output; post it"
        }
        val synchronizing: Boolean
        val scrolledLines: Long
        val scrollbackShrank: Boolean
        val rang: Boolean
        val moved: Boolean
        outputLock.withLock {
            val requests: List<TerminalEvent>
            lock.withLock {
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
                // Empty, without allocating, if there are none.
                requests = gateRequests.toList()
                gateRequests.clear()
                // Following the echo of long input over autowrapped rows is not a move to another
                // line, while switching the screen or a reset is (docs/design/ime-contract.md
                // section 5.1). Counting them during the write also catches a switch there and
                // back. The reset is marked here, with the lock held, so that an input operation
                // that runs before this feed takes the input lock applies it before its edit.
                moved = terminal.lineChanges != lineChangesBefore
                if (moved) hostResetPending = true
            }
            // Inside the output lock, so that the requests of each feed are handled in the order
            // the output was interpreted, and before the observers and the listener, which may
            // call the session, hear of the output.
            gate.handle(requests)
        }
        // Applies the reset, unless an input operation on another thread already did. After the
        // output lock, since an input operation tells the observers and the listener, which may
        // call the session; any input operation applies the reset before its edit anyway.
        if (moved) input {}
        for (observer in observers) observer.onOutput(
            scrolledLines,
            scrollbackShrank,
            synchronizing,
        )
        if (rang) listener.onBell()
        titleNotifier.tell()
    }

    /**
     * Returns a copy of the screen, scrolled back by [scrollOffset] lines into the scrollback, as a
     * display shows it. The offset is clamped to [scrollbackSize]; with the default of 0, the
     * snapshot is the screen.
     */
    public fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = lock.withLock {
        terminal.snapshot(scrollOffset)
    }

    /**
     * Returns copies of the last [maxLines] lines of the scrollback and the screen, oldest first,
     * taken at once, for a display's selection of all the text. On the alternate screen, which has
     * no scrollback, they are the screen's rows.
     *
     * @throws IllegalArgumentException if [maxLines] is negative.
     * @suppress
     */
    @InternalVtfxApi
    public fun lines(maxLines: Int): List<LineSnapshot> = lock.withLock { terminal.lines(maxLines) }

    /**
     * Returns copies of lines for a display's search, from line [from], taken at once: at least
     * [minLines] lines of the scrollback and more to the end of a text to search, or through the
     * screen (`Terminal.searchLines`). A display reads [minLines] at a time, so that the lock is
     * not held while the whole scrollback is copied.
     *
     * @throws IllegalArgumentException unless [minLines] is positive.
     * @suppress
     */
    @InternalVtfxApi
    public fun searchLines(from: Long, minLines: Int): SearchLines = lock.withLock {
        terminal.searchLines(from, minLines)
    }

    /**
     * The number of lines in the scrollback that [snapshot] can show: none on the alternate screen,
     * which has no scrollback.
     */
    public val scrollbackSize: Int
        get() = lock.withLock { if (terminal.isAlternateScreen) 0 else terminal.scrollbackSize }

    /**
     * Resizes the terminal to [columns] and [rows] at once, and calls
     * [TerminalSessionListener.onResize] if the size changed, or if this is the first resize of the
     * session, even when the size is the one it was created with. A Compose `Terminal` resizes the
     * session to fit itself as soon as it is laid out; call it only for a session without a
     * display.
     *
     * [cellWidth] and [cellHeight] are the size of a cell in pixels, which a display gives, or 0 to
     * keep the size given before. The host can ask for it, and images that the host sends without a
     * size in cells take cells of that size; until it is given, the host's queries for pixel sizes
     * are not answered, and images take cells of 10 by 20 pixels.
     *
     * @throws IllegalArgumentException if [columns] or [rows] is not from 1 to
     *   [TerminalLimits.MAX_DIMENSION], or if [cellWidth] or [cellHeight] is not from 0 to 1000, or
     *   only one of them is 0.
     */
    public fun resize(columns: Int, rows: Int, cellWidth: Int = 0, cellHeight: Int = 0) {
        require(
            cellWidth in 0..ProtocolLimits.MAX_CELL_SIZE &&
                cellHeight in 0..ProtocolLimits.MAX_CELL_SIZE
        ) {
            "cell size out of range: ${cellWidth}x$cellHeight"
        }
        require((cellWidth == 0) == (cellHeight == 0)) {
            "cell size must have both sides or neither: ${cellWidth}x$cellHeight"
        }
        val report: Boolean
        val changed: Boolean
        lock.withLock {
            changed = terminal.columns != columns || terminal.rows != rows
            if (changed) terminal.resize(columns, rows)
            val cellChanged =
                cellWidth != 0 && (cellWidth != this.cellWidth || cellHeight != this.cellHeight)
            if (cellChanged) {
                terminal.setCellSize(cellWidth, cellHeight)
                this.cellWidth = cellWidth
                this.cellHeight = cellHeight
            }
            report = changed || cellChanged || !resized
            resized = true
            if (report) {
                sizeNotifier.record(
                    ReportedSize(
                        columns,
                        rows,
                        columns * this.cellWidth,
                        rows * this.cellHeight,
                    )
                )
            }
        }
        if (report) sizeNotifier.tell()
        if (changed) for (observer in observers) observer.onScreenChanged()
    }

    /** The size that [TerminalSessionListener.onResize] reports. */
    private class ReportedSize(
        val columns: Int,
        val rows: Int,
        val widthPixels: Int,
        val heightPixels: Int,
    )

    /**
     * Opens [url] if the URL schemes of the gate's policy allow it, and returns what came of it:
     * [OpenUrlResult.NotAllowed] if the session has no `urlOpener` or the policy does not allow the
     * URL, and otherwise what the `urlOpener` reported, such as [OpenUrlResult.NoApp]. Call it only
     * for an explicit action of the user, after showing them the URL, for example in a confirmation
     * dialog for a tapped link, so that a link cannot pass for another site.
     */
    public fun openUrl(url: String): OpenUrlResult = gate.openUrl(url)

    /**
     * Returns whether [openUrl] would try to open [url] rather than return
     * [OpenUrlResult.NotAllowed]: the session has a `urlOpener`, and the URL schemes of the gate's
     * policy allow it. Check it before asking the user to confirm a link, so as not to ask about
     * one that vtfx does not allow.
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
     * stay armed. It takes a [TerminalKey], without modifiers, rather than a [KeyStroke], so that a
     * display can send no text with it (docs/decisions/0075).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendScrollKey(key: TerminalKey) {
        input { editor ->
            keepStickyModifiers = true
            try {
                editor.onKeyDown(KeyStroke.Special(key))
            } finally {
                keepStickyModifiers = false
            }
        }
        notifyUserInput()
    }

    /**
     * Sends text as if typed. Composing text is committed first. Each line break is sent as Enter,
     * even if the host enabled bracketed paste: send text from elsewhere, such as the clipboard,
     * with [paste]. HT is sent as is, and other control characters, such as ESC, are removed: send
     * Esc and keys with Ctrl with [sendKey]. The IME does not see the text afterwards, so it may be
     * a secret, such as a saved password.
     */
    public fun sendText(text: String) {
        input { it.typeText(text) }
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
        // The session never sees the text: it goes from the gate to HostWriter as one paste
        // (docs/decisions/0075).
        val text = gate.readClipboardForPaste() ?: return
        input { editor ->
            pastedClipboard = text
            try {
                editor.pasteClipboard()
            } finally {
                pastedClipboard = null
            }
        }
        notifyUserInput()
    }

    /**
     * Sets the app's cursor style, which the host's DECSCUSR overrides.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun setDefaultCursorStyle(shape: CursorShape, blinking: Boolean) {
        val changed = lock.withLock { terminal.setDefaultCursorStyle(shape, blinking) }
        if (changed) for (observer in observers) observer.onScreenChanged()
    }

    /**
     * Copies [text] to the clipboard, for a display's selection. Returns whether there is a
     * clipboard to copy it to.
     *
     * @suppress
     */
    @InternalVtfxApi public fun copyToClipboard(text: String): Boolean = gate.copyToClipboard(text)

    /**
     * Sends a mouse report, if the host asked for this kind of event. A press first sends the
     * composing text and resets the IME editing model, since it can move the host's cursor.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendMouse(report: MouseReport) {
        input { editor ->
            // As a key would (docs/decisions/0082). Not motion, which comes in streams while the
            // user may be composing.
            if (report.action == MouseAction.Press) editor.sendPendingInput()
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
    @InternalVtfxApi public fun modes(): TerminalModes = lock.withLock { terminal.modes }

    /**
     * Returns whether the alternate screen is shown.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun isAlternateScreen(): Boolean = lock.withLock { terminal.isAlternateScreen }

    /**
     * Returns the number of columns and rows.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun size(): Pair<Int, Int> = lock.withLock { Pair(terminal.columns, terminal.rows) }

    /**
     * Returns the cursor as (column, row).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun cursorPosition(): Pair<Int, Int> = lock.withLock {
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
            is TerminalEvent.ReplyRequested,
            is TerminalEvent.ClipboardWriteRequested,
            is TerminalEvent.ClipboardReadRequested -> gateRequests += event
        }
    }

    public companion object {
        // Plain vals rather than consts, so that apps read the values of the vtfx they run with,
        // not
        // those they were compiled with (docs/decisions/0037).
        /** The number of columns until the session is resized. */
        public val DEFAULT_COLUMNS: Int = 80

        /** The number of rows until the session is resized. */
        public val DEFAULT_ROWS: Int = 24
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
