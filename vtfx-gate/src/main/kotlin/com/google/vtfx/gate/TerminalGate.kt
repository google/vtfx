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

package com.google.vtfx.gate

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalEvent
import java.util.Base64

/**
 * Decides on the requests with external effects that the host or the user makes, and performs the
 * allowed ones (docs/design/architecture.md section 3.3).
 *
 * [clipboard] and [urlOpener] may be null, in which case the corresponding requests are refused.
 *
 * Holds no state of its own, so it is thread-safe if its dependencies are.
 *
 * @suppress
 */
@InternalVtfxApi
public class TerminalGate(
    private val writer: HostWriter,
    private val policy: GatePolicy = GatePolicy(),
    private val clipboard: ClipboardAccess? = null,
    private val urlOpener: UrlOpener? = null,
    /** The display's colors, for color queries. Without them, color queries are not answered. */
    private val colors: ReportedColors? = null,
) {
    /**
     * Handles an event from the terminal model: replies to queries and decides on clipboard
     * requests. Events without external effects (title, bell) are ignored.
     */
    public fun handle(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.ReplyRequested -> writer.sendReply(event.query, colors)
            is TerminalEvent.ClipboardWriteRequested -> writeClipboard(event.selection, event.text)
            is TerminalEvent.ClipboardReadRequested -> readClipboard(event.selection)
            is TerminalEvent.TitleChanged,
            TerminalEvent.Bell -> {}
        }
    }

    private fun writeClipboard(selection: String, text: String) {
        val clipboard = clipboard ?: return
        if (!policy.allowClipboardWrite || !isValidSelection(selection)) return
        if (text.length > policy.maxClipboardWriteLength) return
        clipboard.write(text)
    }

    /** Replies with OSC 52. The clipboard text is base64-encoded, so it cannot form a sequence. */
    private fun readClipboard(selection: String) {
        val clipboard = clipboard ?: return
        if (!policy.allowClipboardRead || !isValidSelection(selection)) return
        val text = clipboard.read() ?: ""
        val data = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        writer.write("\u001b]52;$selection;$data\u001b\\".toByteArray(Charsets.US_ASCII))
    }

    // The user's copy and paste go through the gate, like opening URLs, so that only vtfx-gate
    // calls the clipboard (docs/decisions/0042).
    /**
     * Copies [text] to the clipboard. Must be called only for an explicit action of the user, such
     * as copying a selection: the OSC 52 policy does not apply to it. Returns whether there is a
     * clipboard to copy it to.
     */
    public fun copyToClipboard(text: String): Boolean {
        val clipboard = clipboard ?: return false
        clipboard.write(text)
        return true
    }

    /**
     * Returns the text on the clipboard, for the user's paste, or null if there is no clipboard or
     * it has no text. Must be called only for an explicit action of the user: the OSC 52 policy
     * does not apply to it. Send the text with [HostWriter.sendPaste], which sanitizes it.
     */
    public fun readClipboardForPaste(): String? = clipboard?.read()

    /**
     * Opens a hyperlink. Must be called only for an explicit action of the user, after the UI
     * showed them [url] (requirement 6.1: spoofed links). Returns whether it was opened: false if
     * the scheme is not allowed, the URL is malformed, it has characters or userinfo that could
     * make the URL shown differ from where it goes (docs/decisions/0006-gate-defaults.md), or there
     * is no [UrlOpener].
     */
    public fun openUrl(url: String): Boolean {
        val opener = urlOpener ?: return false
        if (!isAllowedUrl(url)) return false
        opener.open(url)
        return true
    }

    /** Returns whether [openUrl] would open [url]: there is a [UrlOpener], and it is allowed. */
    public fun canOpenUrl(url: String): Boolean = urlOpener != null && isAllowedUrl(url)

    /** Returns whether [url] would be opened by [openUrl]. */
    public fun isAllowedUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH) return false
        // Whitespace, controls, format characters (such as bidi overrides and zero-width
        // characters), and other default-ignorable characters could hide or reorder part of the
        // URL shown to the user.
        if (url.any { isHidingCharacter(it) || isDefaultIgnorable(it) }) return false
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon)
        if (
            !scheme.first().isAsciiLetter() ||
                !scheme.all { it.isAsciiLetter() || it.isAsciiDigit() || it in "+-." }
        ) {
            return false
        }
        val lowercaseScheme = scheme.lowercase()
        if (lowercaseScheme !in policy.urlSchemes) return false
        return lowercaseScheme !in HTTP_SCHEMES || !hasUserinfoOrBackslash(url, colon)
    }

    private fun isHidingCharacter(c: Char): Boolean =
        c <= ' ' ||
            c in '\u007f'..'\u009f' ||
            Character.isWhitespace(c) ||
            Character.isSpaceChar(c) ||
            Character.isSurrogate(c) ||
            Character.getType(c) == Character.FORMAT.toInt()

    /**
     * Returns whether [c] has the Default_Ignorable_Code_Point property, from the table generated
     * from the copy of the UCD in third_party/unicode ([UnicodeDefaultIgnorables]). These draw as
     * nothing (U+3164 HANGUL FILLER) or change the character before them (variation selectors). The
     * table includes the format characters [isHidingCharacter] refuses, and does not depend on the
     * Unicode version of the runtime. Code points outside the BMP are refused as surrogates.
     */
    private fun isDefaultIgnorable(c: Char): Boolean {
        val ranges = UnicodeDefaultIgnorables.RANGES
        for (i in ranges.indices step 2) {
            if (c.code < ranges[i]) return false
            if (c.code <= ranges[i + 1]) return true
        }
        return false
    }

    /**
     * Returns whether the authority of the http or https [url] whose scheme ends at [colon] has
     * userinfo or a backslash. Userinfo lets the shown URL begin with another host
     * (`https://good.example@evil.example`), and a long one can push the real host out of view.
     * Browsers treat `\` in the authority as `/` but other parsers do not, so they disagree on the
     * host of `https://evil.example\@good.example`. As browsers do (the WHATWG URL Standard), the
     * authority starts after any `/` and `\` that follow the scheme, with or without `//`, and ends
     * at the first `/`, `?`, or `#`. Refusing `\` instead of ending the authority there also
     * refuses an `@` after it, whichever way it is parsed.
     */
    private fun hasUserinfoOrBackslash(url: String, colon: Int): Boolean {
        var i = colon + 1
        while (i < url.length && (url[i] == '/' || url[i] == '\\')) i++
        while (i < url.length && url[i] !in "/?#") {
            if (url[i] == '@' || url[i] == '\\') return true
            i++
        }
        return false
    }

    private fun isValidSelection(selection: String): Boolean =
        selection.isNotEmpty() &&
            selection.length <= MAX_SELECTION_LENGTH &&
            selection.all { it in TerminalEvent.CLIPBOARD_SELECTION_CHARACTERS }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private companion object {
        const val MAX_URL_LENGTH = 8192
        const val MAX_SELECTION_LENGTH = 16

        /** The schemes in which [isAllowedUrl] refuses userinfo. */
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
