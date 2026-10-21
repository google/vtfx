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

@file:OptIn(InternalVtfxApi::class)

package com.google.vtfx.core

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import java.io.File
import org.junit.ComparisonFailure
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the output of real apps recorded by tools/recordings/record.py and compares the screen
 * with the one the reference terminal (tmux) showed for the same output (docs/design/testing.md
 * section 10).
 */
@RunWith(TestParameterInjector::class)
class RecordingReplayTest {
    @Test
    fun recording(@TestParameter(valuesProvider = Recordings::class) recording: File) {
        replay(recording.name.removeSuffix(".out"), recording)
    }

    private class Recordings : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<*> {
            val recordings =
                directory.listFiles { file -> file.name.endsWith(".out") }?.sortedBy { it.name }
                    ?: error("No recordings in $directory")
            check(recordings.isNotEmpty()) { "No recordings in $directory" }
            return recordings.map { value(it).withName(it.name.removeSuffix(".out")) }
        }
    }

    private fun replay(name: String, recording: File) {
        val meta = File(directory, "$name.json").readText()
        val columns = Regex(""""columns": (\d+)""").find(meta)!!.groupValues[1].toInt()
        val rows = Regex(""""rows": (\d+)""").find(meta)!!.groupValues[1].toInt()
        val expected =
            File(directory, "$name.screen.txt").readLines().take(rows).map { it.trimEnd() }
        val terminal = Terminal(columns, rows)
        terminal.write(recording.readBytes())
        val snapshot = terminal.snapshot()
        val actual = List(rows) { snapshot.line(it).text().trimEnd() }
        if (actual != expected) {
            // For inspection, e.g. with diff against the reference.
            File("build/recordings").mkdirs()
            File("build/recordings/$name.actual.txt")
                .writeText(actual.joinToString("\n", postfix = "\n"))
            throw ComparisonFailure(
                "$name: screen differs from the reference",
                expected.joinToString("\n"),
                actual.joinToString("\n"),
            )
        }
        compareStyles(name, snapshot, File(directory, "$name.screen.ansi"))
    }

    /**
     * Compares the style of every non-blank cell with the reference's capture with SGR sequences.
     * The capture is read with [CaptureReader], independent of vtfx's SGR parsing, so that a bug
     * there is not hidden on both sides.
     */
    private fun compareStyles(name: String, snapshot: TerminalSnapshot, capture: File) {
        val expected = CaptureReader.read(capture.readText(), snapshot.columns, snapshot.rows)
        val differences = mutableListOf<String>()
        for (row in 0 until snapshot.rows) {
            for (column in 0 until snapshot.columns) {
                val text = snapshot.line(row).textAt(column) ?: continue
                if (text == " ") continue
                val actualStyle = describe(snapshot.line(row).styleAt(column))
                val expectedStyle = expected[row][column]
                if (actualStyle != expectedStyle)
                    differences += "$row,$column '$text': expected $expectedStyle, was $actualStyle"
            }
        }
        if (differences.isNotEmpty()) {
            throw AssertionError(
                "$name: ${differences.size} cells differ in style:\n" +
                    differences.take(20).joinToString("\n")
            )
        }
    }

    private fun describe(color: TerminalColor): String =
        when {
            color.isDefault -> "default"
            color.isIndexed -> "index${color.index}"
            else -> "rgb%06x".format(color.rgb)
        }

    private fun describe(style: CellStyle): String =
        CaptureReader.Style(
                foreground = describe(style.foreground),
                background = describe(style.background),
                bold = style.isBold,
                faint = style.isFaint,
                italic = style.isItalic,
                underline = style.underline != UnderlineStyle.None,
                inverse = style.isInverse,
                strikethrough = style.isStrikethrough,
            )
            .toString()

    /** Reads tmux's `capture-pane -e` output: text and SGR sequences, lines separated by LF. */
    private object CaptureReader {
        data class Style(
            val foreground: String = "default",
            val background: String = "default",
            val bold: Boolean = false,
            val faint: Boolean = false,
            val italic: Boolean = false,
            val underline: Boolean = false,
            val inverse: Boolean = false,
            val strikethrough: Boolean = false,
        )

        fun read(capture: String, columns: Int, rows: Int): List<List<String>> {
            val cells = List(rows) { MutableList(columns) { Style().toString() } }
            var style = Style()
            var row = 0
            var column = 0
            var i = 0
            while (i < capture.length && row < rows) {
                val c = capture[i]
                when {
                    c == '\u001b' -> {
                        val end = capture.indexOf('m', i)
                        style = apply(style, capture.substring(i + 2, end))
                        i = end + 1
                        continue
                    }
                    c == '\n' -> {
                        row++
                        column = 0
                    }
                    else -> {
                        val codePoint = capture.codePointAt(i)
                        val width = CharWidth.of(codePoint)
                        if (width > 0 && column < columns) {
                            cells[row][column] = style.toString()
                            column += width
                        }
                        i += Character.charCount(codePoint)
                        continue
                    }
                }
                i++
            }
            return cells
        }

        private fun apply(start: Style, parameters: String): Style {
            var style = start
            val params = parameters.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
            val subParameters = parameters.split(';').map { it.substringAfter(':', "") }
            var i = 0
            while (i < params.size) {
                val p = params[i]
                style =
                    when (p) {
                        0 -> Style()
                        1 -> style.copy(bold = true)
                        2 -> style.copy(faint = true)
                        3 -> style.copy(italic = true)
                        4 -> style.copy(underline = subParameters[i] != "0")
                        7 -> style.copy(inverse = true)
                        9 -> style.copy(strikethrough = true)
                        22 -> style.copy(bold = false, faint = false)
                        23 -> style.copy(italic = false)
                        24 -> style.copy(underline = false)
                        27 -> style.copy(inverse = false)
                        29 -> style.copy(strikethrough = false)
                        in 30..37 -> style.copy(foreground = "index${p - 30}")
                        39 -> style.copy(foreground = "default")
                        in 40..47 -> style.copy(background = "index${p - 40}")
                        49 -> style.copy(background = "default")
                        in 90..97 -> style.copy(foreground = "index${p - 90 + 8}")
                        in 100..107 -> style.copy(background = "index${p - 100 + 8}")
                        38,
                        48 -> {
                            val color =
                                if (params[i + 1] == 5) {
                                    "index${params[i + 2]}".also { i += 2 }
                                } else {
                                    "rgb%02x%02x%02x"
                                        .format(params[i + 2], params[i + 3], params[i + 4])
                                        .also { i += 4 }
                                }
                            if (p == 38) style.copy(foreground = color)
                            else style.copy(background = color)
                        }
                        else -> style
                    }
                i++
            }
            return style
        }
    }

    private companion object {
        val directory =
            File(System.getProperty("vtfx.recordingsDir") ?: error("vtfx.recordingsDir is not set"))
    }
}
