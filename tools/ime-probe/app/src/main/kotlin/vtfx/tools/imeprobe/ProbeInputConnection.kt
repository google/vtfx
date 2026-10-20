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

import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import org.json.JSONObject

/**
 * An [android.view.inputmethod.InputConnection] that logs every call from the IME with its
 * arguments, return value, and the resulting buffer state.
 *
 * The editing itself is delegated to [BaseInputConnection] on [InputBuffer.editable].
 */
class ProbeInputConnection(
    view: View,
    private val buffer: InputBuffer,
    private val log: ProbeLog,
    /** Whether to reject selection changes other than a collapsed cursor at the end. */
    private val rejectSelection: Boolean,
    private val onStateChanged: () -> Unit,
) : BaseInputConnection(view, true) {
    private var batchDepth = 0

    /** Nesting depth of logged calls, since some calls delegate to others. */
    private var callDepth = 0

    override fun getEditable(): Editable = buffer.editable

    private fun <T> call(name: String, args: JSONObject.() -> Unit, block: () -> T): T {
        log.event("ic") {
            put("call", name)
            put("depth", callDepth)
            put("args", JSONObject().apply(args))
        }
        callDepth++
        val result = try {
            block()
        } finally {
            callDepth--
        }
        if (callDepth == 0 && batchDepth == 0) {
            buffer.flush()
        }
        log.event("ret") {
            put("call", name)
            put("depth", callDepth)
            put("ret", describe(result))
            put("state", buffer.stateJson())
        }
        if (callDepth == 0) {
            onStateChanged()
        }
        return result
    }

    private fun describe(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Int -> value
        is ExtractedText -> JSONObject()
            .put("text", value.text?.toString())
            .put("startOffset", value.startOffset)
            .put("selStart", value.selectionStart)
            .put("selEnd", value.selectionEnd)
        is Unit -> JSONObject.NULL
        else -> if (Build.VERSION.SDK_INT >= 31) Api31.describe(value) else value.toString()
    }

    // SurroundingText is API 31. Checking for it only from API 31 keeps describe() working on API 29
    // and 30, where the class does not exist.
    @TargetApi(31)
    private object Api31 {
        fun describe(value: Any): Any = when (value) {
            is SurroundingText -> JSONObject()
                .put("text", value.text.toString())
                .put("selStart", value.selectionStart)
                .put("selEnd", value.selectionEnd)
                .put("offset", value.offset)
            else -> value.toString()
        }
    }

    override fun beginBatchEdit(): Boolean = call("beginBatchEdit", {}) {
        batchDepth++
        true
    }

    override fun endBatchEdit(): Boolean = call("endBatchEdit", {}) {
        if (batchDepth > 0) {
            batchDepth--
        }
        batchDepth > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        call("commitText", { put("text", text?.toString()); put("newCursorPosition", newCursorPosition) }) {
            super.commitText(text, newCursorPosition)
        }

    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        call("commitText+attr", {
            put("text", text.toString())
            put("newCursorPosition", newCursorPosition)
            put("attr", textAttribute?.toString())
        }) {
            super.commitText(text, newCursorPosition, textAttribute)
        }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        call("setComposingText", { put("text", text?.toString()); put("newCursorPosition", newCursorPosition) }) {
            super.setComposingText(text, newCursorPosition)
        }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        call("setComposingText+attr", {
            put("text", text.toString())
            put("newCursorPosition", newCursorPosition)
            put("attr", textAttribute?.toString())
        }) {
            super.setComposingText(text, newCursorPosition, textAttribute)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        call("setComposingRegion", { put("start", start); put("end", end) }) {
            super.setComposingRegion(start, end)
        }

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
        call("setComposingRegion+attr", { put("start", start); put("end", end) }) {
            super.setComposingRegion(start, end, textAttribute)
        }

    override fun finishComposingText(): Boolean = call("finishComposingText", {}) {
        super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        call("deleteSurroundingText", { put("before", beforeLength); put("after", afterLength) }) {
            super.deleteSurroundingText(beforeLength, afterLength)
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        call("deleteSurroundingTextInCodePoints", { put("before", beforeLength); put("after", afterLength) }) {
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
        call("getTextBeforeCursor", { put("length", length); put("flags", flags) }) {
            super.getTextBeforeCursor(length, flags)
        }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
        call("getTextAfterCursor", { put("length", length); put("flags", flags) }) {
            super.getTextAfterCursor(length, flags)
        }

    override fun getSelectedText(flags: Int): CharSequence? =
        call("getSelectedText", { put("flags", flags) }) {
            super.getSelectedText(flags)
        }

    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        call("getSurroundingText", { put("before", beforeLength); put("after", afterLength); put("flags", flags) }) {
            super.getSurroundingText(beforeLength, afterLength, flags)
        }

    override fun getCursorCapsMode(reqModes: Int): Int =
        call("getCursorCapsMode", { put("reqModes", reqModes) }) {
            super.getCursorCapsMode(reqModes)
        }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        call("getExtractedText", { put("flags", flags) }) {
            val extracted = ExtractedText()
            extracted.text = editable.toString()
            extracted.startOffset = 0
            extracted.selectionStart = android.text.Selection.getSelectionStart(editable)
            extracted.selectionEnd = android.text.Selection.getSelectionEnd(editable)
            extracted
        }

    override fun setSelection(start: Int, end: Int): Boolean =
        call("setSelection", { put("start", start); put("end", end) }) {
            val atEnd = start == end && end == editable.length
            if (rejectSelection && !atEnd) false else super.setSelection(start, end)
        }

    override fun sendKeyEvent(event: KeyEvent): Boolean =
        call("sendKeyEvent", {
            put("action", event.action)
            put("keyCode", KeyEvent.keyCodeToString(event.keyCode))
            put("meta", event.metaState)
            put("flags", event.flags)
        }) {
            super.sendKeyEvent(event)
        }

    override fun performEditorAction(actionCode: Int): Boolean =
        call("performEditorAction", { put("actionCode", actionCode) }) {
            super.performEditorAction(actionCode)
        }

    override fun performContextMenuAction(id: Int): Boolean =
        call("performContextMenuAction", { put("id", id) }) {
            super.performContextMenuAction(id)
        }

    override fun clearMetaKeyStates(states: Int): Boolean =
        call("clearMetaKeyStates", { put("states", states) }) {
            super.clearMetaKeyStates(states)
        }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        call("requestCursorUpdates", { put("mode", cursorUpdateMode) }) {
            super.requestCursorUpdates(cursorUpdateMode)
        }

    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        call("requestCursorUpdates+filter", { put("mode", cursorUpdateMode); put("filter", cursorUpdateFilter) }) {
            super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)
        }

    override fun commitCompletion(text: CompletionInfo?): Boolean =
        call("commitCompletion", { put("text", text?.text?.toString()) }) {
            super.commitCompletion(text)
        }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
        call("commitCorrection", { put("info", correctionInfo?.toString()) }) {
            super.commitCorrection(correctionInfo)
        }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        call("performPrivateCommand", { put("action", action) }) {
            super.performPrivateCommand(action, data)
        }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = call("replaceText", {
        put("start", start)
        put("end", end)
        put("text", text.toString())
        put("newCursorPosition", newCursorPosition)
    }) {
        super.replaceText(start, end, text, newCursorPosition, textAttribute)
    }

    override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean =
        call("setImeConsumesInput", { put("value", imeConsumesInput) }) {
            super.setImeConsumesInput(imeConsumesInput)
        }

    override fun closeConnection() {
        call("closeConnection", {}) {
            super.closeConnection()
        }
    }
}
