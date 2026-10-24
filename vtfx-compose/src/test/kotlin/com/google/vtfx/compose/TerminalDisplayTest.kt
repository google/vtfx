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

import android.os.Looper
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
class TerminalDisplayTest {
    private val resizes = mutableListOf<Pair<Int, Int>>()
    private val session =
        TerminalSession(
            {},
            columns = 20,
            rows = 5,
            listener =
                object : TerminalSessionListener {
                    override fun onResize(columns: Int, rows: Int) {
                        resizes += Pair(columns, rows)
                    }
                },
        )
    private val display = TerminalDisplay(session).also { it.attach() }

    private fun feed(text: String) = session.feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun copyingASelectionOfTheDisplayIsCutLikeAllTheText() {
        val clipboard = RecordingClipboard()
        val session = TerminalSession({}, columns = 300, rows = 100, clipboard = clipboard)
        // Each cell holds 32 UTF-16 units, as many as a cell takes: 9,600 characters a row.
        val cluster = "a" + "\u0301".repeat(31)
        session.feed(cluster.repeat(300 * 100).toByteArray(Charsets.UTF_8))
        val display = TerminalDisplay(session)
        display.selection = TerminalSelection(Cell(0, 0), Cell(299, 99))

        assertTrue(display.copySelection())

        val text = clipboard.copied.single()
        assertTrue("${text.length}", text.length <= MAX_COPIED_LENGTH)
        // Autowrap joined the rows into one line, longer than what is copied: its end.
        assertEquals(cluster.repeat(300 * 100).takeLast(MAX_COPIED_LENGTH), text)
    }

    private companion object {
        /** The most characters that are copied (section 4 of compose-input.md). */
        const val MAX_COPIED_LENGTH = 256 * 1024
    }

    @Test
    fun theFirstSizeIsAppliedAtOnce() {
        display.resize(40, 12)
        assertEquals(listOf(Pair(40, 12)), resizes)
    }

    @Test
    fun theFirstSizeIsReportedEvenIfItIsTheSessionsSize() {
        display.resize(20, 5)
        assertEquals(listOf(Pair(20, 5)), resizes)
        // A display recreated later, after a configuration change for example, reports no more.
        display.detach()
        val recreated = TerminalDisplay(session).also { it.attach() }
        recreated.resize(20, 5)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(listOf(Pair(20, 5)), resizes)
        recreated.detach()
    }

    @Test
    fun resizesDuringAnAnimationAreCoalesced() {
        display.resize(20, 5)
        for (rows in 20 downTo 10) {
            display.resize(30, rows)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        assertEquals(listOf(Pair(20, 5)), resizes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(listOf(Pair(20, 5), Pair(30, 10)), resizes)
    }

    @Test
    fun aDetachedDisplayNoLongerFollowsTheSession() {
        display.detach()
        val start = display.observeVersion()
        feed("abc")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(start, display.observeVersion())
    }

    @Test
    fun outputFromAnotherThreadWaitsForTheMainThreadOnceForEachKind() {
        // Emulation.md section 10.1: the main thread's queue does not grow with host output.
        val reader = Thread { repeat(10_000) { feed("x\n") } }
        reader.start()
        reader.join()
        val looper = shadowOf(Looper.getMainLooper())
        var tasks = 0
        while (!looper.isIdle) {
            looper.runOneTask()
            tasks++
        }
        // One redraw and one to follow the scrollback.
        assertEquals(2, tasks)
    }

    @Test
    fun restartsOfTheImeFromAnotherThreadWaitForTheMainThreadOnce() {
        var restarts = 0
        display.inputHost =
            object : InputHost {
                override fun restartInput() {
                    restarts++
                }

                override fun onScreenChanged() {}
            }
        val other = Thread {
            repeat(100) {
                session.edit { it.commitText("a") }
                feed("\r\n")
            }
        }
        other.start()
        other.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, restarts)
    }

    @Test
    fun synchronizedOutputDefersDrawingUntilItEnds() {
        val start = display.observeVersion()
        feed("\u001b[?2026habc")
        assertEquals(start, display.observeVersion())
        feed("def\u001b[?2026l")
        assertEquals(start + 1, display.observeVersion())
    }

    @Test
    fun synchronizedOutputThatNeverEndsIsDrawnAfterATimeout() {
        val start = display.observeVersion()
        feed("\u001b[?2026habc")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(start, display.observeVersion())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(start + 1, display.observeVersion())
    }

    @Test
    fun synchronizedOutputThatKeepsComingIsDrawnAfterATimeout() {
        val looper = shadowOf(Looper.getMainLooper())
        val start = display.observeVersion()
        feed("\u001b[?2026h")
        // The timeout counts from the first deferred update, not from the latest output.
        repeat(3) {
            looper.idleFor(Duration.ofMillis(60))
            feed("x")
        }
        assertEquals(start, display.observeVersion())
        looper.idleFor(Duration.ofMillis(30))
        assertEquals(start + 1, display.observeVersion())
        // Output that keeps coming after the timeout is drawn after another one, counted from
        // the first output after it.
        repeat(3) {
            looper.idleFor(Duration.ofMillis(60))
            feed("y")
        }
        looper.idleFor(Duration.ofMillis(70))
        assertEquals(start + 1, display.observeVersion())
        looper.idleFor(Duration.ofMillis(10))
        assertEquals(start + 2, display.observeVersion())
    }

    @Test
    fun theEndOfSynchronizedOutputCancelsTheTimeout() {
        val looper = shadowOf(Looper.getMainLooper())
        val start = display.observeVersion()
        feed("\u001b[?2026habc")
        looper.idleFor(Duration.ofMillis(100))
        feed("\u001b[?2026l")
        assertEquals(start + 1, display.observeVersion())
        // A new synchronized update gets a whole timeout of its own.
        feed("\u001b[?2026hdef")
        looper.idleFor(Duration.ofMillis(150))
        assertEquals(start + 1, display.observeVersion())
        looper.idleFor(Duration.ofMillis(50))
        assertEquals(start + 2, display.observeVersion())
    }

    private fun fullScrollbackDisplay(): TerminalDisplay =
        TerminalDisplay(
                TerminalSession(
                    {},
                    columns = 6,
                    rows = 3,
                    limits = TerminalLimits(scrollbackLines = 3),
                )
            )
            .also {
                it.attach()
                // The scrollback has 1 to 3, the screen 4 to 6.
                it.session.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6".toByteArray())
                it.scrollBy(1)
            }

    private fun TerminalDisplay.topDisplayedLine(): String =
        displaySnapshot().line(0).text().trimEnd()

    @Test
    fun theViewedLinesStayWhenTheScrollbackIsFull() {
        val full = fullScrollbackDisplay()
        assertEquals("3", full.topDisplayedLine())
        full.session.feed("\r\n7".toByteArray())
        assertEquals("3", full.topDisplayedLine())
        full.session.feed("\r\n8".toByteArray())
        assertEquals("3", full.topDisplayedLine())
    }

    @Test
    fun theOldestLineIsShownWhenTheViewedOneIsDropped() {
        val full = fullScrollbackDisplay()
        full.session.feed("\r\n7\r\n8\r\n9".toByteArray())
        assertEquals("4", full.topDisplayedLine())
        assertEquals(3, full.scrollOffset)
    }

    @Test
    fun clearingTheScrollbackReturnsToTheBottom() {
        val full = fullScrollbackDisplay()
        full.session.feed("\u001b[3J".toByteArray())
        assertEquals(0, full.scrollOffset)
        full.session.feed("\r\n7".toByteArray())
        assertEquals(0, full.scrollOffset)
        assertEquals("5", full.topDisplayedLine())
    }
}
