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

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.google.vtfx.session.TerminalSession
import java.util.Locale

/**
 * Measures how long frames take while the terminal shows full-screen updates, as htop or a
 * scrolling build log does, and while the host prints output without a break, as `cat` of a large
 * file does (docs/decisions/0014, docs/decisions/0066). Started with the "benchmark" extra; the
 * results are logged with [TAG] for scripts/benchmark.py.
 */
class RenderBenchmark(private val activity: Activity, private val session: TerminalSession) {
    private val handler = Handler(Looper.getMainLooper())
    private val frames = mutableListOf<Long>()
    private val draws = mutableListOf<Long>()
    private val commands = mutableListOf<Long>()
    private var updates = 0

    /**
     * The scenario: full-screen updates first, then a log that scrolls by a line per update, then a
     * flood of output.
     */
    private var scenario = "full"

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        // Recording the drawing on the UI thread, and issuing it to the GPU on the render thread.
        draws += metrics.getMetric(FrameMetrics.DRAW_DURATION)
        commands += metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
    }

    fun start() {
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.postDelayed(::update, WARM_UP_MILLIS)
    }

    private fun update() {
        if (updates == 0) clearMetrics()
        if (updates == UPDATES) {
            finish()
            when (scenario) {
                "full" -> {
                    scenario = "scroll"
                    updates = 0
                    session.feed("\u001b[2J\u001b[H".toByteArray())
                    start()
                }
                "scroll" -> handler.postDelayed(::flood, WARM_UP_MILLIS)
            }
            return
        }
        session.feed((if (scenario == "full") screen(updates) else logLine(updates)).toByteArray())
        updates++
        handler.postDelayed(::update, INTERVAL_MILLIS)
    }

    /**
     * Feeds colored log lines from another thread without a break for [FLOOD_MILLIS], as an app's
     * reader thread does while the host prints a large file, and measures the frames meanwhile.
     * First feeds a session without a display for as long, on another thread too, so that the
     * throughput with the display can be compared with what the device does without one.
     */
    private fun flood() {
        scenario = "flood"
        val data = buildString {
            var line = 0
            while (length < FLOOD_DATA_BYTES) append(logLine(line++))
        }
            .toByteArray()
        val shown = session.snapshot()
        val headless = TerminalSession(connection = {})
        headless.resize(shown.columns, shown.rows)
        Thread(
                {
                    val headlessBytes = feedFor(headless, data)
                    handler.post {
                        clearMetrics()
                        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
                        Thread(
                                {
                                    val bytes = feedFor(session, data)
                                    handler.post { finishFlood(bytes, headlessBytes) }
                                },
                                "vtfx-benchmark-flood",
                            )
                            .start()
                    }
                },
                "vtfx-benchmark-headless",
            )
            .start()
    }

    /** Feeds [data] to [target] over and over for [FLOOD_MILLIS]. Returns the bytes fed. */
    private fun feedFor(target: TerminalSession, data: ByteArray): Long {
        val end = System.nanoTime() + FLOOD_MILLIS * 1_000_000
        var bytes = 0L
        var offset = 0
        while (System.nanoTime() < end) {
            val length = minOf(FLOOD_CHUNK, data.size - offset)
            target.feed(data, offset, length)
            bytes += length
            offset = (offset + length) % data.size
        }
        return bytes
    }

    private fun finishFlood(bytes: Long, headlessBytes: Long) {
        // Every frame of the flood shows new rows, so a frame is due at every vsync: fewer frames
        // per second than the refresh rate are vsyncs without a frame, and a frame longer than the
        // refresh interval missed its vsync.
        val interval = 1_000_000_000 / refreshRate()
        val slow = frames.count { it > interval }
        val janky = if (frames.isEmpty()) 0.0 else 100.0 * slow / frames.size
        finish(
            String.format(
                Locale.ROOT,
                ",\"refreshRate\":%.0f,\"fps\":%.1f,\"janky\":%.1f,\"mbps\":%.1f,\"headlessMbps\":%.1f",
                refreshRate(),
                frames.size * 1000.0 / FLOOD_MILLIS,
                janky,
                bytes / 1e3 / FLOOD_MILLIS,
                headlessBytes / 1e3 / FLOOD_MILLIS,
            )
        )
    }

    private fun refreshRate(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display!!.refreshRate
        } else {
            @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.refreshRate
        }

    private fun clearMetrics() {
        frames.clear()
        draws.clear()
        commands.clear()
    }

    /** One line of a colored log, which scrolls the screen once it is full. */
    private fun logLine(index: Int): String =
        "\r\n\u001b[32m2026-10-12 12:00:${index % 60}\u001b[0m \u001b[1mINFO\u001b[0m request $index took ${index % 97} ms"

    /** A full screen of colored text that differs from the previous one. */
    private fun screen(index: Int): String = buildString {
        append("\u001b[H")
        val (columns, rows) = 120 to 50
        for (row in 0 until rows) {
            append("\u001b[${row + 1};1H")
            for (column in 0 until columns step 8) {
                append("\u001b[38;5;${(index + row + column) % 256}m")
                append("%-8s".format("v${(index * 7 + row * 3 + column) % 100000}"))
            }
        }
        append("\u001b[0m")
    }

    /** Logs the results of the scenario, with [extra] fields appended to the JSON object. */
    private fun finish(extra: String = "") {
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        fun percentile(values: List<Long>, p: Double): String {
            val sorted = values.map { it / 1_000_000.0 }.sorted()
            // The root locale, so that the JSON has a decimal point.
            return String.format(
                Locale.ROOT,
                "%.2f",
                if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()],
            )
        }
        Log.i(
            TAG,
            "{\"scenario\":\"$scenario\",\"frames\":${frames.size},\"p50\":${percentile(frames, 0.5)},\"p90\":${percentile(frames, 0.9)}," +
                "\"p99\":${percentile(frames, 0.99)},\"drawP50\":${percentile(draws, 0.5)},\"drawP90\":${percentile(draws, 0.9)}," +
                "\"commandP50\":${percentile(commands, 0.5)}$extra}",
        )
    }

    companion object {
        const val TAG = "VtfxRenderBenchmark"
        private const val UPDATES = 300
        private const val INTERVAL_MILLIS = 16L
        private const val WARM_UP_MILLIS = 2000L

        /** How long the flood, and the feeding of the session without a display, each last. */
        private const val FLOOD_MILLIS = 5000L

        /** The bytes of each `feed` of the flood, a common size of a read from a socket. */
        private const val FLOOD_CHUNK = 16 * 1024

        /** The log lines fed over and over. */
        private const val FLOOD_DATA_BYTES = 1024 * 1024
    }
}
