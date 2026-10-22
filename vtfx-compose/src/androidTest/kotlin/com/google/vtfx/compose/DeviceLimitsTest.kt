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

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.gate.android.AndroidClipboard
import com.google.vtfx.session.TerminalSession
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks on a device the limits that exist only there, which Robolectric does not model
 * (docs/design/testing.md section 8): the size of a bitmap that a hardware-accelerated canvas
 * draws, the size of a clip that the clipboard's Binder call takes, and drawing clipped to the
 * bounds of `Terminal` by the hardware renderer. Without the limits, these tests fail or crash the
 * test process on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class DeviceLimitsTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    /**
     * Returns a graphics command that transmits a red image of [side] by [side] pixels, compressed,
     * with the control data [keys].
     */
    private fun redImage(side: Int, keys: String): String {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { out ->
            val row = ByteArray(side * 4)
            for (x in 0 until side) {
                row[x * 4] = 0xE0.toByte()
                row[x * 4 + 3] = 0xFF.toByte()
            }
            repeat(side) { out.write(row) }
        }
        val data = Base64.getEncoder().encodeToString(compressed.toByteArray())
        return "\u001b_G$keys,f=32,s=$side,v=$side,o=z,q=2;$data\u001b\\"
    }

    @Test
    fun theLargestImageIsDrawn() {
        // An app may let images take more than the 100 MiB of one bitmap that Android draws; one
        // image still takes at most 64 MiB (docs/decisions/0051).
        val session =
            TerminalSession({}, limits = TerminalLimits(maxImageBytes = 256 * 1024 * 1024))
        val display = TerminalDisplay(session)
        composeRule.setContent { Terminal(display, Modifier.size(320.dp, 200.dp)) }
        settle()
        session.feed(redImage(4096, "a=T,c=4,r=2").toByteArray())
        settle()
        assertEquals(1, display.displaySnapshot().imagePlacements.size)
        val shown = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val pixel = shown.getPixel(4, 4)
        assertEquals("#%06X".format(0xE00000), "#%06X".format(pixel and 0xFFFFFF))
    }

    @Test
    fun anImageLargerThanAndroidDrawsIsNotDrawn() {
        // 5200 by 5200 pixels are 108,160,000 bytes, above the 100 MiB of one bitmap that a
        // hardware-accelerated canvas draws, which throws on the frame that draws it.
        val session =
            TerminalSession({}, limits = TerminalLimits(maxImageBytes = 256 * 1024 * 1024))
        val display = TerminalDisplay(session)
        composeRule.setContent { Terminal(display, Modifier.size(320.dp, 200.dp)) }
        settle()
        session.feed(redImage(5200, "a=T,c=4,r=2").toByteArray())
        settle()
        assertEquals(emptyList<Any>(), display.displaySnapshot().imagePlacements)
    }

    @Test
    fun copyingALargeSelectionReachesTheClipboard() {
        // Each cell holds 32 UTF-16 units, so the cells of a large display are several times the
        // megabyte that one Binder call takes.
        val session = TerminalSession({}, 300, 100, clipboard = AndroidClipboard(context))
        val display = TerminalDisplay(session)
        // Only to give the test's activity the focus, which reading the clipboard needs; a
        // Terminal would resize the session to its own size.
        composeRule.setContent { Box(Modifier.size(10.dp)) }
        settle()
        session.feed(("a" + "\u0301".repeat(31)).repeat(300 * 100).toByteArray())
        var copied = false
        composeRule.runOnIdle {
            display.selection = TerminalSelection(Cell(0, 0), Cell(299, 99))
            copied = display.copySelection()
        }
        assertTrue(copied)
        composeRule.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val length = clipboard.primaryClip?.getItemAt(0)?.text?.length ?: 0
            // The newest whole lines that fit.
            assertTrue("$length", length > MAX_COPIED_LENGTH - 300 * 32 - 1)
            assertTrue("$length", length <= MAX_COPIED_LENGTH)
        }
    }

    @Test
    fun copyingAllTheTextReachesTheClipboard() {
        // Select all takes the scrollback too, many times the megabyte of one Binder call.
        val session =
            TerminalSession(
                {},
                300,
                100,
                limits = TerminalLimits(scrollbackLines = 5000),
                clipboard = AndroidClipboard(context),
            )
        val display = TerminalDisplay(session)
        composeRule.setContent { Box(Modifier.size(10.dp)) }
        settle()
        val line = "x".repeat(299)
        repeat(5100) { session.feed("$line\r\n".toByteArray()) }
        var copied = false
        composeRule.runOnIdle {
            display.selectAll()
            copied = display.copySelection()
        }
        assertTrue(copied)
        composeRule.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val length = clipboard.primaryClip?.getItemAt(0)?.text?.length ?: 0
            assertTrue("$length", length > MAX_COPIED_LENGTH - 300 - 1)
            assertTrue("$length", length <= MAX_COPIED_LENGTH)
        }
    }

    private fun changedPixelsOfWhiteBox(left: Int, top: Int, right: Int, bottom: Int): Int {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        var changed = 0
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in left until minOf(right, bitmap.width)) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) changed++
            }
        }
        return changed
    }

    @Test
    fun stackedCombiningMarksStayInsideTheTerminal() {
        val session = TerminalSession({})
        composeRule.setContent {
            Column {
                Box(Modifier.size(300.dp, 100.dp).background(Color.White))
                Terminal(session, Modifier.size(300.dp, 200.dp))
            }
        }
        settle()
        session.feed(("\u001b[?25l" + ("a" + "\u0346".repeat(31)).repeat(20)).toByteArray())
        settle()
        val (width, height) = with(composeRule.density) { 300.dp.roundToPx() to 100.dp.roundToPx() }
        assertEquals(0, changedPixelsOfWhiteBox(0, 0, width, height))
    }

    @Test
    fun wideGlyphsInTheLastColumnStayInsideTheTerminal() {
        val session = TerminalSession({})
        composeRule.setContent {
            Box {
                Box(Modifier.padding(start = 200.dp).size(200.dp, 100.dp).background(Color.White))
                Terminal(session, Modifier.size(200.dp, 100.dp))
            }
        }
        settle()
        val columns = session.size().first
        val text =
            "\uFDFD\r\n" + String(Character.toChars(0x1242B)) + "\r\n\u1100" + "\u1161".repeat(31)
        session.feed(
            ("\u001b[?25l" + text.split("\r\n").joinToString("") { "\u001b[${columns}G$it\r\n" })
                .toByteArray()
        )
        settle()
        val (left, right, bottom) =
            with(composeRule.density) {
                Triple(200.dp.roundToPx(), 400.dp.roundToPx(), 100.dp.roundToPx())
            }
        assertEquals(0, changedPixelsOfWhiteBox(left, 0, right, bottom))
    }

    private companion object {
        const val SETTLE_MILLIS = 300L

        /** The most characters that a copy takes (section 4 of compose-input.md). */
        const val MAX_COPIED_LENGTH = 256 * 1024
    }
}
