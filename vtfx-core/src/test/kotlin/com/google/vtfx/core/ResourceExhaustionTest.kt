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

import java.lang.management.ManagementFactory
import java.time.Duration
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Resource exhaustion by host output, an attack class of requirement 6.1, with the limits of
 * docs/decisions/0002: hostile output must take time linear in its length, with work per byte
 * bounded by the screen size, and memory within the limits.
 *
 * Each case repeats a hostile sequence up to [INPUT_BYTES]. The limit on the CPU time is far above
 * what the cases take (a few seconds at most on a development machine), so that it only catches
 * work that grows faster than the input, such as a scan of the scrollback per sequence. CPU time,
 * unlike elapsed time, does not grow when other processes compete for the CPUs, as fuzzers do.
 */
class ResourceExhaustionTest {
    class Case(val name: String, val unit: (Int) -> String) {
        override fun toString(): String = name
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun hostileOutputIsBounded(case: Case) {
        val terminal = Terminal(COLUMNS, ROWS, TerminalLimits(scrollbackLines = SCROLLBACK_LINES))
        val input = buildString {
            var i = 0
            while (length < INPUT_BYTES) append(case.unit(i++))
        }
            .toByteArray(Charsets.UTF_8)
        val cpuNanos =
            assertTimeoutPreemptively(
                Duration.ofSeconds(HANG_LIMIT_SECONDS),
                ThrowingSupplier {
                    val threads = ManagementFactory.getThreadMXBean()
                    val start = threads.currentThreadCpuTime
                    // In chunks, as a connection delivers it, so that state carries across writes.
                    for (offset in input.indices step CHUNK) {
                        terminal.write(input, offset, minOf(CHUNK, input.size - offset))
                    }
                    threads.currentThreadCpuTime - start
                },
            )
        assertTrue(
            cpuNanos <= CPU_TIME_LIMIT_SECONDS * 1_000_000_000L,
            "${cpuNanos / 1_000_000} ms of CPU time",
        )
        assertTrue(terminal.scrollbackSize <= SCROLLBACK_LINES)
        // The scrollback's budget, and the newest line, which is kept even if over it.
        val bound =
            SCROLLBACK_LINES * (Scrollback.BYTES_PER_LINE + COLUMNS * Scrollback.BYTES_PER_CELL) +
                COLUMNS * MAX_BYTES_PER_CELL
        assertTrue(
            terminal.estimatedScrollbackBytes() <= bound,
            "scrollback ${terminal.estimatedScrollbackBytes()} bytes",
        )
        assertTrue(
            terminal.imageBytes <= TerminalLimits().maxImageBytes,
            "images ${terminal.imageBytes} bytes",
        )
        assertTrue(terminal.graphicsLayer.placementCount <= 1024)
        assertTrue(terminal.graphicsLayer.imageCount <= 1024)
        val snapshot = terminal.snapshot()
        for (row in 0 until snapshot.rows) {
            for (column in 0 until snapshot.columns) {
                val text = snapshot.line(row).textAt(column) ?: continue
                assertTrue(text.length <= Line.MAX_CLUSTER_LENGTH)
            }
        }
    }

    companion object {
        private const val COLUMNS = 80
        private const val ROWS = 24
        private const val SCROLLBACK_LINES = 1000
        private const val INPUT_BYTES = 1 shl 20
        private const val CHUNK = 4096
        private const val CPU_TIME_LIMIT_SECONDS = 20L

        /** Catches a hang, which the CPU time limit cannot interrupt. */
        private const val HANG_LIMIT_SECONDS = 120L

        /**
         * A code point, a style, and a link per cell, and the cluster of a cell with combining
         * characters, generously.
         */
        private const val MAX_BYTES_PER_CELL = 4 + 8 + 4 + 2 * Line.MAX_CLUSTER_LENGTH + 64

        /** Zlib data of 3 MB of zeros: an image of 1000 by 1000 RGB pixels in 3 KB. */
        private val BOMB: String by lazy {
            val deflater = java.util.zip.Deflater(9)
            deflater.setInput(ByteArray(3_000_000))
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        }

        @JvmStatic
        fun cases(): List<Case> =
            listOf(
                Case("repeat") { "x\u001b[65535b" },
                Case("forward tab") { "\u001b[65535I" },
                Case("backward tab") { "\u001b[65535Z" },
                Case("insert and delete lines") { "\u001b[65535L\u001b[65535M" },
                Case("insert and delete characters") { "\u001b[65535@\u001b[65535P\u001b[65535X" },
                Case("scroll") { "\u001b[65535S\u001b[65535T" },
                Case("scroll region") { "\u001b[2;23r\u001b[65535S\u001b[r" },
                Case("erase display") { "\u001b[2J\u001b[3J" },
                Case("alignment pattern") { "\u001b#8" },
                Case("alternate screen") { "\u001b[?1049h\u001b[?1049l" },
                Case("cursor movement") { "\u001b[65535;65535H\u001b[65535A\u001b[65535D" },
                Case("many parameters") { "\u001b[" + "1;".repeat(1000) + "m" },
                Case("huge parameters") { "\u001b[" + "9".repeat(1000) + "m" },
                Case("long string") { "\u001b]0;" + "a".repeat(1 shl 18) },
                Case("unterminated device control string") { "\u001bP1\$q" + "a".repeat(1 shl 16) },
                Case("combining characters") { "e" + "\u0301".repeat(1000) },
                Case("long lines") { "x".repeat(10_000) },
                Case("wide characters at the margin") { "\u001b[80Gあ" },
                Case("hyperlinks per cell") { "\u001b]8;;https://example.com/$it\u0007x" },
                Case("unused hyperlinks") { "\u001b]8;;https://example.com/$it\u0007" },
                Case("styles per cell") {
                    "\u001b[38;2;${it % 256};${it / 256 % 256};1m\u001b[48;5;${it % 256}mx"
                },
                Case("queries") { "\u001b[6n\u001b[c\u001b]10;?\u0007" },
                // Kitty graphics (docs/decisions/0038).
                Case("images") { "\u001b_Ga=t,i=${it + 1},f=24,s=1,v=1,q=2;AAAA\u001b\\" },
                Case("images without ids") { "\u001b_Ga=T,f=24,s=1,v=1,c=1,r=1,q=2;AAAA\u001b\\" },
                Case("large images") {
                    "\u001b_Ga=t,i=${it + 1},f=24,s=1000,v=1000,o=z,q=2;$BOMB\u001b\\"
                },
                Case("unterminated transmission") {
                    if (it == 0) "\u001b_Ga=t,i=1,f=100,m=1;"
                    else "\u001b_Gm=1;${"A".repeat(4096)}\u001b\\"
                },
                Case("placements") { "\u001b_Ga=T,i=1,f=24,s=1,v=1,q=2;AAAA\u001b\\\n" },
                Case("deletions by position") {
                    if (it == 0) "\u001b_Ga=t,i=1,f=24,s=1,v=1,q=2;AAAA\u001b\\"
                    else if (it <= 1100) "\u001b_Ga=p,i=1,C=1,q=2\u001b\\"
                    else "\u001b_Ga=d,d=p,x=9,y=9\u001b\\"
                },
                Case("clearing the screen with placements") {
                    if (it == 0) "\u001b_Ga=t,i=1,f=24,s=1,v=1,q=2;AAAA\u001b\\"
                    else "\u001b_Ga=p,i=1,C=1,q=2\u001b\\\u001b[2J"
                },
                Case("placements in a scrolling region") {
                    if (it == 0) "\u001b_Ga=t,i=1,f=24,s=1,v=1,q=2;AAAA\u001b\\\u001b[2;23r"
                    else if (it <= 1100) "\u001b_Ga=p,i=1,r=1,c=1,q=2\u001b\\" else "\n"
                },
            )
    }
}
