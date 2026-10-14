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

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import org.json.JSONObject

/**
 * A stock [EditText] whose input connection logs the main IME calls. Used as a baseline to tell
 * whether an IME behavior comes from the IME itself or from the probe's custom editor.
 */
class ProbeEditText(
    context: Context,
    private val config: ProbeConfig,
    private val log: ProbeLog,
) : EditText(context) {
    init {
        inputType = config.inputType
        imeOptions = config.imeOptions
        textSize = 20f
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        log.event("onCreateInputConnection") {
            put("inputType", outAttrs.inputType)
            put("imeOptions", outAttrs.imeOptions)
        }
        return LoggingWrapper(target)
    }

    private fun state(): JSONObject = JSONObject()
        .put("text", text.toString())
        .put("sel", "${selectionStart},${selectionEnd}")

    private inner class LoggingWrapper(target: InputConnection) : InputConnectionWrapper(target, false) {
        private fun <T> call(name: String, args: JSONObject, block: () -> T): T {
            log.event("ic") {
                put("call", name)
                put("depth", 0)
                put("args", args)
            }
            val result = block()
            log.event("ret") {
                put("call", name)
                put("depth", 0)
                put("ret", result?.toString() ?: JSONObject.NULL)
                put("state", state())
            }
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            call("commitText", JSONObject().put("text", text?.toString())) { super.commitText(text, newCursorPosition) }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            call("setComposingText", JSONObject().put("text", text?.toString())) {
                super.setComposingText(text, newCursorPosition)
            }

        override fun setComposingRegion(start: Int, end: Int) =
            call("setComposingRegion", JSONObject().put("start", start).put("end", end)) {
                super.setComposingRegion(start, end)
            }

        // InputConnectionWrapper forwards these overloads straight to the target, bypassing the
        // overrides above, so they need to be logged separately.
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            call("commitText+attr", JSONObject().put("text", text.toString())) {
                super.commitText(text, newCursorPosition, textAttribute)
            }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            call("setComposingText+attr", JSONObject().put("text", text.toString())) {
                super.setComposingText(text, newCursorPosition, textAttribute)
            }

        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) =
            call("setComposingRegion+attr", JSONObject().put("start", start).put("end", end)) {
                super.setComposingRegion(start, end, textAttribute)
            }

        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?,
        ) = call("replaceText", JSONObject().put("start", start).put("end", end).put("text", text.toString())) {
            super.replaceText(start, end, text, newCursorPosition, textAttribute)
        }

        override fun finishComposingText() = call("finishComposingText", JSONObject()) { super.finishComposingText() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            call("deleteSurroundingText", JSONObject().put("before", beforeLength).put("after", afterLength)) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun sendKeyEvent(event: KeyEvent) =
            call("sendKeyEvent", JSONObject().put("action", event.action).put("keyCode", KeyEvent.keyCodeToString(event.keyCode))) {
                super.sendKeyEvent(event)
            }

        override fun getTextBeforeCursor(n: Int, flags: Int) =
            call("getTextBeforeCursor", JSONObject().put("length", n)) { super.getTextBeforeCursor(n, flags) }
    }
}
