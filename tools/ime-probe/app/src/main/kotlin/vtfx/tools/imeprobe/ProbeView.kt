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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Selection
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray

/**
 * A minimal terminal-like editor view. It renders the bytes "sent to the host" as if the host
 * echoed them back, followed by the composing text.
 */
class ProbeView(
    context: Context,
    private val config: ProbeConfig,
    private val log: ProbeLog,
) : View(context) {
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val buffer = InputBuffer(config.surrounding, ::sendToHost)

    /** Simulated host echo. Lines are separated by '\n'. */
    private val screen = StringBuilder()

    private var reportedSelection = listOf(-1, -1, -1, -1)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f
    }
    private val composingPaint = Paint(textPaint).apply {
        color = Color.YELLOW
        isUnderlineText = true
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(0x20, 0x20, 0x20))
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = config.inputType
        outAttrs.imeOptions = config.imeOptions
        outAttrs.initialSelStart = Selection.getSelectionStart(buffer.editable)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(buffer.editable)
        log.event("onCreateInputConnection") {
            put("inputType", outAttrs.inputType)
            put("imeOptions", outAttrs.imeOptions)
            put("state", buffer.stateJson())
        }
        reportedSelection = currentSelection()
        return ProbeInputConnection(this, buffer, log, config.rejectSelection, ::onBufferChanged)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            requestFocus()
            imm.showSoftInput(this, 0)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        log.event("key") {
            put("action", event.action)
            put("keyCode", KeyEvent.keyCodeToString(event.keyCode))
            put("meta", event.metaState)
            put("flags", event.flags)
            put("unicode", event.unicodeChar)
            put("source", event.source)
            put("deviceId", event.deviceId)
            put("composing", buffer.isComposing)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            else -> {
                val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
                when {
                    unicode == 0 -> null
                    event.isCtrlPressed && unicode in 0x40..0x7f -> (unicode and 0x1f).toChar().toString()
                    else -> String(Character.toChars(unicode))
                }
            }
        } ?: return super.onKeyDown(keyCode, event)

        val isTextKey = bytes.length == 1 && bytes[0] >= ' ' && bytes[0] != '\u007f'
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            // Backspace is an edit of the input history, not a reason to forget it.
            sendToHost(bytes)
            buffer.applyBackspace()
            onBufferChanged()
            return true
        }
        if (!isTextKey && buffer.reset()) {
            log.event("restartInput") { put("reason", "non-text key") }
            imm.restartInput(this)
        }
        sendToHost(bytes)
        return true
    }

    private fun sendToHost(bytes: String) {
        log.event("send") {
            put("bytes", bytes)
            put("codePoints", JSONArray(bytes.codePoints().toArray().toList()))
        }
        var i = 0
        while (i < bytes.length) {
            val cp = bytes.codePointAt(i)
            when (cp) {
                0x7f -> if (screen.isNotEmpty() && screen.last() != '\n') {
                    val prev = screen.offsetByCodePoints(screen.length, -1)
                    screen.setLength(prev)
                }
                '\r'.code -> screen.append('\n')
                else -> if (cp >= 0x20) screen.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        invalidate()
    }

    private fun currentSelection(): List<Int> = listOf(
        Selection.getSelectionStart(buffer.editable),
        Selection.getSelectionEnd(buffer.editable),
        BaseInputConnection.getComposingSpanStart(buffer.editable),
        BaseInputConnection.getComposingSpanEnd(buffer.editable),
    )

    private fun onBufferChanged() {
        if (buffer.takeRestartRequest()) {
            log.event("restartInput") { put("reason", "committed newline") }
            reportedSelection = currentSelection()
            imm.restartInput(this)
            invalidate()
            return
        }
        val selection = currentSelection()
        if (selection != reportedSelection) {
            reportedSelection = selection
            log.event("updateSelection") { put("sel", JSONArray(selection)) }
            imm.updateSelection(this, selection[0], selection[1], selection[2], selection[3])
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val lineHeight = textPaint.fontSpacing
        val lines = screen.toString().split('\n').takeLast(((height / lineHeight).toInt() - 1).coerceAtLeast(1))
        var y = lineHeight
        for (line in lines) {
            canvas.drawText(line, 16f, y, textPaint)
            y += lineHeight
        }
        val last = lines.lastOrNull() ?: ""
        val x = 16f + textPaint.measureText(last)
        canvas.drawText(buffer.composingText, x, y - lineHeight, composingPaint)
    }
}
