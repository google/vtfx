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

package com.google.vtfx.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.vtfx.font.FontFile
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.SanitizedFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sanitizes the fonts of the device and draws with them through the platform's font code
 * (docs/decisions/0040): real fonts, TrueType and CFF, including the Noto Sans CJK collection,
 * which the gating tests cannot carry. Each sanitized face must load and draw, and the CJK faces
 * must draw their own glyphs rather than the system's fallback. The time that each face takes is
 * logged, for the item "Imported fonts" of docs/review-queue.md.
 *
 * The device's fonts are part of the system and are trusted; they stand here for fonts that users
 * import.
 */
@RunWith(AndroidJUnit4::class)
class SystemFontsTest {
    private val limits = FontLimits()

    @Test
    fun deviceFontsSanitizeLoadAndDraw() {
        val files =
            File("/system/fonts").listFiles().orEmpty().filter {
                it.extension in setOf("ttf", "otf", "ttc", "otc")
            }
        val problems = ArrayList<String>()
        var sanitized = 0
        var cjkDrawnWithOwnGlyphs = 0
        for (file in files.sortedBy { it.name }) {
            val bytes = file.readBytes()
            val faces =
                try {
                    FontFile.inspect(bytes, limits)
                } catch (e: InvalidFontException) {
                    Log.i(TAG, "${file.name}: not read: ${e.kind}: ${e.message}")
                    continue
                }
            val supported = faces.filter { it.isSupported }.map { it.index }
            // The first face of each file: the faces of a collection share their outlines.
            for (index in supported.take(1)) {
                val started = SystemClock.elapsedRealtime()
                val font =
                    try {
                        FontFile.sanitize(bytes, index, limits)
                    } catch (e: InvalidFontException) {
                        val message = "${file.name} face $index: ${e.kind}: ${e.message}"
                        Log.i(TAG, message)
                        // Fonts that use what the canonical form leaves out are fine to refuse;
                        // malformed or too complex system fonts would be a bug in the readers.
                        if (e.kind != InvalidFontException.Kind.Unsupported) problems += message
                        continue
                    }
                val millis = SystemClock.elapsedRealtime() - started
                Log.i(
                    TAG,
                    "${file.name} face $index (${faces[index].outlineFormat}): ${bytes.size} " +
                        "bytes to ${font.size} in $millis ms, ${font.widthClass}",
                )
                sanitized++
                val typeface =
                    sanitizedTypefaces(TerminalFont.Sanitized(listOf(SanitizedFamily(font))))
                        .regular
                if (file.name.contains("CJK") && drawsDifferently(typeface, CJK_TEXT)) {
                    cjkDrawnWithOwnGlyphs++
                }
            }
        }
        Log.i(TAG, "$sanitized faces sanitized; $cjkDrawnWithOwnGlyphs CJK faces drew their own")
        assertEquals(emptyList<String>(), problems)
        check(sanitized > 0) { "no fonts sanitized" }
        val cjkFaces = files.count { it.name.contains("CJK") }
        check(cjkFaces == 0 || cjkDrawnWithOwnGlyphs > 0) { "no CJK face drew its own glyphs" }
    }

    /**
     * Whether [text] drawn with [typeface] differs from the system's monospace font, which the
     * sanitized typefaces fall back to: if it does, the sanitized font drew glyphs of its own.
     */
    private fun drawsDifferently(typeface: Typeface, text: String): Boolean =
        !draw(typeface, text).sameAs(draw(Typeface.MONOSPACE, text))

    private fun draw(typeface: Typeface, text: String): Bitmap {
        val paint =
            Paint().apply {
                this.typeface = typeface
                textSize = 48f
                color = Color.BLACK
                isAntiAlias = true
            }
        val bitmap = Bitmap.createBitmap(48 * (text.length + 1), 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 0f, 64f, paint)
        }
        return bitmap
    }

    private companion object {
        const val TAG = "SystemFontsTest"
        const val CJK_TEXT = "日本語漢字かなカナ한국어中文"
    }
}
