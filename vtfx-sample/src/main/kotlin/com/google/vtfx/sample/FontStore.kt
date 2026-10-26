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

import com.google.vtfx.compose.TerminalFont
import com.google.vtfx.font.FontFile
import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.SanitizedFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * The fonts that the user imported, sanitized and saved in [directory], as vtfx expects apps to
 * keep them: a font file is sanitized once, when it is imported, and only the sanitized font is
 * saved, to be checked again when it is loaded (section 3 of docs/design/fonts.md). It keeps at
 * most the families that [TerminalFont.Sanitized] takes, so that the app can always make its font
 * of them.
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

        /** The thread was interrupted, and the file was not imported. */
        data object Cancelled : ImportResult
    }

    /**
     * The saved fonts that passed the check again, and a message for each one that did not, which
     * is left out: files in storage can be damaged, and the checks of a newer vtfx can be stricter.
     */
    class Loaded(val fonts: List<SanitizedFont>, val problems: List<String>)

    /**
     * Loads the fonts saved before, checking them again, at most [TerminalFont.Sanitized]'s
     * families. The files of the fonts left out are deleted at the next save.
     */
    fun load(): Loaded {
        val files = directory.listFiles().orEmpty().sortedBy { it.name }
        val fonts = mutableListOf<SanitizedFont>()
        val problems = mutableListOf<String>()
        for (file in files) {
            if (fonts.size == MAX_FONTS) {
                problems += "Saved font ${file.name} not loaded: more than $MAX_FONTS fonts"
                continue
            }
            try {
                val bytes = file.inputStream().use { readAtMost(it, limits.maxOutputBytes) }
                fonts += SanitizedFont.fromCanonical(bytes, limits)
            } catch (e: InvalidFontException) {
                problems += "Saved font ${file.name} not loaded: ${e.kind}: ${e.message}"
            } catch (e: IOException) {
                problems += "Saved font ${file.name} not loaded: $e"
            }
        }
        saved = fonts
        return Loaded(fonts, problems)
    }

    /**
     * Imports face [face] of the font file that [open] opens, or the first face that vtfx reads if
     * [face] is null, adding it to the saved fonts, unless they are already as many as
     * [TerminalFont.Sanitized] takes. Every failure, of the file, of reading it, or of an
     * interrupt, is a result: an exception on the font thread would end the app.
     */
    fun import(open: () -> InputStream?, face: Int?): ImportResult {
        if (saved.size >= MAX_FONTS) {
            return ImportResult.Failed("Not imported: already $MAX_FONTS fonts, the most kept")
        }
        return try {
            val input = open() ?: throw FileNotFoundException("cannot open the file")
            val bytes = input.use { readAtMost(it, limits.maxInputBytes) }
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
        } catch (e: SecurityException) {
            // The provider of the file no longer lets the app read it.
            ImportResult.Failed("Not imported: $e")
        } catch (_: InterruptedException) {
            // FontFile.sanitize clears the interrupt when it stops; set it again for the caller.
            Thread.currentThread().interrupt()
            ImportResult.Cancelled
        }
    }

    /**
     * Reads [input] to its end, failing as too large once it holds more than [limit] bytes, so that
     * a huge or endless file takes no more memory than vtfx would accept.
     */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            // One byte more than the limit tells a file over it from one that just fits.
            val wanted = minOf(buffer.size.toLong(), limit + 1L - out.size()).toInt()
            val count = input.read(buffer, 0, wanted)
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
            if (out.size() > limit) {
                throw InvalidFontException(
                    InvalidFontException.Kind.TooLarge,
                    "file of more than $limit bytes",
                )
            }
        }
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

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** The most fonts kept: the families that the app's [TerminalFont.Sanitized] takes. */
        val MAX_FONTS = TerminalFont.Sanitized.MAX_FAMILIES
    }
}
