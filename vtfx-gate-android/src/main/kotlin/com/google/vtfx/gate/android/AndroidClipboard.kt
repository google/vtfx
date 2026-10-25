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

package com.google.vtfx.gate.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.vtfx.gate.ClipboardAccess

/**
 * The system clipboard. vtfx-gate decides whether a request is allowed before calling this; this
 * class only performs it. Thread-safe.
 *
 * On a device or in a context without the clipboard service, where `Context.getSystemService`
 * returns null, writes do nothing and reads return null, since [ClipboardAccess] must not throw.
 *
 * @param context A context to get the clipboard service from.
 */
public class AndroidClipboard(context: Context) : ClipboardAccess {
    // Typed explicitly: getSystemService is nullable, and a platform type would let a call on a
    // missing service throw NullPointerException.
    private val clipboard: ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    /**
     * Replaces the clipboard with [text] as plain text, which the system's clipboard preview shows.
     * Does nothing without the clipboard service.
     */
    override fun write(text: String) {
        val clipboard = clipboard ?: return
        // Not marked sensitive: the clipboard preview shows the text, so that the user sees what
        // the host put there with OSC 52 (docs/decisions/0083).
        clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    /**
     * Returns the clipboard text. Android lets only the app in the foreground with focus read the
     * clipboard, so this returns null otherwise, as it does without the clipboard service.
     */
    override fun read(): String? {
        val clipboard = clipboard ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private companion object {
        const val LABEL = "Terminal"
    }
}
