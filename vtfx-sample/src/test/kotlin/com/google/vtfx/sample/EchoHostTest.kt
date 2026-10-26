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

package com.google.vtfx.sample

import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoHostTest {
    private val output = StringBuilder()
    private val host =
        EchoHost(
            output = { output.append(String(it, Charsets.UTF_8)) },
            recording = { name -> if (name == "demo") "\u001b[?1049hDEMO".toByteArray() else null },
            requestSize = { columns, rows -> requested += columns to rows },
            font = { fontCommands += it },
        )
    private val requested = mutableListOf<Pair<Int, Int>>()
    private val fontCommands = mutableListOf<String>()

    private fun receive(text: String) = host.receive(text.toByteArray(Charsets.UTF_8))

    @Test
    fun echoesTypedText() {
        receive("ls")
        assertEquals("ls", output.toString())
    }

    @Test
    fun backspaceErasesByWidth() {
        receive("aあ\u007f\u007f")
        assertEquals("aあ\b\b  \b\b\b \b", output.toString())
    }

    @Test
    fun enterRunsTheLineAndPrompts() {
        receive("size\r")
        assertEquals("size\r\n80 x 24\r\n\u001b[32m$\u001b[0m ", output.toString())
    }

    @Test
    fun linksShowHyperlinks() {
        receive("links\r")
        val links =
            Regex("\u001b]8;;([^\u001b]+)\u001b\\\\")
                .findAll(output)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            listOf("https://example.com/", "https://example.org/", "javascript:alert(1)"),
            links,
        )
    }

    @Test
    fun keysAndPasteMarkersAreNotEchoed() {
        receive("\u001b[A\u001bOP\u001b[200~x\u001b[201~")
        assertEquals("x", output.toString())
    }

    @Test
    fun repliesWithStringsAreNotEchoed() {
        receive("\u001bP>|vtfx(0.1)\u001b\\a\u001b]11;rgb:0000/0000/0000\u0007b")
        assertEquals("ab", output.toString())
    }

    @Test
    fun replayShowsTheRecordingUntilAKey() {
        receive("replay demo\r")
        assertEquals("replay demo\r\n\u001b[1;24r\u001b[?1049hDEMO", output.toString())
        output.setLength(0)
        // Replies to the recording's queries do not end it.
        receive("\u001b[?62;22c")
        receive("\u001bP>|vtfx(0.1)\u001b\\")
        assertEquals("", output.toString())
        receive("q")
        assertEquals(
            "\u001b[?1049l\u001b[!p\u001b[?5;9;1000;1002;1003;1004;1005;1006;1015;2004;2026l" +
                "\u001b[0 q\r\n\u001b[32m$\u001b[0m ",
            output.toString(),
        )
    }

    @Test
    fun leavingAReplayRestoresTheModesThatTheRecordingSet() {
        lateinit var session: TerminalSession
        val modes =
            // As the recordings set them: vim's mouse, focus, paste, and keys, icat's reverse
            // video,
            // and a cursor style, autowrap, insert mode, keyboard flags, and synchronized output.
            "\u001b[?1049h\u001b[?1h\u001b=\u001b[?2004h\u001b[?1004h\u001b[?1002h\u001b[?1006h" +
                "\u001b[?5h\u001b[5 q\u001b[?12h\u001b[?7l\u001b[4h\u001b[>1u\u001b[?2026h"
        val replaying =
            EchoHost(
                output = { session.feed(it) },
                recording = { (modes + "VIM").toByteArray() },
                font = {},
            )
        session = TerminalSession({ replaying.receive(it) })
        replaying.start()
        replaying.receive("replay vim\r".toByteArray())
        val start = TerminalSession({}).snapshot().modes
        assertNotEquals(start, session.snapshot().modes)

        replaying.receive("q".toByteArray())

        assertEquals(start, session.snapshot().modes)
        assertFalse(session.snapshot().isAlternateScreen)
        // Autowrap, which the modes do not show: the line after the prompt wraps.
        session.feed("y".repeat(80).toByteArray())
        val snapshot = session.snapshot()
        assertTrue(snapshot.line(snapshot.cursor.row - 1).isWrapped)
    }

    @Test
    fun replayWaitsForTheTerminalToBeLargeEnough() {
        host.onResize(40, 20)
        receive("replay demo\r")
        assertEquals(listOf(80 to 24), requested)
        assert(!output.contains("DEMO")) { output }
        host.onResize(79, 30)
        assert(!output.contains("DEMO")) { output }
        host.onResize(81, 25)
        assert(output.endsWith("DEMO")) { output }
    }

    @Test
    fun unknownRecordingIsReported() {
        receive("replay nope\r")
        assert(output.contains("No recording 'nope'")) { output }
    }

    @Test
    fun utf8SplitAcrossWritesIsEchoedWhole() {
        host.receive(byteArrayOf(0xE3.toByte(), 0x81.toByte()))
        host.receive(byteArrayOf(0x82.toByte()))
        assertEquals("あ", output.toString())
    }

    @Test
    fun fontCommandGoesToTheApp() {
        receive("font import 2\r")

        assertEquals(listOf("import 2"), fontCommands)
    }

    @Test
    fun reportShowsAMessageAboveTheLineBeingEdited() {
        receive("ab")

        host.report("done")

        assertEquals("ab\r\u001b[Kdone\r\n\u001b[32m$\u001b[0m ab", output.toString())
    }
}
