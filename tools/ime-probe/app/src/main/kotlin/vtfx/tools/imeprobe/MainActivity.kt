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

package vtfx.tools.imeprobe

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val recentEvents = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = ProbeConfig.fromIntent(intent)

        val header = TextView(this).apply { text = config.toJson().toString() }
        val events = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
        }
        val log = ProbeLog { line ->
            recentEvents.addLast(line)
            while (recentEvents.size > 12) {
                recentEvents.removeFirst()
            }
            events.text = recentEvents.joinToString("\n")
        }
        log.event("config") { put("config", config.toJson()) }

        val probe = if (config.useEditText) ProbeEditText(this, config, log) else ProbeView(this, config, log)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(header)
            addView(probe, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(events)
        }
        setContentView(root)
        probe.requestFocus()
    }
}
