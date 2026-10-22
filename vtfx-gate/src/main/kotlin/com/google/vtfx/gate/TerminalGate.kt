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
     * Handles the [events] that the terminal model emitted for one write of host output, in order:
     * replies to queries and decides on clipboard requests. Events without external effects (title,
     * bell) are ignored. Of the clipboard writes, only the last that is allowed is performed, so
     * that the host cannot make the system clipboard change, and show its preview, many times at
     * once (docs/decisions/0059).
     */
    public fun handle(events: List<TerminalEvent>) {
        val lastWrite = events.indexOfLast {
            it is TerminalEvent.ClipboardWriteRequested && mayWriteClipboard(it.selection, it.text)
        }
        var clipboardRead = false
        for ((index, event) in events.withIndex()) {
            when (event) {
                is TerminalEvent.ReplyRequested -> writer.sendReply(event.query, colors)
                is TerminalEvent.ClipboardWriteRequested ->
                    if (index == lastWrite) clipboard?.write(event.text)
                is TerminalEvent.ClipboardReadRequested ->
                    if (readClipboard(event.selection, fromClipboard = !clipboardRead)) {
                        clipboardRead = true
                    }
                is TerminalEvent.TitleChanged,
                TerminalEvent.Bell -> {}
            }
        }
    }

    // Both clipboard requests parse the selection with the gate's own list of targets, rather than
    // trust the model's check, since vtfx-core is not under security review.
    private fun mayWriteClipboard(selection: String, text: String): Boolean =
        clipboard != null &&
            policy.allowClipboardWrite &&
            ClipboardSelection.parse(selection) != null &&
            text.length <= policy.maxClipboardWriteLength

    /**
     * Replies with OSC 52, built by [Replies], and returns whether it replied. The reply carries
     * the clipboard only if [fromClipboard], the first read of a write of host output, and the text
     * is no longer than [GatePolicy.maxClipboardReadLength]; otherwise it is empty, so that what
     * the gate sends stays proportional to the host's output (docs/decisions/0059).
     */
    private fun readClipboard(selection: String, fromClipboard: Boolean): Boolean {
        val clipboard = clipboard ?: return false
        if (!policy.allowClipboardRead) return false
        val parsed = ClipboardSelection.parse(selection) ?: return false
        val text =
            if (fromClipboard) {
                clipboard.read()?.takeIf { it.length <= policy.maxClipboardReadLength }
            } else {
                null
            }
        writer.write(Replies.clipboard(parsed, text ?: ""))
        return true
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
     * showed them [url] (requirement 6.1: spoofed links). Returns what the [UrlOpener] reported, or
     * [OpenUrlResult.NotAllowed] without calling it if the scheme is not allowed, the URL is
     * malformed, it has characters, userinfo, or a host that is not ASCII that could make the URL
     * shown differ from where it goes (docs/decisions/0006-gate-defaults.md), or there is no
     * [UrlOpener].
     */
    public fun openUrl(url: String): OpenUrlResult {
        val opener = urlOpener ?: return OpenUrlResult.NotAllowed
        if (!isAllowedUrl(url)) return OpenUrlResult.NotAllowed
        return opener.open(url)
    }

    /**
     * Returns whether [openUrl] would pass [url] to the [UrlOpener] rather than return
     * [OpenUrlResult.NotAllowed]: there is a [UrlOpener], and the URL is allowed.
     */
    public fun canOpenUrl(url: String): Boolean = urlOpener != null && isAllowedUrl(url)

    /** Returns whether [openUrl] allows [url], whether or not there is a [UrlOpener]. */
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
        return lowercaseScheme !in SPECIAL_SCHEMES || !hasMisleadingAuthority(url, colon)
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
     * Returns whether the authority of the [url] with a special scheme (http, https, ftp, ws, wss)
     * whose scheme ends at [colon] has userinfo, a backslash, a `%`, or a character that is not
     * ASCII. Userinfo lets the shown URL begin with another host
     * (`https://good.example@evil.example`), and a long one can push the real host out of view.
     * Browsers treat `\` in the authority as `/` but other parsers do not, so they disagree on the
     * host of `https://evil.example\@good.example`. Characters that are not ASCII let the host look
     * like another one: letters of other scripts (`https://apple.com` with U+0430 CYRILLIC SMALL
     * LETTER A for the `a`), and characters that look like `.` or `/` but are mapped to `.` or stay
     * in the host (U+FF0E FULLWIDTH FULL STOP, U+2215 DIVISION SLASH). Browsers percent-decode the
     * host, so `%` does the same in ASCII (`https://good.example%2Eevil.example`, or `%D0%B0` for
     * U+0430). A host in punycode (`xn--`) is ASCII and shows where it goes. As browsers do (the
     * WHATWG URL Standard), the authority starts after any `/` and `\` that follow the scheme, with
     * or without `//`, and ends at the first `/`, `?`, or `#`. Refusing `\` instead of ending the
     * authority there also refuses an `@` after it, whichever way it is parsed.
     */
    private fun hasMisleadingAuthority(url: String, colon: Int): Boolean {
        var i = colon + 1
        while (i < url.length && (url[i] == '/' || url[i] == '\\')) i++
        while (i < url.length && url[i] !in "/?#") {
            if (url[i] == '@' || url[i] == '\\' || url[i] == '%' || url[i] > '\u007f') return true
            i++
        }
        return false
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private companion object {
        const val MAX_URL_LENGTH = 8192

        /**
         * The schemes in which [isAllowedUrl] refuses userinfo and characters that are not ASCII in
         * the authority: the special schemes of the WHATWG URL Standard, whose authority browsers
         * parse the same way, except `file`, which [GatePolicy] refuses.
         */
        val SPECIAL_SCHEMES = setOf("http", "https", "ftp", "ws", "wss")
    }
}
