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

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SampleViewModelTest {
    private val application = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private val viewModel =
        SampleViewModel(application).also { store.put(SampleViewModel::class.java.name, it) }

    private val uri = Uri.parse("content://fonts/font.ttf")

    init {
        val font =
            checkNotNull(javaClass.getResourceAsStream(FONT)) { "missing $FONT" }
                .use { it.readBytes() }
        shadowOf(application.contentResolver).registerInputStream(uri, ByteArrayInputStream(font))
    }

    @After
    fun tearDown() {
        // Clears the view model, which stops its font thread.
        store.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main thread until [done], while the font thread works. */
    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
            idle()
        }
    }

    private fun fontImport(): SampleViewModel.FontRequest {
        viewModel.session.sendText("font import\r")
        idle()
        return checkNotNull(viewModel.fontRequest) { "no request" }
    }

    @Test
    fun aFontRequestIsDoneWhenTheFileIsImported() {
        val request = fontImport()

        viewModel.importFont(request, uri)
        idleUntil { viewModel.fonts.isNotEmpty() }

        assertEquals(1, viewModel.fonts.size)
        assertNull(viewModel.fontRequest)
    }

    @Test
    fun aFontRequestWhosePickerIsShownIsNotShownAgain() {
        val request = fontImport()

        viewModel.onFontPickerShown(request)

        // An activity recreated now, on a rotation for example, shows no picker.
        assertNull(viewModel.fontRequest)
    }

    @Test
    fun theFilePickedIsImportedForTheRequestWhosePickerWasShown() {
        viewModel.onFontPickerShown(fontImport())

        // The result may come to an activity recreated meanwhile.
        viewModel.onFontPicked(uri)
        idleUntil { viewModel.fonts.isNotEmpty() }

        assertEquals(1, viewModel.fonts.size)
    }

    @Test
    fun aPickerThatReturnsNoFileImportsNothing() {
        viewModel.onFontPickerShown(fontImport())

        viewModel.onFontPicked(null)
        // A result without a picker shown, which then has no request.
        viewModel.onFontPicked(uri)
        idle()

        assertNull(viewModel.fontRequest)
        assertEquals(emptyList<Any>(), viewModel.fonts)
    }

    private companion object {
        const val FONT = "/com/google/vtfx/font/gate/canonical/variations.ttf"
    }
}
