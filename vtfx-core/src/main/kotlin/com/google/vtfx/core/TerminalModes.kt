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

package com.google.vtfx.core

// The mouse modes are not public API: only vtfx-compose and vtfx-gate act on them, and the
// encodings are likely to grow (SGR-Pixels, mode 1016), which would break exhaustive whens of apps
// compiled earlier (docs/decisions/0037).
/**
 * Which mouse events the host asked to receive (DECSET 9, 1000, 1002, 1003).
 *
 * @suppress
 */
@InternalVtfxApi
public enum class MouseTracking {
    None,
    /** Button presses only (DECSET 9). */
    X10,
    /** Presses and releases (DECSET 1000). */
    Normal,
    /** Presses, releases, and motion while a button is held (DECSET 1002). */
    ButtonEvent,
    /** Presses, releases, and all motion (DECSET 1003). */
    AnyEvent,
}

/**
 * How mouse reports are encoded (DECSET 1005, 1006, 1015).
 *
 * @suppress
 */
@InternalVtfxApi
public enum class MouseEncoding {
    Default,
    Utf8,
    Sgr,
    Urxvt,
}

/**
 * The shape of the cursor, set by DECSCUSR. The set is closed: a shape is added only in a major
 * version.
 */
public enum class CursorShape {
    Block,
    Underline,
    Bar,
}

// Not a data class, so that modes can be added without changing what apps compiled earlier call
// (docs/decisions/0037). Only vtfx makes them.
/**
 * The modes that affect input and display, as set by the host. Immutable, and equal to modes with
 * the same values.
 */
public class TerminalModes
internal constructor(
    /** DECCKM: cursor keys send application sequences (ESC O A instead of CSI A). */
    public val applicationCursorKeys: Boolean = false,
    /** DECKPAM: the keypad sends application sequences. */
    public val applicationKeypad: Boolean = false,
    /** LNM: Enter sends CR LF. */
    public val newLine: Boolean = false,
    /** DECTCEM: the cursor is visible. */
    public val cursorVisible: Boolean = true,
    /** DECSCUSR: the shape of the cursor. */
    public val cursorShape: CursorShape = CursorShape.Block,
    /** DECSCUSR or DECSET 12: the cursor blinks. */
    public val cursorBlinking: Boolean = false,
    /** DECSCNM: the screen is displayed in reverse video. */
    public val reverseVideo: Boolean = false,
    /** Mode 2004: pasted text is bracketed. */
    public val bracketedPaste: Boolean = false,
    /** Mode 1004: focus changes are reported. */
    public val focusEvents: Boolean = false,
    /** Mode 2026: the host is in the middle of a synchronized update. */
    public val synchronizedOutput: Boolean = false,
    /**
     * Modes 9, 1000, 1002, 1003: which mouse events are reported.
     *
     * @suppress
     */
    @InternalVtfxApi public val mouseTracking: MouseTracking = MouseTracking.None,
    /**
     * Modes 1005, 1006, 1015: how mouse events are encoded.
     *
     * @suppress
     */
    @InternalVtfxApi public val mouseEncoding: MouseEncoding = MouseEncoding.Default,
    /**
     * Flag 1 of the kitty keyboard protocol (`CSI > 1 u` and the like): keys are sent in its
     * unambiguous forms, such as `CSI 27 u` for Esc (docs/decisions/0070).
     *
     * @suppress
     */
    @InternalVtfxApi public val disambiguateEscapeCodes: Boolean = false,
) {
    /** Returns these modes with the given ones replaced. */
    internal fun copy(
        applicationCursorKeys: Boolean = this.applicationCursorKeys,
        applicationKeypad: Boolean = this.applicationKeypad,
        newLine: Boolean = this.newLine,
        cursorVisible: Boolean = this.cursorVisible,
        cursorShape: CursorShape = this.cursorShape,
        cursorBlinking: Boolean = this.cursorBlinking,
        reverseVideo: Boolean = this.reverseVideo,
        bracketedPaste: Boolean = this.bracketedPaste,
        focusEvents: Boolean = this.focusEvents,
        synchronizedOutput: Boolean = this.synchronizedOutput,
        mouseTracking: MouseTracking = this.mouseTracking,
        mouseEncoding: MouseEncoding = this.mouseEncoding,
        disambiguateEscapeCodes: Boolean = this.disambiguateEscapeCodes,
    ): TerminalModes =
        TerminalModes(
            applicationCursorKeys,
            applicationKeypad,
            newLine,
            cursorVisible,
            cursorShape,
            cursorBlinking,
            reverseVideo,
            bracketedPaste,
            focusEvents,
            synchronizedOutput,
            mouseTracking,
            mouseEncoding,
            disambiguateEscapeCodes,
        )

    override fun equals(other: Any?): Boolean =
        other is TerminalModes &&
            other.applicationCursorKeys == applicationCursorKeys &&
            other.applicationKeypad == applicationKeypad &&
            other.newLine == newLine &&
            other.cursorVisible == cursorVisible &&
            other.cursorShape == cursorShape &&
            other.cursorBlinking == cursorBlinking &&
            other.reverseVideo == reverseVideo &&
            other.bracketedPaste == bracketedPaste &&
            other.focusEvents == focusEvents &&
            other.synchronizedOutput == synchronizedOutput &&
            other.mouseTracking == mouseTracking &&
            other.mouseEncoding == mouseEncoding &&
            other.disambiguateEscapeCodes == disambiguateEscapeCodes

    override fun hashCode(): Int {
        var hash = 0
        hash = 31 * hash + applicationCursorKeys.hashCode()
        hash = 31 * hash + applicationKeypad.hashCode()
        hash = 31 * hash + newLine.hashCode()
        hash = 31 * hash + cursorVisible.hashCode()
        hash = 31 * hash + cursorShape.hashCode()
        hash = 31 * hash + cursorBlinking.hashCode()
        hash = 31 * hash + reverseVideo.hashCode()
        hash = 31 * hash + bracketedPaste.hashCode()
        hash = 31 * hash + focusEvents.hashCode()
        hash = 31 * hash + synchronizedOutput.hashCode()
        hash = 31 * hash + mouseTracking.hashCode()
        hash = 31 * hash + mouseEncoding.hashCode()
        hash = 31 * hash + disambiguateEscapeCodes.hashCode()
        return hash
    }

    override fun toString(): String =
        "TerminalModes(" +
            "applicationCursorKeys=$applicationCursorKeys, " +
            "applicationKeypad=$applicationKeypad, " +
            "newLine=$newLine, " +
            "cursorVisible=$cursorVisible, " +
            "cursorShape=$cursorShape, " +
            "cursorBlinking=$cursorBlinking, " +
            "reverseVideo=$reverseVideo, " +
            "bracketedPaste=$bracketedPaste, " +
            "focusEvents=$focusEvents, " +
            "synchronizedOutput=$synchronizedOutput, " +
            "mouseTracking=$mouseTracking, " +
            "mouseEncoding=$mouseEncoding, " +
            "disambiguateEscapeCodes=$disambiguateEscapeCodes" +
            ")"
}
