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

package com.google.vtfx.core.spec

import java.io.ByteArrayOutputStream
import java.io.File

/** One case of a .vtspec file (docs/design/testing.md section 3). */
data class VtSpecCase(
    /** `<file>:<case name>`, as listed in spec-expected-failures.txt. */
    val id: String,
    val columns: Int,
    val rows: Int,
    val inputs: List<ByteArray>,
    val screen: List<String>?,
    /** 1-based column and row. */
    val cursor: Pair<Int, Int>?,
    /** A problem in the case itself, which makes it fail. */
    val error: String?,
)

/**
 * Parses .vtspec files. Unknown directives are reported as errors of the case, so that a case using
 * a directive the runner does not support yet fails instead of passing vacuously.
 */
object VtSpecParser {
    fun parse(file: File): List<VtSpecCase> = parse(file.name, file.readText())

    fun parse(fileName: String, text: String): List<VtSpecCase> {
        val cases = mutableListOf<VtSpecCase>()
        var builder: Builder? = null
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            i++
            if (trimmed.startsWith("=== ")) {
                builder?.let { cases += it.build() }
                builder = Builder("$fileName:${trimmed.removePrefix("=== ").trim()}")
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val current = builder ?: error("$fileName:$i: directive outside a case: $line")
            val keyword = trimmed.substringBefore(' ')
            val argument = trimmed.substringAfter(' ', "").trim()
            try {
                when (keyword) {
                    "size" -> {
                        val match =
                            Regex("""(\d+)x(\d+)""").matchEntire(argument)
                                ?: throw IllegalArgumentException("bad size: $argument")
                        current.columns = match.groupValues[1].toInt()
                        current.rows = match.groupValues[2].toInt()
                    }
                    "input" -> current.inputs += decodeString(argument)
                    "screen" -> {
                        val screen = mutableListOf<String>()
                        while (i < lines.size && lines[i].trim().startsWith("|")) {
                            val row = lines[i].trim()
                            require(row.length >= 2 && row.endsWith("|")) { "bad screen row: $row" }
                            screen += row.substring(1, row.length - 1)
                            i++
                        }
                        current.screen = screen
                    }
                    "cursor" -> {
                        val match =
                            Regex("""(\d+),(\d+)""").matchEntire(argument)
                                ?: throw IllegalArgumentException("bad cursor: $argument")
                        current.cursor =
                            Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                    }
                    "ref" -> {}
                    else -> throw IllegalArgumentException("unknown directive: $keyword")
                }
            } catch (e: IllegalArgumentException) {
                current.error = current.error ?: "line $i: ${e.message}"
            }
        }
        builder?.let { cases += it.build() }
        return cases
    }

    /** Decodes a quoted string with the escapes of testing.md section 3 into bytes. */
    fun decodeString(quoted: String): ByteArray {
        require(quoted.length >= 2 && quoted.startsWith("\"") && quoted.endsWith("\"")) {
            "bad string: $quoted"
        }
        val body = quoted.substring(1, quoted.length - 1)
        val out = ByteArrayOutputStream()
        var i = 0
        fun utf8(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                val end = if (Character.isHighSurrogate(c) && i + 1 < body.length) i + 2 else i + 1
                utf8(body.substring(i, end))
                i = end
                continue
            }
            require(i + 1 < body.length) { "trailing backslash" }
            when (val e = body[i + 1]) {
                'e' -> out.write(0x1b)
                'b' -> out.write(0x08)
                'r' -> out.write(0x0d)
                'n' -> out.write(0x0a)
                't' -> out.write(0x09)
                '\\' -> out.write('\\'.code)
                '"' -> out.write('"'.code)
                'x' -> {
                    require(i + 4 <= body.length) { "short \\x escape" }
                    out.write(body.substring(i + 2, i + 4).toInt(16))
                    i += 2
                }
                'u' -> {
                    require(i + 6 <= body.length) { "short \\u escape" }
                    utf8(String(Character.toChars(body.substring(i + 2, i + 6).toInt(16))))
                    i += 4
                }
                else -> throw IllegalArgumentException("unknown escape: \\$e")
            }
            i += 2
        }
        return out.toByteArray()
    }

    private class Builder(val id: String) {
        var columns = 0
        var rows = 0
        val inputs = mutableListOf<ByteArray>()
        var screen: List<String>? = null
        var cursor: Pair<Int, Int>? = null
        var error: String? = null

        fun build(): VtSpecCase {
            val missingSize = if (columns == 0 || rows == 0) "missing size" else null
            return VtSpecCase(id, columns, rows, inputs, screen, cursor, error ?: missingSize)
        }
    }
}
