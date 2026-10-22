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

package com.google.vtfx.input

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey

/**
 * The IME editing model of docs/design/ime-contract.md, independent of Android.
 *
 * The edit buffer that the IME sees is the input history followed by the composing text, with the
 * cursor always at the end (invariant 1). The input history is what was committed and sent since
 * the last reset. Edits to it are turned into DEL and new text by comparing it with what was sent,
 * when the outermost batch edit ends (section 4.1).
 *
 * The methods mirror `InputConnection`, with the same return values, so that the Android adapter
 * only delegates. Offsets are in UTF-16 code units, as in `InputConnection`.
 *
 * Not thread-safe: calls must not overlap. They may come from any thread; the Android adapter makes
 * them on the UI thread, where the IME calls arrive (section 8). [Listener] is called during the
 * calls, on the same thread.
 *
 * @suppress
 */
@InternalVtfxApi
public class ImeEditor(
    private val listener: Listener,
    /** The longest input history, in code points, before a reset (invariant 5). */
    private val maxHistoryLength: Int = DEFAULT_MAX_HISTORY_LENGTH,
) {
    /** Receives the effects of edits. */
    @InternalVtfxApi
    public interface Listener {
        /** Sends [input] to the host. */
        public fun send(input: HostInput)

        /**
         * Tells the IME that the edit buffer was reset, by calling
         * `InputMethodManager.restartInput` on Android (section 5.2).
         */
        public fun restartInput()
    }

    init {
        require(maxHistoryLength > 0) { "maxHistoryLength must be positive" }
    }

    private var history = ""
    private var composing = ""

    /** The input history as last sent to the host. */
    private var sentHistory = ""

    /**
     * DELs for text before the start of the input history, which the history cannot express. At
     * most [maxHistoryLength]: the IME cannot see that text, so a larger count is not a user's
     * intent, and an untrusted IME must not make the host receive an unbounded number of DELs.
     */
    private var pendingDeletes = 0
    private var batchDepth = 0
    private var resetPending = false

    /**
     * Whether the IME may have seen an edit since the last restart of its input, or since the
     * editor was made. Until then its edit buffer is empty, and a reset does not restart it again,
     * so that host output that moves the cursor to another line does not restart the IME on every
     * receive while the user types nothing (section 5.2).
     */
    private var changedSinceRestart = false

    /**
     * The start of a selection that ends at the cursor, or -1 if the selection is collapsed. Only
     * set while there is no composing text (docs/decisions/0008).
     */
    private var selectionFrom = -1

    /** The edit buffer: the input history followed by the composing text. */
    public val text: String
        get() = history + composing

    /** The cursor, which is always at the end of [text]. It is also the end of the selection. */
    public val cursor: Int
        get() = history.length + composing.length

    /**
     * The start of the selection, which always ends at [cursor]. Equal to [cursor] unless the IME
     * selected text with [setSelection].
     */
    public val selectionStart: Int
        get() = if (selectionFrom >= 0) selectionFrom else cursor

    /** The selected text, or null if the selection is collapsed. */
    public val selectedText: String?
        get() = if (selectionFrom >= 0) history.substring(selectionFrom) else null

    /** The start of the composing text in [text], or -1 if there is none. */
    public val composingStart: Int
        get() = if (composing.isEmpty()) -1 else history.length

    /** The end of the composing text in [text], or -1 if there is none. */
    public val composingEnd: Int
        get() = if (composing.isEmpty()) -1 else cursor

    /** The composing text, which the terminal displays at its cursor (section 7). */
    public val composingText: String
        get() = composing

    /**
     * Whether a batch edit is in progress. The IME is told about selection changes only outside
     * batch edits (section 7).
     */
    public val isBatchEditInProgress: Boolean
        get() = batchDepth > 0

    // --- Edits (section 6.1) ---

    /** Replaces the composing text, or the selection if there is one, with [text]. */
    public fun setComposingText(text: CharSequence): Boolean {
        removeSelectedText()
        composing = withoutDroppedControls(text)
        endOperation()
        return true
    }

    /**
     * Replaces the composing text, or the selection if there is one, with [text] and commits it.
     * Text with a line break, other than a line feed alone, is pasted (section 4.2).
     */
    public fun commitText(text: CharSequence): Boolean {
        removeSelectedText()
        composing = ""
        val committed = withoutDroppedControls(text)
        if (isPaste(committed)) {
            sendPaste(committed)
        } else {
            history += committed
            endOperation()
        }
        return true
    }

    /**
     * Commits the composing text and sends [text] after it, as typed: each line break is sent as
     * Enter. For text from the app, which is not a paste. Both are sent at once, even during a
     * batch edit, since the app's call is not part of the IME's edit, and the input history is then
     * reset, so that the IME never sees the app's text, which may be a secret such as a saved
     * password (docs/decisions/0056).
     */
    public fun typeText(text: String) {
        selectionFrom = -1
        history += composing
        composing = ""
        flush()
        history += withoutDroppedControls(text)
        flush()
        // Not an edit that the IME saw: the reset restarts it only if it saw one before.
        requestReset()
    }

    /** Commits the composing text, which is sent to the host. */
    public fun finishComposingText(): Boolean {
        selectionFrom = -1
        history += composing
        composing = ""
        endOperation()
        return true
    }

    /**
     * Makes [start, end) the composing text. Only a region that ends at the cursor is accepted,
     * since text after the composing text would break invariant 1. An empty region finishes the
     * composition, as in `BaseInputConnection`.
     */
    public fun setComposingRegion(start: Int, end: Int): Boolean {
        selectionFrom = -1
        val buffer = text
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (from < 0 || to > buffer.length || splitsSurrogatePair(buffer, from)) return false
        if (from == to) return finishComposingText()
        if (to != buffer.length) return false
        history = buffer.substring(0, from)
        composing = buffer.substring(from)
        endOperation()
        return true
    }

    /**
     * Deletes [beforeLength] code units before the cursor. What reaches before the input history is
     * sent as DEL, since it is what the user meant by pressing BS. [afterLength] is ignored: there
     * is no text after the cursor.
     */
    public fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        selectionFrom = -1
        val buffer = text
        var cut = maxOf(0, buffer.length - beforeLength)
        if (splitsSurrogatePair(buffer, cut)) cut--
        deleteBufferFrom(cut, outside = maxOf(0, beforeLength - buffer.length))
        return true
    }

    /** Same as [deleteSurroundingText], in code points. */
    public fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        selectionFrom = -1
        val buffer = text
        val available = buffer.codePointCount(0, buffer.length)
        val count = minOf(beforeLength, available)
        val cut = buffer.offsetByCodePoints(buffer.length, -count)
        deleteBufferFrom(cut, outside = beforeLength - count)
        return true
    }

    private fun deleteBufferFrom(cut: Int, outside: Int) {
        if (cut < history.length) {
            history = history.substring(0, cut)
            composing = ""
        } else {
            composing = composing.substring(0, cut - history.length)
        }
        // Added as Long, since both may be near Int.MAX_VALUE.
        pendingDeletes = minOf(pendingDeletes.toLong() + outside, maxHistoryLength.toLong()).toInt()
        endOperation()
    }

    /**
     * Replaces [start, end) with [text]. A range outside the edit buffer is rejected, since its
     * content is unknown. The composing text is finished first. Text with a line break, other than
     * a line feed alone, is pasted with the rest of the edit buffer after it (section 4.2).
     */
    public fun replaceText(start: Int, end: Int, text: CharSequence): Boolean {
        val buffer = this.text
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (from < 0 || to > buffer.length) return false
        if (splitsSurrogatePair(buffer, from) || splitsSurrogatePair(buffer, to)) return false
        selectionFrom = -1
        val replacement = withoutDroppedControls(text)
        composing = ""
        if (isPaste(replacement)) {
            history = buffer.substring(0, from)
            sendPaste(replacement + buffer.substring(to))
        } else {
            history = buffer.substring(0, from) + replacement + buffer.substring(to)
            endOperation()
        }
        return true
    }

    /**
     * Accepts a selection that ends at the cursor, without composing text, so that invariant 1
     * holds. The next [commitText] or [setComposingText] replaces the selected text; any other edit
     * collapses the selection first. Gboard selects a trailing space this way to replace it with ".
     * " (docs/decisions/0008).
     */
    public fun setSelection(start: Int, end: Int): Boolean {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (to != cursor || from < 0) return false
        if (from == to) {
            selectionFrom = -1
            return true
        }
        if (composing.isNotEmpty() || splitsSurrogatePair(history, from)) return false
        selectionFrom = from
        return true
    }

    private fun removeSelectedText() {
        if (selectionFrom < 0) return
        history = history.substring(0, selectionFrom)
        selectionFrom = -1
    }

    /** Starts a batch edit: nothing is sent to the host until the outermost batch ends. */
    public fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    /**
     * Returns whether a batch edit is still in progress, as `InputConnection.endBatchEdit` does.
     */
    public fun endBatchEdit(): Boolean {
        if (batchDepth == 0) return false
        batchDepth--
        if (batchDepth == 0) flush()
        return batchDepth > 0
    }

    /** Finishes the composing text, which would otherwise be lost (section 6.4). */
    public fun closeConnection() {
        selectionFrom = -1
        if (composing.isNotEmpty()) finishComposingText()
        batchDepth = 0
        flush()
    }

    // --- Queries (section 6.2) ---

    /**
     * Returns up to [length] code units before the selection; nothing if [length] is not positive.
     */
    public fun textBeforeCursor(length: Int): String {
        // InputConnection's callers check the length, but the IME is not trusted.
        if (length <= 0) return ""
        val buffer = text.substring(0, selectionStart)
        var start = maxOf(0, buffer.length - length)
        if (splitsSurrogatePair(buffer, start)) start++
        return buffer.substring(start)
    }

    // --- Keys (section 6.3) ---

    /**
     * Handles a key pressed down, from the IME or a hardware keyboard. Key releases must not be
     * passed: they have no effect.
     *
     * A character without Ctrl, Alt, or Meta is committed like [commitText]. Other keys first
     * finish and send the composing text, so that the host receives them in the order typed.
     */
    public fun onKeyDown(stroke: KeyStroke) {
        selectionFrom = -1
        changedSinceRestart = true
        if (
            stroke is KeyStroke.Character &&
                !stroke.modifiers.ctrl &&
                !stroke.modifiers.alt &&
                !stroke.modifiers.meta
        ) {
            history +=
                composing + withoutDroppedControls(String(Character.toChars(stroke.codePoint)))
            composing = ""
            endOperation()
            return
        }
        history += composing
        composing = ""
        flush()
        listener.send(HostInput.Key(stroke))
        if (stroke == KeyStroke.Special(TerminalKey.Backspace)) {
            // The DEL deletes the last character of the history at the host too.
            if (history.isNotEmpty()) {
                history = history.substring(0, history.offsetByCodePoints(history.length, -1))
                sentHistory = history
            }
        } else {
            requestReset()
        }
    }

    /**
     * Pastes [text] (section 6.4). The composing text is finished, and it and the edits not sent
     * yet are sent before the paste, even during a batch edit, so that the host receives them in
     * the order made. Resets afterwards, since the host's line no longer matches the history.
     */
    public fun paste(text: String) {
        selectionFrom = -1
        history += composing
        composing = ""
        sendPaste(text)
    }

    /**
     * Sends the edits not sent yet, even during a batch edit, and then [text] as a paste, and
     * resets (section 6.4).
     */
    private fun sendPaste(text: String) {
        changedSinceRestart = true
        flush()
        listener.send(HostInput.Paste(text))
        requestReset()
    }

    /** Handles `performEditorAction`, which the contract treats as Enter (section 6.4). */
    public fun performEditorAction() {
        onKeyDown(KeyStroke.Special(TerminalKey.Enter))
    }

    /**
     * Commits the composing text and sends it with the edits not sent yet, even during a batch
     * edit, so that a change that applies to later input, such as the app arming sticky modifiers,
     * does not apply to it (section 6.3). Resets if there was composing text, since the IME still
     * has it.
     */
    public fun sendPendingInput() {
        selectionFrom = -1
        val hadComposingText = composing.isNotEmpty()
        history += composing
        composing = ""
        flush()
        if (hadComposingText) requestReset()
    }

    // --- Resets (section 5) ---

    /**
     * Resets the input history, for example because the host moved the cursor to another line
     * (section 5.1). If there is composing text, the reset waits until it is finished, since
     * restarting the input would commit it.
     */
    public fun requestReset() {
        resetPending = true
        if (batchDepth == 0) flush()
    }

    private fun endOperation() {
        changedSinceRestart = true
        if (isHistoryOverLimit()) {
            // Past the limit, the history is sent and reset at once, even during a batch edit and
            // with composing text, which is committed as restarting the input would commit it.
            // Otherwise an IME that does not end its batch, or that moves its composing text into
            // the history bit by bit with setComposingRegion, could make the buffer grow without
            // bound (invariant 5).
            history += composing
            composing = ""
            flush()
        } else if (batchDepth == 0) {
            flush()
        }
    }

    private fun isHistoryOverLimit(): Boolean =
        history.length > maxHistoryLength &&
            history.codePointCount(0, history.length) > maxHistoryLength

    /** Sends the difference between the sent and the current input history (section 4.1). */
    private fun flush() {
        // DELs for text before the history reach it only after all of the sent history is deleted,
        // so then nothing of it can be kept.
        val prefix = if (pendingDeletes > 0) 0 else commonPrefixLength(sentHistory, history)
        val deletes = sentHistory.codePointCount(prefix, sentHistory.length) + pendingDeletes
        pendingDeletes = 0
        if (deletes > 0) listener.send(HostInput.Delete(deletes))
        val added = history.substring(prefix)
        sentHistory = history
        if (sendText(added)) resetPending = true
        if (isHistoryOverLimit()) resetPending = true
        if (resetPending && composing.isEmpty()) {
            resetPending = false
            selectionFrom = -1
            history = ""
            sentHistory = ""
            if (changedSinceRestart) {
                changedSinceRestart = false
                listener.restartInput()
            }
        }
    }

    /**
     * Sends [text], with each line break (CR LF, CR, or LF) as Enter. Returns whether there was a
     * line break.
     */
    private fun sendText(text: String): Boolean {
        var start = 0
        var i = 0
        var hasLineBreak = false
        while (i < text.length) {
            val c = text[i]
            if (c != '\n' && c != '\r') {
                i++
                continue
            }
            if (i > start) listener.send(HostInput.Text(text.substring(start, i)))
            listener.send(HostInput.Key(KeyStroke.Special(TerminalKey.Enter)))
            hasLineBreak = true
            i += if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
            start = i
        }
        if (start < text.length) listener.send(HostInput.Text(text.substring(start)))
        return hasLineBreak
    }

    @InternalVtfxApi
    public companion object {
        /** The default limit of the input history, in code points (ime-contract.md section 10). */
        public const val DEFAULT_MAX_HISTORY_LENGTH: Int = 1024

        /**
         * Removes the controls that the gate drops (ime-contract.md section 4.2), so that the edit
         * buffer holds only what the host receives (invariant 3). Line breaks and HT are kept.
         * Otherwise a later edit would send DEL for characters that never reached the host, and
         * delete the host's text before the input history.
         *
         * A copy of the rule in the gate's `OutboundText`, which must not depend on this module or
         * on any other code outside security review. `DroppedControlsAgreementTest` in vtfx-session
         * pins that the two agree for every UTF-16 code unit; change both together.
         */
        private fun withoutDroppedControls(text: CharSequence): String =
            text
                .filterNot {
                    (it < ' ' && it != '\t' && it != '\n' && it != '\r') || it in '\u007f'..'\u009f'
                }
                .toString()

        /**
         * Whether committed [text] is a paste rather than typing: it has a line break, and is not a
         * line feed alone, which is how an IME commits Enter. An IME commits a clipboard suggestion
         * as text, and its lines must not run one by one (docs/decisions/0050).
         */
        private fun isPaste(text: String): Boolean =
            text != "\n" && text.any { it == '\n' || it == '\r' }

        private fun splitsSurrogatePair(text: String, index: Int): Boolean =
            index in 1 until text.length &&
                Character.isLowSurrogate(text[index]) &&
                Character.isHighSurrogate(text[index - 1])

        /** The length of the common prefix of [a] and [b], not splitting a surrogate pair. */
        private fun commonPrefixLength(a: String, b: String): Int {
            var i = 0
            val n = minOf(a.length, b.length)
            while (i < n && a[i] == b[i]) i++
            if (splitsSurrogatePair(a, i) || splitsSurrogatePair(b, i)) i--
            return i
        }
    }
}
