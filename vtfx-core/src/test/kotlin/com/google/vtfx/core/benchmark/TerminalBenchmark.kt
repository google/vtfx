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

package com.google.vtfx.core.benchmark

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import java.io.File

/**
 * Measures how fast the terminal model interprets host output (docs/decisions/0089). Run with
 * `./gradlew :vtfx-core:benchmark`; not part of the check, since timing depends on the machine.
 *
 * Each workload is generated deterministically, written in chunks of 4 KiB as a host would deliver
 * it, for a warm-up and then a measured period. The result is MB/s of host output.
 */
object TerminalBenchmark {
    private const val CHUNK = 4096

    private class Workload(val name: String, val data: ByteArray)

    @JvmStatic
    fun main(args: Array<String>) {
        val recordings = args.getOrNull(0)?.let { File(it) }
        val seconds = args.getOrNull(1)?.toDouble() ?: 3.0
        // An optional name runs only that workload, for profiling.
        val only = args.getOrNull(2)
        val workloads =
            listOf(
                Workload("ascii", ascii()),
                Workload("sgr", colored()),
                Workload("cjk", cjk()),
            ) + listOfNotNull(recordings?.let { Workload("recordings", replay(it)) })
        println("workload\tMB/s\tscrollback MiB")
        for (workload in workloads.filter { only == null || it.name == only }) {
            val unused = run(workload, seconds / 3)
            val rate = run(workload, seconds)
            println(
                "${workload.name}\t${"%.1f".format(rate)}\t${"%.1f".format(scrollbackMebibytes(workload.data))}"
            )
        }
        if (only == null) {
            println("worst\t-\t${"%.1f".format(scrollbackMebibytes(worstCase()))}")
        }
    }

    /** The scrollback's memory, by estimate, after the workload filled it (default limits). */
    private fun scrollbackMebibytes(data: ByteArray): Double {
        val terminal = Terminal(120, 40)
        while (terminal.scrollbackSize < com.google.vtfx.core.TerminalLimits().scrollbackLines) {
            val before = terminal.scrollbackSize
            terminal.write(data)
            if (terminal.scrollbackSize == before) break
        }
        return terminal.estimatedScrollbackBytes() / 1048576.0
    }

    /** Full lines whose every cell has another color: nothing to compact. */
    private fun worstCase(): ByteArray = buildString {
        repeat(200) { row ->
            for (column in 0 until 120) append(
                "\u001b[38;5;${(row + column) % 256}m${'a' + column % 26}"
            )
            append("\r\n")
        }
    }
        .toByteArray()

    /** Writes the workload repeatedly for [seconds] and returns MB/s. */
    private fun run(workload: Workload, seconds: Double): Double {
        val terminal = Terminal(120, 40)
        val deadline = System.nanoTime() + (seconds * 1e9).toLong()
        val start = System.nanoTime()
        var bytes = 0L
        while (System.nanoTime() < deadline) {
            var offset = 0
            while (offset < workload.data.size) {
                val length = minOf(CHUNK, workload.data.size - offset)
                terminal.write(workload.data, offset, length)
                offset += length
            }
            bytes += workload.data.size
        }
        return bytes / ((System.nanoTime() - start) / 1e9) / 1e6
    }

    /** Lines of printable ASCII, as `cat` of a source file shows. */
    private fun ascii(): ByteArray = buildString {
        repeat(20_000) { i ->
            append(
                "${i.toString().padStart(6)}  fun example(value: Int): Int = value * $i + 42 // comment\r\n"
            )
        }
    }
        .toByteArray()

    /** Short runs of colored text, as `ls --color` or a compiler's output. */
    private fun colored(): ByteArray = buildString {
        repeat(20_000) { i ->
            append("\u001b[1;3${i % 8}mdir$i\u001b[0m  \u001b[38;5;${i % 256}mfile.txt\u001b[0m  ")
            append("\u001b[48;2;${i % 256};64;128m bg \u001b[49m \u001b[4mlink\u001b[24m\r\n")
        }
    }
        .toByteArray()

    /** Japanese text, which is wide and three bytes per character in UTF-8. */
    private fun cjk(): ByteArray = buildString {
        repeat(20_000) { i -> append("$i: 日本語のテキスト、全角の文字が二つのセルを占める。かなカナ漢字\r\n") }
    }
        .toByteArray()

    /** The recordings of real apps, concatenated. */
    private fun replay(directory: File): ByteArray =
        directory
            .listFiles { file -> file.name.endsWith(".out") }!!
            .sortedBy { it.name }
            .map { it.readBytes() }
            .reduce { a, b -> a + b }
}
