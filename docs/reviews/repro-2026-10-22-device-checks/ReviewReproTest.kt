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

package com.google.vtfx.session

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ReviewReproTest {
    private var restarts = 0
    private val sent = StringBuilder()
    private val session =
        TerminalSession({ sent.append(String(it, Charsets.UTF_8)) }, columns = 80, rows = 24).also {
            it.addObserver(
                object : SessionObserver {
                    override fun onOutput(
                        scrolledLines: Long,
                        scrollbackShrank: Boolean,
                        synchronizing: Boolean,
                    ) {}

                    override fun onScreenChanged() {}

                    override fun onUserInput() {}

                    override fun onRestartInput() {
                        restarts++
                    }
                }
            )
        }

    private fun feed(text: String) = session.feed(text.toByteArray(Charsets.UTF_8))

    /**
     * R1. After Enter restarts the IME, Android finishes the composing text of the connection that
     * the restart closed (seen on a device: `finishComposingText` arrives right after
     * `restartInput`, with no composing text). That call changes nothing, but it makes the echo of
     * Enter, which moves the cursor to the next line, restart the IME a second time. A key that the
     * IME commits between the two restarts goes to a closed connection and is lost.
     */
    @Test
    fun aFinishWithoutComposingTextAfterEnterDoesNotRestartAgainOnTheEcho() {
        session.edit { it.commitText("ab") }
        session.edit { it.onKeyDown(KeyStroke.Special(TerminalKey.Enter)) }
        assertEquals(1, restarts, "Enter restarts the IME once")

        // What the framework calls on the closed connection after restartInput.
        session.edit { it.finishComposingText() }
        // The host echoes the line and moves the cursor to the next line.
        feed("ab\r\n")

        // ime-contract.md 5.2 step 3: no restart while the editing buffer has not changed since
        // the last restart.
        assertEquals(1, restarts)
    }

    /**
     * R2. While output arrives without a break, a reader of the screen (the display drawing a
     * frame on the main thread) waits for the state lock for a long time, since feed holds it for
     * each chunk and takes it again at once.
     */
    @Test
    fun aSnapshotDoesNotWaitLongWhileOutputFloods() {
        val line = "0123456789".repeat(7) + " a line of output\r\n"
        val chunk = line.repeat(16 * 1024 / line.length).toByteArray(Charsets.UTF_8)
        // Without output, taking the screen is quick.
        repeat(20) { session.feed(chunk) }
        val idleStart = System.nanoTime()
        repeat(100) { session.snapshot() }
        println("snapshot without output (ms): ${(System.nanoTime() - idleStart) / 100 / 1e6}")
        val running = AtomicBoolean(true)
        val feeder = thread {
            while (running.get()) session.feed(chunk)
        }
        // Warm up, so that the JIT does not count.
        Thread.sleep(500)
        val waits = mutableListOf<Long>()
        val end = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < end) {
            val start = System.nanoTime()
            session.snapshot()
            waits += (System.nanoTime() - start) / 1_000_000
            // A frame every 16 ms.
            Thread.sleep(16)
        }
        running.set(false)
        feeder.join()
        waits.sort()
        val p90 = waits[waits.size * 9 / 10]
        val summary = "frames=${waits.size} p50=${waits[waits.size / 2]} p90=$p90 max=${waits.last()}"
        println("snapshot waits (ms): $summary")
        // docs/decisions/0014: the UI thread's drawing takes at most 25 ms (p50); taking the
        // screen is only a part of it. p90, since a frame that waits now and then is what freezes
        // the display, and the median varies from run to run.
        assertTrue(p90 <= 25, summary)
    }
}
