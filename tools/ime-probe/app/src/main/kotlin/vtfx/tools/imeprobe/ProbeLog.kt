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

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Writes probe events as JSON lines to logcat under [TAG], so that a run can be captured with
 * `adb logcat -s VtfxImeProbe -v raw`.
 */
class ProbeLog(private val onEvent: (String) -> Unit) {
    private val startMs = SystemClock.uptimeMillis()
    private var seq = 0

    fun event(type: String, fill: JSONObject.() -> Unit = {}) {
        val json = JSONObject()
            .put("seq", seq++)
            .put("t", SystemClock.uptimeMillis() - startMs)
            .put("ev", type)
        json.fill()
        val line = json.toString()
        Log.i(TAG, line)
        onEvent(line)
    }

    companion object {
        const val TAG = "VtfxImeProbe"
    }
}
