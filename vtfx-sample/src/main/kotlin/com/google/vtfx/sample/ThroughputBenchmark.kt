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

import android.util.Log
import com.google.vtfx.session.TerminalSession
import java.io.File
import java.util.Locale

/**
 * Measures on the device how fast a session interprets host output, with the workloads of the JVM's
 * `TerminalBenchmark` in vtfx-core (docs/decisions/0089). Started with the "throughput" extra; the
 * results are logged with [TAG] for scripts/benchmark.py.
 *
 * The workloads are the files `<workload>.bin` in [directory], which `./gradlew
 * :vtfx-core:benchmarkWorkloads` writes and scripts/benchmark.py pushes over adb, so that the
 * device measures the same bytes as the JVM. Each is fed in writes of 4 KiB into a session of 120 ×
 * 40 that no display shows, for a warm-up and then [seconds], as `TerminalBenchmark` does.
 */
class ThroughputBenchmark(
    private val directory: File,
    /** The only workload to measure, for profiling, or null for all of them. */
    private val workload: String?,
    private val seconds: Double,
) {
    fun start() {
        Thread(::run, "vtfx-benchmark-throughput").start()
    }

    private fun run() {
        // Made by the app, so that it can read the files that adb pushes into it: it cannot see
        // those
        // in a directory that adb made, which belongs to the shell.
        directory.mkdirs()
        val files =
            directory
                .listFiles { file -> file.name.endsWith(SUFFIX) }
                .orEmpty()
                .filter { workload == null || it.name == workload + SUFFIX }
                .sortedBy { it.name }
        if (files.isEmpty()) Log.w(TAG, "No workloads in $directory")
        for (file in files) {
            val data = file.readBytes()
            val unused = measure(data, seconds / 3)
            val rate = measure(data, seconds)
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "{\"workload\":\"%s\",\"mbps\":%.1f,\"seconds\":%.1f}",
                    file.name.removeSuffix(SUFFIX),
                    rate,
                    seconds,
                ),
            )
        }
        Log.i(TAG, "{\"done\":true}")
    }

    /** Feeds [data] over and over for [seconds] and returns MB/s. */
    private fun measure(data: ByteArray, seconds: Double): Double {
        val session = TerminalSession(connection = {})
        session.resize(COLUMNS, ROWS)
        val start = System.nanoTime()
        val deadline = start + (seconds * 1e9).toLong()
        var bytes = 0L
        while (System.nanoTime() < deadline) {
            var offset = 0
            while (offset < data.size) {
                val length = minOf(CHUNK, data.size - offset)
                session.feed(data, offset, length)
                offset += length
            }
            bytes += data.size
        }
        return bytes / ((System.nanoTime() - start) / 1e9) / 1e6
    }

    companion object {
        const val TAG = "VtfxThroughput"

        /** The directory under the app's external files directory that holds the workloads. */
        const val DIRECTORY = "workloads"

        private const val SUFFIX = ".bin"
        private const val CHUNK = 4096
        private const val COLUMNS = 120
        private const val ROWS = 40
    }
}
