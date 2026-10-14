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

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.input.ImeEditor
import com.google.vtfx.session.TerminalSession

/**
 * The `InputConnection` of a terminal: a thin adapter that delegates to the [ImeEditor] of a
 * [TerminalSession], through [TerminalSession.edit] (docs/design/ime-contract.md section 6). It
 * does not use the `Editable` of [BaseInputConnection]; every method that would touch it is
 * overridden.
 *
 * Called on the main thread.
 */
internal class TerminalInputConnection(
    private val view: View,
    private val session: TerminalSession,
    private val keys: KeyEventHandler,
    private val cursorAnchor: CursorAnchorReporter,
    private val onPaste: () -> Unit,
    /**
     * Called after a call of the IME changed the edit buffer, outside batch edits. Input sent to
     * the host is told by the session instead, through `SessionObserver.onUserInput`.
     */
    private val onEditorChanged: () -> Unit,
) : BaseInputConnection(view, false) {
    private val inputMethodManager = view.context.getSystemService(InputMethodManager::class.java)

    /** The selection and composing region last reported to the IME. */
    private var reported = Selection(-1, -1, -1, -1)

    /**
     * The edit buffer as of the last report, so that calls without an effect, such as the key
     * events of a modifier key alone, do not count as edits.
     */
    private var lastState = session.edit { EditorState(it) }

    private data class Selection(
        val start: Int,
        val end: Int,
        val composingStart: Int,
        val composingEnd: Int,
    )

    private data class EditorState(val text: String, val selection: Selection) {
        constructor(
            editor: ImeEditor
        ) : this(
            editor.text,
            Selection(
                editor.selectionStart,
                editor.cursor,
                editor.composingStart,
                editor.composingEnd,
            ),
        )
    }

    // --- Edits (section 6.1) ---

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = edit {
        it.setComposingText(text)
    }

    override fun setComposingText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = setComposingText(text, newCursorPosition)

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = edit {
        it.commitText(text)
    }

    override fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = commitText(text, newCursorPosition)

    override fun finishComposingText(): Boolean = edit { it.finishComposingText() }

    override fun setComposingRegion(start: Int, end: Int): Boolean = edit {
        it.setComposingRegion(start, end)
    }

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
        setComposingRegion(start, end)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = edit {
        it.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        edit {
            it.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = edit { it.replaceText(start, end, text) }

    override fun setSelection(start: Int, end: Int): Boolean = edit { it.setSelection(start, end) }

    override fun commitCompletion(text: CompletionInfo): Boolean = commitText(text.text ?: "", 1)

    /** The correction already arrived through the edits before it. */
    override fun commitCorrection(correctionInfo: CorrectionInfo): Boolean = true

    override fun beginBatchEdit(): Boolean = session.edit { it.beginBatchEdit() }

    override fun endBatchEdit(): Boolean {
        val inBatch = session.edit { it.endBatchEdit() }
        if (!inBatch) reportSelection()
        return inBatch
    }

    // --- Queries (section 6.2) ---

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = session.edit {
        it.textBeforeCursor(n)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    override fun getSelectedText(flags: Int): CharSequence? = session.edit { it.selectedText }

    override fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText = session.edit { editor ->
        val before = editor.textBeforeCursor(beforeLength)
        val selected = editor.selectedText.orEmpty()
        SurroundingText(
            before + selected,
            before.length,
            before.length + selected.length,
            editor.selectionStart - before.length,
        )
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
        session.edit { editor ->
            ExtractedText().apply {
                text = editor.text
                startOffset = 0
                selectionStart = editor.selectionStart
                selectionEnd = editor.cursor
            }
        }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    // --- Keys (section 6.3) ---

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        keys.handle(event)
        reportSelection()
        return true
    }

    // --- Others (section 6.4) ---

    override fun performEditorAction(editorAction: Int): Boolean {
        session.edit { it.performEditorAction() }
        reportSelection()
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (id != android.R.id.paste) return false
        onPaste()
        reportSelection()
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        cursorAnchor.request(cursorUpdateMode)
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)

    override fun clearMetaKeyStates(states: Int): Boolean = true

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?,
    ): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean = true

    override fun closeConnection() {
        session.edit { it.closeConnection() }
        reportSelection()
        cursorAnchor.request(0)
        // Releases the state of BaseInputConnection. Its Editable is unused, so nothing is sent.
        super.closeConnection()
    }

    /** Runs [operation], and reports the selection unless a batch edit is in progress. */
    private fun edit(operation: (ImeEditor) -> Boolean): Boolean {
        val (result, inBatch) = session.edit { operation(it) to it.isBatchEditInProgress }
        if (!inBatch) reportSelection()
        return result
    }

    /**
     * Tells the IME about a change of the selection or the composing region, which
     * `BaseInputConnection` does not do by itself (section 7), and the display about a change of
     * the edit buffer.
     */
    private fun reportSelection() {
        val state = session.edit { EditorState(it) }
        val current = state.selection
        if (current != reported) {
            reported = current
            inputMethodManager.updateSelection(
                view,
                current.start,
                current.end,
                current.composingStart,
                current.composingEnd,
            )
        }
        if (state != lastState) {
            lastState = state
            onEditorChanged()
        }
    }

    companion object {
        /**
         * Fills [outAttrs] as docs/design/ime-contract.md section 3 specifies. With [directInput],
         * the IME is asked for plain characters without suggestions, which it then commits one by
         * one.
         */
        fun configure(outAttrs: EditorInfo, editor: ImeEditor, directInput: Boolean = false) {
            outAttrs.inputType =
                if (directInput) {
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            outAttrs.imeOptions =
                EditorInfo.IME_ACTION_NONE or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            outAttrs.initialSelStart = editor.cursor
            outAttrs.initialSelEnd = editor.cursor
            outAttrs.setInitialSurroundingText(editor.text)
        }
    }
}
