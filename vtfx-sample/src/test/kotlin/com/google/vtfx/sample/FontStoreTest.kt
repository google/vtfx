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

import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.SanitizedFont
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val limits = FontLimits()

    private val directory: File by lazy { File(folder.root, "fonts") }

    /** A valid font file: a canonical font, which sanitizes to itself. */
    private val font: ByteArray =
        checkNotNull(javaClass.getResourceAsStream(FONT)) { "missing $FONT" }.use { it.readBytes() }

    private fun store() = FontStore(directory, limits)

    @Test
    fun importedFontIsSavedAndLoadedAgain() {
        val result = store().import({ ByteArrayInputStream(font) }, null)

        val imported = (result as FontStore.ImportResult.Imported).fonts
        assertEquals(listOf(SanitizedFont.fromCanonical(font, limits)), imported)
        assertEquals(imported, store().load().fonts)
    }

    @Test
    fun fileThatIsNotAFontIsNotImported() {
        val result = store().import({ ByteArrayInputStream(ByteArray(100)) }, null)

        assertTrue((result as FontStore.ImportResult.Failed).message.startsWith("Not imported"))
        assertEquals(emptyList<SanitizedFont>(), store().load().fonts)
    }

    @Test
    fun fileLargerThanTheInputLimitIsNotReadBeyondIt() {
        var read = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int = 0.also { read++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { read += it }
            }
        val store = FontStore(directory, FontLimits(maxInputBytes = 1000))

        val result = store.import({ endless }, null)

        assertTrue((result as FontStore.ImportResult.Failed).message.contains("TooLarge"))
        assertTrue("read $read bytes", read <= 1001)
    }

    @Test
    fun fileThatCannotBeOpenedIsNotImported() {
        val result = store().import({ null }, null)

        assertTrue(result is FontStore.ImportResult.Failed)
    }

    @Test
    fun fileWhosePermissionWasRevokedIsNotImported() {
        val result = store().import({ throw SecurityException("revoked") }, null)

        assertTrue(result is FontStore.ImportResult.Failed)
    }

    @Test
    fun importOnAnInterruptedThreadIsCancelledAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt()

        val result = store().import({ ByteArrayInputStream(font) }, null)

        assertTrue(Thread.interrupted())
        assertTrue(result is FontStore.ImportResult.Cancelled)
        assertEquals(emptyList<SanitizedFont>(), store().load().fonts)
    }

    @Test
    fun savedFontThatFailsTheCheckIsLeftOutAndReported() {
        assertTrue(
            store().import({ ByteArrayInputStream(font) }, null) is FontStore.ImportResult.Imported
        )
        File(directory, "001.ttf").writeBytes(font.copyOf(font.size - 1))

        val loaded = store().load()

        assertEquals(listOf(SanitizedFont.fromCanonical(font, limits)), loaded.fonts)
        assertEquals(1, loaded.problems.size)
        assertTrue(loaded.problems.single().contains("001.ttf"))
    }

    private companion object {
        const val FONT = "/com/google/vtfx/font/gate/canonical/variations.ttf"
    }
}
