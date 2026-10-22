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

package com.google.vtfx.compose

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import com.google.vtfx.core.Hyperlink
import com.google.vtfx.core.LinkPattern

// See docs/decisions/0019. The links and clicks are not data classes, so that properties can be
// added without changing what apps compiled earlier call; only vtfx makes them. TerminalLink is an
// abstract class with an internal constructor rather than a sealed interface, so that apps cannot
// match the kinds exhaustively, and a new kind does not make code compiled earlier throw
// NoWhenBranchMatchedException (docs/decisions/0037).
/**
 * A link on the display that the user clicked, passed to the `onLinkClick` of [Terminal] in a
 * [TerminalLinkClick]. Show [url] to the user and, when they confirm, open it with
 * [TerminalSession.openUrl][com.google.vtfx.session.TerminalSession.openUrl], which refuses the
 * URLs that the session's policy does not allow. This holds for every kind of link: what the user
 * sees may differ from where the link goes.
 *
 * New kinds of links may be added in a minor version, so a `when` over the kinds needs an `else`
 * branch.
 */
public abstract class TerminalLink internal constructor() {
    /**
     * The text of the link on the display, which may go on beyond the display. Untrusted: it comes
     * from the host.
     */
    public abstract val text: String

    /** The URL that the link goes to. Untrusted: it comes from the host, wholly or in part. */
    public abstract val url: String

    /**
     * An OSC 8 hyperlink. The host chose its URL apart from [text][TerminalLink.Explicit.text],
     * which may name another site: show [url] to the user, apart from the text, and open it only
     * when they confirm. Equal to a link with the same values.
     */
    public class Explicit
    internal constructor(
        /**
         * The text of the cells around the tapped one that have the same hyperlink, joined across
         * rows that autowrap joins. Concealed cells (SGR 8), which are not drawn, are blanks.
         */
        override val text: String,
        /** The hyperlink of the tapped cell. */
        public val hyperlink: Hyperlink,
    ) : TerminalLink() {
        override val url: String
            get() = hyperlink.uri

        override fun equals(other: Any?): Boolean =
            other is Explicit && other.text == text && other.hyperlink == hyperlink

        override fun hashCode(): Int = 31 * text.hashCode() + hyperlink.hashCode()

        override fun toString(): String = "Explicit(text=$text, hyperlink=$hyperlink)"
    }

    /**
     * Text on the display that matched [pattern][TerminalLink.Matched.pattern], which made
     * [url][TerminalLink.Matched.url] from it. For [LinkPattern.Urls], the URL is
     * [text][TerminalLink.Matched.text]; for the app's own patterns, it is what the template makes
     * of the matched text. Matches over concealed text are not links, but the text can still differ
     * from what the user sees, for example where the host drew part of it in the background's
     * color: show [url][TerminalLink.Matched.url] as for any link. Equal to a link with the same
     * values.
     */
    public class Matched
    internal constructor(
        /** The matched text, which may go on beyond the display. */
        override val text: String,
        override val url: String,
        /** The pattern that matched, one of the `linkPatterns` of [Terminal]. */
        public val pattern: LinkPattern,
    ) : TerminalLink() {
        override fun equals(other: Any?): Boolean =
            other is Matched && other.text == text && other.url == url && other.pattern == pattern

        override fun hashCode(): Int =
            (31 * text.hashCode() + url.hashCode()) * 31 + pattern.hashCode()

        override fun toString(): String = "Matched(text=$text, url=$url, pattern=$pattern)"
    }
}

/**
 * A click on a link, passed to the `onLinkClick` of [Terminal]: [link][TerminalLinkClick.link], and
 * how the user clicked it, so that the app can, for example, open links only on Ctrl+click with a
 * mouse. Equal to a click with the same values.
 */
public class TerminalLinkClick
internal constructor(
    /** The link that the user clicked. */
    public val link: TerminalLink,
    /**
     * The kind of pointer that clicked, as Compose reports it: [PointerType.Touch] for a tap,
     * [PointerType.Mouse] for a click of a mouse's primary button, and [PointerType.Stylus] for a
     * tap of a stylus. [PointerType.Unknown] when no pointer clicked, as when an accessibility
     * service activates the link without a touch.
     */
    public val pointerType: PointerType,
    /**
     * The modifier keys held when the user clicked, as the pointer event reports them; none when no
     * pointer clicked. Test them with `isCtrlPressed` and the other extensions of
     * `androidx.compose.ui.input.pointer`.
     */
    public val keyboardModifiers: PointerKeyboardModifiers,
) {
    override fun equals(other: Any?): Boolean =
        other is TerminalLinkClick &&
            other.link == link &&
            other.pointerType == pointerType &&
            other.keyboardModifiers == keyboardModifiers

    override fun hashCode(): Int =
        (31 * link.hashCode() + pointerType.hashCode()) * 31 + keyboardModifiers.hashCode()

    override fun toString(): String =
        "TerminalLinkClick(link=$link, pointerType=$pointerType, " +
            "keyboardModifiers=$keyboardModifiers)"
}
