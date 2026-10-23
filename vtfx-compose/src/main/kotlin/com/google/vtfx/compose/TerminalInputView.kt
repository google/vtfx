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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.session.TerminalSession

/**
 * The view that the IME connects to. It draws nothing; the [Terminal] composable lays it out behind
 * the grid with the same bounds, so that its coordinates are the grid's.
 *
 * A view is used instead of Compose's `PlatformTextInputModifierNode`, because Compose wraps the
 * `InputConnection` in a wrapper that does not forward `replaceText`. Its default implementation
 * selects the range and commits, which cannot reject a range outside the edit buffer as
 * docs/design/ime-contract.md section 6.1 requires (docs/decisions/0008).
 */
@SuppressLint("ViewConstructor")
internal class TerminalInputView(context: Context) : View(context), InputHost {
    private val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
    private var display: TerminalDisplay? = null
    private val session: TerminalSession?
        get() = display?.session

    private var reporter: CursorAnchorReporter? = null

    /** Whether the window has the focus, as last dispatched to this view. */
    private var windowFocused = false

    /** The session last told that it has the focus, so that each change is told once. */
    private var focusedSession: TerminalSession? = null

    /** The grid in this view's coordinates, set after layout. */
    var metrics: CellMetrics? = null

    /** Called when this view gains or loses focus. */
    var onFocusChange: (Boolean) -> Unit = {}

    /**
     * Called with each key event dispatched to this view before the IME sees it; returns whether
     * the app consumed it (docs/decisions/0032).
     */
    var onPreImeKeyEvent: (KeyEvent) -> Boolean = { false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The rows over this view expose the text to accessibility services (docs/decisions/0015).
        // This view only connects the IME; exposed, it would cover the rows and take a screen
        // reader's touches.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(display: TerminalDisplay?) {
        if (display === this.display) return
        this.display?.let { if (it.inputHost === this) it.inputHost = null }
        this.display = display
        if (display != null && hasFocus()) display.inputHost = this
        updateSessionFocus()
        inputMethodManager.restartInput(this)
    }

    /**
     * Tells the sessions about a change of the one that gets the input: the bound session, while
     * this view and its window have the focus. The other session, when the view is bound to
     * another, loses it (docs/design/ime-contract.md section 5.1, mode 1004).
     */
    private fun updateSessionFocus() {
        val focused = session?.takeIf { isFocused && windowFocused }
        val previous = focusedSession
        if (focused === previous) return
        focusedSession = focused
        previous?.onFocusChanged(false)
        focused?.onFocusChanged(true)
    }

    /** Focuses this view and shows the software keyboard. */
    fun showKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = session != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val display = display ?: return null
        val session = display.session
        val directInput = session.directInput
        session.edit { TerminalInputConnection.configure(outAttrs, it, directInput) }
        val anchor = CursorAnchorReporter(this, session) { metrics }
        reporter = anchor
        display.inputHost = this
        return TerminalInputConnection(
            view = this,
            session = session,
            keys = KeyEventHandler(session),
            cursorAnchor = anchor,
            onPaste = { session.pasteFromClipboard() },
            onEditorChanged = {
                anchor.onEditorChanged()
                display.notifyEditorChanged()
            },
        )
    }

    /**
     * Hardware keys before the IME: the window dispatches them here first, while this view has the
     * focus, then to the IME, then to [onKeyDown] and [onKeyUp]. A key consumed here reaches
     * neither the IME nor the session.
     */
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        onPreImeKeyEvent(event) || super.dispatchKeyEventPreIme(event)

    /** Hardware keys that the IME did not consume (docs/design/ime-contract.md section 6.3). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyUp(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val display = display ?: return false
        val handled = KeyEventHandler(display.session).handle(event)
        if (handled) display.notifyEditorChanged()
        return handled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) display?.inputHost = this
        updateSessionFocus()
        onFocusChange(gainFocus)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        windowFocused = hasWindowFocus
        updateSessionFocus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowFocused = hasWindowFocus()
        updateSessionFocus()
    }

    override fun onDetachedFromWindow() {
        bind(null)
        super.onDetachedFromWindow()
    }

    // --- InputHost ---

    override fun restartInput() {
        inputMethodManager.restartInput(this)
    }

    override fun onScreenChanged() {
        reporter?.onEditorChanged()
    }
}
