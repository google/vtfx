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

import com.google.vtfx.core.CellStyle
import com.google.vtfx.core.ColorTarget
import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.GraphicsStatus
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.ModeState
import com.google.vtfx.core.MouseEncoding
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalEvent
import com.google.vtfx.core.TerminalQuery
import kotlin.test.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RepliesAndMouseTest {
    private val sent = mutableListOf<String>()
    private val writer = HostWriter { sent += String(it, Charsets.UTF_8).replace("\u001b", "\\e") }

    @Nested
    inner class Replies {
        private val colors = ReportedColors { target ->
            when (target) {
                ColorTarget.Foreground -> 0xE5E5E5
                ColorTarget.Background -> 0x000000
                ColorTarget.Cursor -> null
                is ColorTarget.Palette -> 0xCD0000
            }
        }

        @Test
        fun modeStatus() {
            writer.sendReply(TerminalQuery.ModeStatus(2004, true, ModeState.Set))
            writer.sendReply(TerminalQuery.ModeStatus(4, false, ModeState.Reset))
            writer.sendReply(TerminalQuery.ModeStatus(9999, true, ModeState.NotRecognized))
            assertEquals(listOf("\\e[?2004;1\$y", "\\e[4;2\$y", "\\e[?9999;0\$y"), sent)
        }

        @Test
        fun statusStrings() {
            // The style comes from a real terminal, as the gate would get it.
            val styles = mutableListOf<TerminalQuery.SgrStatus>()
            Terminal(10, 1) { event ->
                    val query = (event as? com.google.vtfx.core.TerminalEvent.ReplyRequested)?.query
                    if (query is TerminalQuery.SgrStatus) styles += query
                }
                .write(
                    ("\u001b[1;4:3;91;48;5;200;38;2;1;2;3m\u001bP\$qm\u001b\\" +
                            "\u001b[0;58:5:3m\u001bP\$qm\u001b\\" +
                            "\u001b[58;2;4;5;6m\u001bP\$qm\u001b\\")
                        .toByteArray()
                )
            styles.forEach { writer.sendReply(it) }
            writer.sendReply(TerminalQuery.SgrStatus(CellStyle.Default))
            writer.sendReply(TerminalQuery.ScrollingRegionStatus(2, 5))
            writer.sendReply(TerminalQuery.CursorStyleStatus(CursorShape.Bar, isBlinking = true))
            writer.sendReply(TerminalQuery.UnsupportedStatus)
            assertEquals(
                listOf(
                    "\\eP1\$r0;1;4:3;38;2;1;2;3;48;5;200m\\e\\",
                    "\\eP1\$r0;58;5;3m\\e\\",
                    "\\eP1\$r0;58;2;4;5;6m\\e\\",
                    "\\eP1\$r0m\\e\\",
                    "\\eP1\$r2;5r\\e\\",
                    "\\eP1\$r5 q\\e\\",
                    "\\eP0\$r\\e\\",
                ),
                sent,
            )
        }

        @Test
        fun versionAndSize() {
            writer.sendReply(TerminalQuery.TerminalVersion)
            writer.sendReply(TerminalQuery.TextAreaSize(24, 80))
            writer.sendReply(TerminalQuery.TextAreaPixelSize(456, 800))
            writer.sendReply(TerminalQuery.CellPixelSize(19, 10))
            assertEquals(
                listOf("\\eP>|vtfx(0.1)\\e\\", "\\e[8;24;80t", "\\e[4;456;800t", "\\e[6;19;10t"),
                sent,
            )
        }

        @Test
        fun colorsComeFromTheDisplay() {
            writer.sendReply(TerminalQuery.Color(ColorTarget.Foreground), colors)
            writer.sendReply(TerminalQuery.Color(ColorTarget.Background), colors)
            writer.sendReply(TerminalQuery.Color(ColorTarget.Palette(1)), colors)
            writer.sendReply(TerminalQuery.Color(ColorTarget.Cursor), colors)
            writer.sendReply(TerminalQuery.Color(ColorTarget.Foreground))
            assertEquals(
                listOf(
                    "\\e]10;rgb:e5e5/e5e5/e5e5\\e\\",
                    "\\e]11;rgb:0000/0000/0000\\e\\",
                    "\\e]4;1;rgb:cdcd/0000/0000\\e\\",
                ),
                sent,
            )
        }

        @Test
        fun graphicsReplies() {
            // From a real terminal, as the gate would get them.
            val terminal =
                Terminal(80, 24) { event ->
                    ((event as? TerminalEvent.ReplyRequested)?.query)?.let { writer.sendReply(it) }
                }
            fun write(text: String) = terminal.write(text.toByteArray(Charsets.US_ASCII))
            write("\u001b_Ga=q,i=31,s=1,v=1,f=24;AAAA\u001b\\")
            write("\u001b_Ga=t,I=7,s=1,v=1,f=24;AAAA\u001b\\")
            write("\u001b_Ga=p,i=1,p=4294967295\u001b\\")
            write("\u001b_Ga=p,i=2\u001b\\")
            write("\u001b_Ga=q,i=3,f=100;AAAA\u001b\\")
            write("\u001b_Ga=q,i=4,t=f,f=100;L2V0Yy9wYXNzd2Q=\u001b\\")
            assertEquals(
                listOf(
                    "\\e_Gi=31;OK\\e\\",
                    "\\e_Gi=1,I=7;OK\\e\\",
                    "\\e_Gi=1,p=4294967295;OK\\e\\",
                    "\\e_Gi=2;ENOENT:no such image\\e\\",
                    "\\e_Gi=3;EBADPNG:malformed PNG data\\e\\",
                    "\\e_Gi=4;EBADF:only direct transmission is supported\\e\\",
                ),
                sent,
            )
        }

        @Test
        fun everyGraphicsStatusHasAKnownReply() {
            for (status in GraphicsStatus.entries) {
                writer.sendReply(TerminalQuery.GraphicsReply(1, 2, 3, status))
            }
            val unknown =
                sent
                    .map { it.replace("\\e", "\u001b") }
                    .filterNot { KnownReplies.pattern.matches(it) }
            assertEquals(emptyList(), unknown)
        }
    }

    @Nested
    inner class Mouse {
        private fun send(
            report: MouseReport,
            tracking: MouseTracking = MouseTracking.AnyEvent,
            encoding: MouseEncoding = MouseEncoding.Sgr,
        ) = writer.sendMouse(report, tracking, encoding)

        @Test
        fun sgrEncoding() {
            send(MouseReport(MouseButton.Left, MouseAction.Press, 3, 4))
            send(MouseReport(MouseButton.Left, MouseAction.Release, 3, 4))
            send(MouseReport(MouseButton.Right, MouseAction.Press, 300, 4, ctrl = true))
            send(MouseReport(MouseButton.Left, MouseAction.Motion, 5, 6, shift = true))
            send(MouseReport(MouseButton.WheelDown, MouseAction.Press, 1, 1, alt = true))
            assertEquals(
                listOf(
                    "\\e[<0;3;4M",
                    "\\e[<0;3;4m",
                    "\\e[<18;300;4M",
                    "\\e[<36;5;6M",
                    "\\e[<73;1;1M",
                ),
                sent,
            )
        }

        @Test
        fun defaultEncoding() {
            send(
                MouseReport(MouseButton.Middle, MouseAction.Press, 1, 2),
                encoding = MouseEncoding.Default,
            )
            send(
                MouseReport(MouseButton.Middle, MouseAction.Release, 1, 2),
                encoding = MouseEncoding.Default,
            )
            send(
                MouseReport(MouseButton.Left, MouseAction.Press, 224, 1),
                encoding = MouseEncoding.Default,
            )
            assertEquals(listOf("\\e[M!!\"", "\\e[M#!\""), sent)
        }

        @Test
        fun utf8AndUrxvtEncodings() {
            send(
                MouseReport(MouseButton.Left, MouseAction.Press, 300, 1),
                encoding = MouseEncoding.Utf8,
            )
            send(
                MouseReport(MouseButton.Left, MouseAction.Release, 300, 1),
                encoding = MouseEncoding.Urxvt,
            )
            assertEquals(listOf("\\e[M \u014c!", "\\e[35;300;1M"), sent)
        }

        @Test
        fun trackingModesFilterEvents() {
            val press = MouseReport(MouseButton.Left, MouseAction.Press, 1, 1, ctrl = true)
            val release = MouseReport(MouseButton.Left, MouseAction.Release, 1, 1)
            val drag = MouseReport(MouseButton.Left, MouseAction.Motion, 1, 1)
            val move = MouseReport(MouseButton.None, MouseAction.Motion, 1, 1)
            for (report in listOf(press, release, drag, move)) send(report, MouseTracking.None)
            assertEquals(emptyList(), sent)
            for (report in listOf(press, release, drag, move)) send(report, MouseTracking.X10)
            assertEquals(listOf("\\e[<0;1;1M"), sent)
            sent.clear()
            for (report in listOf(press, release, drag, move)) send(report, MouseTracking.Normal)
            assertEquals(2, sent.size)
            sent.clear()
            for (report in listOf(press, release, drag, move)) send(
                report,
                MouseTracking.ButtonEvent,
            )
            assertEquals(3, sent.size)
            sent.clear()
            for (report in listOf(press, release, drag, move)) send(report, MouseTracking.AnyEvent)
            assertEquals(4, sent.size)
        }
    }
}
