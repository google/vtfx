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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.google.vtfx.session.TerminalSession
import java.util.Locale

/**
 * Measures how long frames take while the terminal shows full-screen updates, as htop or a
 * scrolling build log does (docs/decisions/0014). Started with the "benchmark" extra; the result is
 * logged with [TAG] for scripts/benchmark.py.
 */
class RenderBenchmark(private val activity: Activity, private val session: TerminalSession) {
    private val handler = Handler(Looper.getMainLooper())
    private val frames = mutableListOf<Long>()
    private val draws = mutableListOf<Long>()
    private val commands = mutableListOf<Long>()
    private var updates = 0

    /** The scenario: full-screen updates first, then a log that scrolls by a line per update. */
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
        if (updates == 0) {
            frames.clear()
            draws.clear()
            commands.clear()
        }
        if (updates == UPDATES) {
            finish()
            if (scenario == "full") {
                scenario = "scroll"
                updates = 0
                session.feed("\u001b[2J\u001b[H".toByteArray())
                activity.window.addOnFrameMetricsAvailableListener(listener, handler)
                handler.postDelayed(::update, WARM_UP_MILLIS)
            }
            return
        }
        session.feed((if (scenario == "full") screen(updates) else logLine(updates)).toByteArray())
        updates++
        handler.postDelayed(::update, INTERVAL_MILLIS)
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

    private fun finish() {
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
                "\"p99\":${percentile(frames, 0.99)},\"drawP50\":${percentile(draws, 0.5)}," +
                "\"commandP50\":${percentile(commands, 0.5)}}",
        )
    }

    companion object {
        const val TAG = "VtfxRenderBenchmark"
        private const val UPDATES = 300
        private const val INTERVAL_MILLIS = 16L
        private const val WARM_UP_MILLIS = 2000L
    }
}
