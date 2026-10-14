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

package com.google.vtfx.gate

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.MouseEncoding
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.TerminalLimits

/**
 * A mouse button, or [None] for motion without a button.
 *
 * @suppress
 */
@InternalVtfxApi
public enum class MouseButton {
    Left,
    Middle,
    Right,
    None,
    WheelUp,
    WheelDown,
}

/**
 * What happened to the button.
 *
 * @suppress
 */
@InternalVtfxApi
public enum class MouseAction {
    Press,
    Release,
    Motion,
}

/**
 * A mouse event to report to the host: [action] of [button] at [column] and [row], 1-based cells,
 * with the modifiers [shift], [alt], and [ctrl] held.
 *
 * @suppress
 */
@InternalVtfxApi
public data class MouseReport(
    val button: MouseButton,
    val action: MouseAction,
    val column: Int,
    val row: Int,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
) {
    init {
        require(
            column in 1..TerminalLimits.MAX_DIMENSION && row in 1..TerminalLimits.MAX_DIMENSION
        ) {
            "position out of range: $column,$row"
        }
    }
}

/**
 * Encodes mouse reports as xterm does (ctlseqs, "Mouse Tracking"). Whether an event is reported at
 * all depends on the tracking mode, which [encode] checks too, so that a caller cannot report
 * events the host did not ask for.
 */
internal object MouseReports {
    fun encode(report: MouseReport, tracking: MouseTracking, encoding: MouseEncoding): ByteArray? {
        if (!isReported(report, tracking)) return null
        var code =
            when (report.button) {
                MouseButton.Left -> 0
                MouseButton.Middle -> 1
                MouseButton.Right -> 2
                MouseButton.None -> 3
                MouseButton.WheelUp -> 64
                MouseButton.WheelDown -> 65
            }
        if (report.action == MouseAction.Motion) code += 32
        // X10 mode reports no modifiers.
        if (tracking != MouseTracking.X10) {
            if (report.shift) code += 4
            if (report.alt) code += 8
            if (report.ctrl) code += 16
        }
        val (x, y) = report.column to report.row
        val text =
            when (encoding) {
                MouseEncoding.Sgr ->
                    "\u001b[<$code;$x;${y}${if (report.action == MouseAction.Release) 'm' else 'M'}"
                MouseEncoding.Urxvt -> "\u001b[${legacyButton(code, report)};$x;${y}M"
                MouseEncoding.Default -> {
                    // One byte per value: positions beyond 223 cannot be reported.
                    if (x > MAX_DEFAULT_POSITION || y > MAX_DEFAULT_POSITION) return null
                    return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte()) +
                        byteArrayOf(
                            legacyButton(code, report).toByte(),
                            (x + 32).toByte(),
                            (y + 32).toByte(),
                        )
                }
                MouseEncoding.Utf8 -> {
                    if (x > MAX_UTF8_POSITION || y > MAX_UTF8_POSITION) return null
                    "\u001b[M" +
                        String(Character.toChars(legacyButton(code, report))) +
                        String(Character.toChars(x + 32)) +
                        String(Character.toChars(y + 32))
                }
            }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Legacy encodings report a release as button 3 and offset the code by 32. */
    private fun legacyButton(code: Int, report: MouseReport): Int {
        val released =
            if (
                report.action == MouseAction.Release &&
                    report.button.ordinal <= MouseButton.Right.ordinal
            ) {
                (code and 3.inv()) or 3
            } else {
                code
            }
        return released + 32
    }

    private fun isReported(report: MouseReport, tracking: MouseTracking): Boolean =
        when (tracking) {
            MouseTracking.None -> false
            MouseTracking.X10 -> report.action == MouseAction.Press
            MouseTracking.Normal -> report.action != MouseAction.Motion
            MouseTracking.ButtonEvent ->
                report.action != MouseAction.Motion || report.button != MouseButton.None
            MouseTracking.AnyEvent -> true
        }

    private const val MAX_DEFAULT_POSITION = 223
    private const val MAX_UTF8_POSITION = 2015
}
