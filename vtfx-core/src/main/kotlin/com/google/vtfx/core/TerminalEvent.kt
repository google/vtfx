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

package com.google.vtfx.core

/**
 * Something that the host output requested and that the terminal model cannot do by itself.
 *
 * The model never acts on these. Whether and how to act is decided by vtfx-gate
 * (docs/design/architecture.md section 1). Events never carry strings from the host where a reply
 * could echo them back, so that a reply cannot inject host-controlled input. The one exception is
 * the selection of OSC 52, which the reply to a read repeats: it is limited to the characters of
 * [CLIPBOARD_SELECTION_CHARACTERS] here, and vtfx-gate parses it again with its own list of targets
 * and spells the reply from that list.
 *
 * @suppress
 */
@InternalVtfxApi
public sealed interface TerminalEvent {
    /** BEL was received. */
    @InternalVtfxApi public data object Bell : TerminalEvent

    /**
     * OSC 0 or 2 set the window title, or XTWINOPS 23 restored a saved one. [title] is untrusted
     * text from the host.
     */
    @InternalVtfxApi public data class TitleChanged(val title: String) : TerminalEvent

    /** The host asked for a reply to [query]. */
    @InternalVtfxApi public data class ReplyRequested(val query: TerminalQuery) : TerminalEvent

    /**
     * OSC 52 asked to set the clipboard to [text], which is untrusted text from the host. An empty
     * [text] clears it. [selection] is the OSC 52 selection parameter, only characters of
     * [CLIPBOARD_SELECTION_CHARACTERS].
     */
    @InternalVtfxApi
    public data class ClipboardWriteRequested(val selection: String, val text: String) :
        TerminalEvent

    /** OSC 52 asked to read the clipboard. [selection] is as in [ClipboardWriteRequested]. */
    @InternalVtfxApi public data class ClipboardReadRequested(val selection: String) : TerminalEvent

    @InternalVtfxApi
    public companion object {
        /** The characters allowed in the selection parameter of OSC 52, as in xterm. */
        public const val CLIPBOARD_SELECTION_CHARACTERS: String = "cpqs01234567"
    }
}

/**
 * A query from the host. Each carries only the values that the reply needs, taken from the terminal
 * state, never text from the host.
 *
 * @suppress
 */
@InternalVtfxApi
public sealed interface TerminalQuery {
    /** DA1 (CSI c). */
    @InternalVtfxApi public data object PrimaryDeviceAttributes : TerminalQuery

    /** DA2 (CSI > c). */
    @InternalVtfxApi public data object SecondaryDeviceAttributes : TerminalQuery

    /** DSR 5 (CSI 5 n): the operating status. */
    @InternalVtfxApi public data object OperatingStatus : TerminalQuery

    /**
     * DSR 6 (CSI 6 n) or DECXCPR (CSI ? 6 n, if [isDecPrivate]): the cursor position, [row] and
     * [column], 1-based and relative to the top margin if origin mode is set.
     */
    @InternalVtfxApi
    public data class CursorPosition(val row: Int, val column: Int, val isDecPrivate: Boolean) :
        TerminalQuery

    /** DECRQM (CSI Ps $ p, or CSI ? Ps $ p if [isDecPrivate]): the [state] of [mode]. */
    @InternalVtfxApi
    public data class ModeStatus(val mode: Int, val isDecPrivate: Boolean, val state: ModeState) :
        TerminalQuery

    /**
     * DECRQSS for SGR (DCS $ q m ST): the current rendition, [style] and [underlineColor] (SGR 58).
     */
    @InternalVtfxApi
    public data class SgrStatus(
        val style: CellStyle,
        val underlineColor: TerminalColor = TerminalColor.Default,
    ) : TerminalQuery

    /**
     * DECRQSS for DECSTBM (DCS $ q r ST): the scrolling region, [top] to [bottom], 1-based and
     * inclusive.
     */
    @InternalVtfxApi
    public data class ScrollingRegionStatus(val top: Int, val bottom: Int) : TerminalQuery

    /**
     * DECRQSS for DECSCUSR (DCS $ q SP q ST): the cursor style, [shape] and whether [isBlinking].
     */
    @InternalVtfxApi
    public data class CursorStyleStatus(val shape: CursorShape, val isBlinking: Boolean) :
        TerminalQuery

    /** DECRQSS for a setting that is not supported. */
    @InternalVtfxApi public data object UnsupportedStatus : TerminalQuery

    /** XTVERSION (CSI > q): the name and version of the terminal. */
    @InternalVtfxApi public data object TerminalVersion : TerminalQuery

    /** XTWINOPS 18 (CSI 18 t): the size of the text area in characters, [rows] and [columns]. */
    @InternalVtfxApi public data class TextAreaSize(val rows: Int, val columns: Int) : TerminalQuery

    /**
     * XTWINOPS 14 (CSI 14 t): the size of the text area in pixels, [height] and [width], from the
     * size of a cell that the display gave (docs/decisions/0038).
     */
    @InternalVtfxApi
    public data class TextAreaPixelSize(val height: Int, val width: Int) : TerminalQuery

    /** XTWINOPS 16 (CSI 16 t): the size of a cell in pixels, [height] and [width]. */
    @InternalVtfxApi
    public data class CellPixelSize(val height: Int, val width: Int) : TerminalQuery

    /**
     * OSC 10, 11, 12, or 4 with "?": the color of [target]. The colors are the display's, not the
     * model's.
     */
    @InternalVtfxApi public data class Color(val target: ColorTarget) : TerminalQuery

    /**
     * The reply to a kitty graphics command (docs/decisions/0038): the [imageId] (or the id given
     * to a new image with a number), the [imageNumber] and [placementId] of the command, each from
     * 0 to 4294967295 with 0 for none, and the [status].
     */
    @InternalVtfxApi
    public data class GraphicsReply(
        val imageId: Long,
        val imageNumber: Long,
        val placementId: Long,
        val status: GraphicsStatus,
    ) : TerminalQuery
}

/**
 * The outcome of a kitty graphics command, which its reply reports.
 *
 * @suppress
 */
@InternalVtfxApi
public enum class GraphicsStatus {
    /** The command succeeded. */
    Ok,
    /** The command is not valid: both an id and a number, an unknown format, a zero size. */
    InvalidCommand,
    /** No image has the id or number. */
    NotFound,
    /** The data is shorter than the image needs. */
    InsufficientData,
    /** The data is not a PNG image that can be decoded. */
    BadPng,
    /** The image is larger than the limits allow. */
    TooLarge,
    /** The data is to be read from a file or shared memory, which vtfx does not do. */
    UnsupportedMedium,
    /** The compressed data cannot be inflated. */
    InflateFailed,
    /** The command is for animation, which vtfx does not support. */
    Unsupported,
}

/**
 * The state of a mode reported by DECRQM.
 *
 * @suppress
 */
@InternalVtfxApi
public enum class ModeState(internal val value: Int) {
    NotRecognized(0),
    Set(1),
    Reset(2),
    PermanentlySet(3),
    PermanentlyReset(4);

    /** The Ps value of the reply. */
    public val code: Int
        get() = value
}

/**
 * The color asked by OSC 10, 11, 12, or 4.
 *
 * @suppress
 */
@InternalVtfxApi
public sealed interface ColorTarget {
    /** The default foreground color (OSC 10). */
    @InternalVtfxApi public data object Foreground : ColorTarget

    /** The default background color (OSC 11). */
    @InternalVtfxApi public data object Background : ColorTarget

    /** The cursor color (OSC 12). */
    @InternalVtfxApi public data object Cursor : ColorTarget

    /** An indexed color (OSC 4), [index] 0 to 255. */
    @InternalVtfxApi public data class Palette(val index: Int) : ColorTarget
}

/**
 * Receives [TerminalEvent]s while [Terminal.write] runs, on the same thread.
 *
 * @suppress
 */
@InternalVtfxApi
public fun interface TerminalEventListener {
    /** Must not call back into the [Terminal]. */
    public fun onEvent(event: TerminalEvent)
}
