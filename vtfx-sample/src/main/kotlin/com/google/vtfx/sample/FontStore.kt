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

import com.google.vtfx.font.FontFile
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.SanitizedFont
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The fonts that the user imported, sanitized and saved in [directory], as vtfx expects apps to
 * keep them: a font file is sanitized once, when it is imported, and only the sanitized font is
 * saved, to be checked again when it is loaded (section 3 of docs/design/fonts.md).
 *
 * Its functions block, taking seconds to sanitize a CJK font: call them on one background thread.
 * Not thread-safe.
 */
class FontStore(private val directory: File, private val limits: FontLimits) {
    /** The saved fonts, in the order of import. */
    private var saved: List<SanitizedFont> = emptyList()

    /** What importing a font file gave. */
    sealed interface ImportResult {
        /** The file was imported: [fonts] are the saved fonts now, and [report] says how. */
        class Imported(val fonts: List<SanitizedFont>, val report: String) : ImportResult

        /** The file was not imported, for the reason that [message] gives. */
        class Failed(val message: String) : ImportResult
    }

    /** Loads the fonts saved before, checking them again, and returns them. */
    fun load(): List<SanitizedFont> {
        val files = directory.listFiles().orEmpty().sortedBy { it.name }
        saved = files.map { SanitizedFont.fromCanonical(it.readBytes(), limits) }
        return saved
    }

    /**
     * Imports face [face] of the font file that [open] opens, or the first face that vtfx reads if
     * [face] is null, adding it to the saved fonts.
     */
    fun import(open: () -> InputStream?, face: Int?): ImportResult =
        try {
            val bytes = checkNotNull(open()) { "cannot open the file" }.use { it.readBytes() }
            val faces = FontFile.inspect(bytes, limits)
            val chosen =
                face
                    ?: faces.firstOrNull { it.isSupported }?.index
                    ?: throw IllegalArgumentException("no face that vtfx reads")
            val started = System.nanoTime()
            val font = FontFile.sanitize(bytes, chosen, limits)
            val millis = (System.nanoTime() - started) / 1_000_000
            val report =
                faces.joinToString("\n", postfix = "\n") { info ->
                    val mark = if (info.index == chosen) "*" else " "
                    "$mark ${info.index}: ${info.familyName} ${info.styleName} " +
                        "(${info.outlineFormat})"
                } + "Imported face $chosen in $millis ms: ${bytes.size} bytes to ${font.size}.\n"
            save(saved + font)
            ImportResult.Imported(saved, report)
        } catch (e: InvalidFontException) {
            ImportResult.Failed("Not imported: ${e.kind}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ImportResult.Failed("Not imported: ${e.message}")
        } catch (e: IOException) {
            ImportResult.Failed("Not imported: $e")
        }

    /** Saves [fonts] in place of the saved fonts. */
    fun save(fonts: List<SanitizedFont>) {
        directory.deleteRecursively()
        directory.mkdirs()
        for ((i, font) in fonts.withIndex()) {
            File(directory, "%03d.ttf".format(i)).writeBytes(font.toByteArray())
        }
        saved = fonts
    }
}
