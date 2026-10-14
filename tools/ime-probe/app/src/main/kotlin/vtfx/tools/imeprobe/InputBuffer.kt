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

package vtfx.tools.imeprobe

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.inputmethod.BaseInputConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The editor buffer the IME operates on, and the translation of its committed text into bytes
 * for the host.
 *
 * Committed text is everything outside the composing region. After each top-level IME operation,
 * [flush] diffs the committed text against what has already been sent, and sends DELs for removed
 * code points followed by the added text.
 */
class InputBuffer(
    private val policy: SurroundingPolicy,
    private val sendToHost: (String) -> Unit,
) {
    val editable: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }

    /** Committed text that has already been sent to the host and is still in [editable]. */
    private var sent = ""

    /** Whether a newline was committed, so that the owner should restart the IME input. */
    private var restartRequested = false

    fun flush() {
        val text = editable.toString()
        val compStart = BaseInputConnection.getComposingSpanStart(editable)
        val compEnd = BaseInputConnection.getComposingSpanEnd(editable)
        val committed = if (compStart >= 0) {
            text.substring(0, compStart) + text.substring(compEnd)
        } else {
            text
        }

        var prefix = sent.commonPrefixWith(committed).length
        // Do not split a surrogate pair.
        if (prefix > 0 && Character.isHighSurrogate(sent[prefix - 1])) {
            prefix--
        }
        val deletedCodePoints = sent.codePointCount(prefix, sent.length)
        val added = committed.substring(prefix)
        if (deletedCodePoints > 0 || added.isNotEmpty()) {
            sendToHost("\u007f".repeat(deletedCodePoints) + added)
        }
        sent = committed

        if (policy == SurroundingPolicy.HISTORY && compStart < 0 && committed.contains('\n')) {
            // A committed newline is Enter. Like a key event Enter, it ends the input history.
            restartRequested = reset() || restartRequested
            return
        }

        if (policy == SurroundingPolicy.NONE && committed.isNotEmpty()) {
            // Drop committed text so that only composing text remains visible to the IME.
            if (compStart >= 0) {
                editable.delete(compEnd, editable.length)
                editable.delete(0, compStart)
            } else {
                editable.clear()
                Selection.setSelection(editable, 0)
            }
            sent = ""
        }
    }

    /** Returns whether the owner should call `InputMethodManager.restartInput`, and clears it. */
    fun takeRestartRequest(): Boolean {
        val requested = restartRequested
        restartRequested = false
        return requested
    }

    /**
     * Applies a backspace key that has already been sent to the host as DEL, by removing the last
     * code point of the input history. Does nothing while composing, since the IME handles it.
     */
    fun applyBackspace() {
        if (policy != SurroundingPolicy.HISTORY || isComposing || sent.isEmpty()) {
            return
        }
        val newLength = sent.offsetByCodePoints(sent.length, -1)
        editable.delete(newLength, editable.length)
        sent = sent.substring(0, newLength)
    }

    /** Forgets committed text, e.g. after Enter. Returns whether the buffer content changed. */
    fun reset(): Boolean {
        val compStart = BaseInputConnection.getComposingSpanStart(editable)
        if (compStart >= 0 || editable.isEmpty()) {
            return false
        }
        editable.clear()
        Selection.setSelection(editable, 0)
        sent = ""
        return true
    }

    val isComposing: Boolean
        get() = BaseInputConnection.getComposingSpanStart(editable) >= 0

    val composingText: String
        get() {
            val start = BaseInputConnection.getComposingSpanStart(editable)
            val end = BaseInputConnection.getComposingSpanEnd(editable)
            return if (start >= 0) editable.substring(start, end) else ""
        }

    fun stateJson(): JSONObject = JSONObject()
        .put("text", editable.toString())
        .put("sel", JSONArray(listOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))))
        .put(
            "comp",
            JSONArray(
                listOf(
                    BaseInputConnection.getComposingSpanStart(editable),
                    BaseInputConnection.getComposingSpanEnd(editable),
                )
            )
        )
}
