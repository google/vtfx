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

package com.google.vtfx.gate.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.vtfx.gate.OpenUrlResult
import com.google.vtfx.gate.UrlOpener
import com.google.vtfx.gate.UrlOpenerResult

/**
 * Opens URLs in a browser. vtfx-gate checks the URL before calling this; this class only performs
 * it. Thread-safe.
 *
 * @param context The context to start the browser from. Without an activity, the browser starts in
 *   a new task.
 */
public class AndroidUrlOpener(private val context: Context) : UrlOpener {
    /**
     * Opens [url] with ACTION_VIEW. CATEGORY_BROWSABLE restricts the target to activities that
     * accept links from a browser, so that a URL cannot start an app's internal activity. The
     * scheme is put in lower case, as the gate compares it, since Android matches schemes
     * case-sensitively.
     *
     * Returns [OpenUrlResult.NoApp] if no app opens it, and [OpenUrlResult.Refused] if the system
     * refuses to start the app that does, such as one whose activity requires a permission. Other
     * exceptions from the system propagate.
     */
    override fun open(url: String): UrlOpenerResult {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // Only the failures that startActivity documents become results; anything else is a
        // programming error and propagates (docs/decisions/0083).
        return try {
            context.startActivity(intent)
            OpenUrlResult.Opened
        } catch (_: ActivityNotFoundException) {
            OpenUrlResult.NoApp
        } catch (_: SecurityException) {
            OpenUrlResult.Refused
        }
    }
}
