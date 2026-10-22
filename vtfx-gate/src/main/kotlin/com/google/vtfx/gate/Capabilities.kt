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

/**
 * Access to the system clipboard, implemented by vtfx-gate-android. Only vtfx-gate calls it: for
 * OSC 52 after the [GatePolicy] allowed the request, and for the user's copy and paste, to which
 * the policy does not apply.
 *
 * Called on any thread, without a lock of vtfx held: with `TerminalSession`, the main thread for
 * copy and paste, and the thread that feeds the host's output for OSC 52. Implementations must be
 * thread-safe and must not throw.
 */
public interface ClipboardAccess {
    /** Replaces the clipboard with [text]. */
    public fun write(text: String)

    /** Returns the text on the clipboard, or null if there is none or it cannot be read. */
    public fun read(): String?
}

// How "must not throw" applies to the failures of opening a URL is in docs/decisions/0043.
/**
 * Opens URLs in another app, implemented by vtfx-gate-android. vtfx calls it only after the
 * [GatePolicy] allowed the URL.
 *
 * Called on the thread that asked to open the URL, the main thread with `TerminalSession`, without
 * a lock of vtfx held. Implementations must be thread-safe. They return the failures that the
 * platform documents for opening a URL, such as no app to open it, as a result, and must not throw.
 */
public fun interface UrlOpener {
    /**
     * Opens [url], which vtfx has checked against the [GatePolicy], and returns what came of it.
     */
    public fun open(url: String): UrlOpenerResult
}

// Closed: a new kind of result is added only in a major version, so that apps can match it
// exhaustively (docs/decisions/0043).
/**
 * What came of asking vtfx to open a URL: [NotAllowed] if vtfx did not try, or what the [UrlOpener]
 * reported ([UrlOpenerResult]).
 */
public sealed interface OpenUrlResult {
    /**
     * vtfx did not try to open the URL: the [GatePolicy] does not allow it, or there is no
     * [UrlOpener].
     */
    public data object NotAllowed : OpenUrlResult

    /** An app was started to open the URL. */
    public data object Opened : UrlOpenerResult

    /** No app opens the URL. */
    public data object NoApp : UrlOpenerResult

    /**
     * The system refused to start the app that opens the URL, for example because that app requires
     * a permission.
     */
    public data object Refused : UrlOpenerResult
}

/**
 * What a [UrlOpener] did with a URL that vtfx allowed: [OpenUrlResult.Opened],
 * [OpenUrlResult.NoApp], or [OpenUrlResult.Refused].
 */
public sealed interface UrlOpenerResult : OpenUrlResult

// The defaults are chosen in docs/decisions/0006.
/**
 * Which requests from the host with external effects are allowed. The defaults refuse setting and
 * reading the clipboard, and allow opening http and https URLs. Immutable.
 */
public class GatePolicy(
    /**
     * Whether the host may set the clipboard with OSC 52, within [maxClipboardWriteLength]. Off by
     * default: before Android 13 the system shows no preview when the clipboard changes, so the
     * host could replace what the user copied without the user noticing. Apps that want remote
     * copy, as tmux and vim do, turn it on.
     */
    public val allowClipboardWrite: Boolean = false,
    /** The longest text, in UTF-16 code units, that the host may put on the clipboard. */
    public val maxClipboardWriteLength: Int = DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH,
    /**
     * Whether the host may read the clipboard with OSC 52. Off by default: the clipboard may hold
     * secrets copied from other apps.
     */
    public val allowClipboardRead: Boolean = false,
    /**
     * The longest clipboard text, in UTF-16 code units, that an OSC 52 read replies with. A longer
     * text is replied to as empty.
     */
    public val maxClipboardReadLength: Int = DEFAULT_MAX_CLIPBOARD_READ_LENGTH,
    urlSchemes: Set<String> = DEFAULT_URL_SCHEMES,
) {
    /**
     * The URL schemes that may be opened, in lower case; given in any case, since schemes are
     * case-insensitive. They apply to every link that the app opens through vtfx: OSC 8 hyperlinks,
     * URLs found in the text, and URLs that the app's link patterns build. Which text becomes a
     * link is decided by the link patterns, not by this set.
     */
    public val urlSchemes: Set<String> = urlSchemes.mapTo(LinkedHashSet()) { it.lowercase() }

    init {
        require(maxClipboardWriteLength >= 0) { "maxClipboardWriteLength must not be negative" }
        require(maxClipboardReadLength >= 0) { "maxClipboardReadLength must not be negative" }
        require(this.urlSchemes.none { it.isEmpty() }) { "schemes must not be empty" }
    }

    public companion object {
        // Plain vals rather than consts, so that apps read the values of the vtfx they run with,
        // not
        // those they were compiled with (docs/decisions/0037).
        /**
         * The default of [maxClipboardWriteLength][GatePolicy.maxClipboardWriteLength]: 64 Ki
         * UTF-16 code units.
         */
        public val DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH: Int = 64 * 1024

        /**
         * The default of [maxClipboardReadLength][GatePolicy.maxClipboardReadLength]: 64 Ki UTF-16
         * code units.
         */
        public val DEFAULT_MAX_CLIPBOARD_READ_LENGTH: Int = 64 * 1024

        /** The default of [urlSchemes][GatePolicy.urlSchemes]: http and https. */
        public val DEFAULT_URL_SCHEMES: Set<String> = setOf("http", "https")
    }
}
