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

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import com.google.vtfx.gate.OpenUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidCapabilitiesTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Test
    fun clipboardWritesPlainTextThatThePreviewShows() {
        val clipboard = AndroidClipboard(application)
        clipboard.write("secret")
        val clip = application.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("secret", clip.getItemAt(0).text)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        // Not marked sensitive, so that the clipboard preview shows what the host or the user
        // copied (docs/decisions/0006).
        assertFalse(
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
        )
        assertEquals("secret", clipboard.read())
    }

    @Test
    fun clipboardWithoutTextReadsNull() {
        assertNull(AndroidClipboard(application).read())
    }

    @Test
    fun urlsOpenOnlyBrowsableActivities() {
        AndroidUrlOpener(application).open("https://example.com/a")
        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com/a"), intent.data)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun urlsOpenWithTheSchemeInLowerCase() {
        // The gate allows schemes in any case, but intent filters match them case-sensitively, so
        // "HTTPS" would reach no browser, or an app that registered "HTTPS" as its own scheme.
        AndroidUrlOpener(application).open("HTTPS://Example.com/A")
        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Uri.parse("https://Example.com/A"), intent.data)
    }

    @Test
    fun openingAUrlThatAnAppTakesReturnsOpened() {
        // Robolectric starts every activity unless asked to check that one takes the intent.
        val opener = AndroidUrlOpener(application)

        val result = opener.open("https://example.com/a")

        assertEquals(OpenUrlResult.Opened, result)
    }

    @Test
    fun openingAUrlThatNoAppTakesReturnsNoApp() {
        shadowOf(application).checkActivities(true)
        val opener = AndroidUrlOpener(application)

        val result = opener.open("https://example.com/a")

        assertEquals(OpenUrlResult.NoApp, result)
    }

    @Test
    fun openingAUrlThatTheSystemRefusesReturnsRefused() {
        val opener = AndroidUrlOpener(PermissionDeniedContext(application))

        val result = opener.open("https://example.com/a")

        assertEquals(OpenUrlResult.Refused, result)
    }
}
