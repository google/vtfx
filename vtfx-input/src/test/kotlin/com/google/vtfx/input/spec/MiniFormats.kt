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

package com.google.vtfx.input.spec

/**
 * A minimal JSON parser for the ime-probe traces, to avoid a dependency for tests. Objects become
 * maps, arrays lists, numbers Long or Double.
 */
class MiniJson private constructor(private val text: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? {
            val parser = MiniJson(text)
            val value = parser.value()
            parser.skipWhitespace()
            require(parser.i == text.length) { "trailing characters at ${parser.i}" }
            return value
        }
    }

    private fun value(): Any? {
        skipWhitespace()
        require(i < text.length) { "unexpected end" }
        return when (val c = text[i]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else ->
                if (c == '-' || c.isDigit()) number()
                else throw IllegalArgumentException("unexpected '$c' at $i")
        }
    }

    private fun obj(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        i++
        skipWhitespace()
        if (text[i] == '}') {
            i++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            result[key] = value()
            skipWhitespace()
            if (text[i] == ',') {
                i++
                continue
            }
            expect('}')
            return result
        }
    }

    private fun array(): List<Any?> {
        val result = mutableListOf<Any?>()
        i++
        skipWhitespace()
        if (text[i] == ']') {
            i++
            return result
        }
        while (true) {
            result += value()
            skipWhitespace()
            if (text[i] == ',') {
                i++
                continue
            }
            expect(']')
            return result
        }
    }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = text[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' ->
                    when (val e = text[i++]) {
                        '"',
                        '\\',
                        '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(text.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e")
                    }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
        val s = text.substring(start, i)
        return s.toLongOrNull() ?: s.toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return value
    }

    private fun expect(c: Char) {
        require(i < text.length && text[i] == c) { "expected '$c' at $i" }
        i++
    }

    private fun skipWhitespace() {
        while (i < text.length && text[i].isWhitespace()) i++
    }
}

/**
 * A parser for the subset of TOML used by spec/ime: arrays of tables (`[[name]]`) whose values are
 * basic strings, single-line or multi-line. Anything else is an error, so that a spec using more of
 * TOML is not misread.
 */
object MiniToml {
    fun parseArrayOfTables(text: String, name: String): List<Map<String, String>> {
        val tables = mutableListOf<MutableMap<String, String>>()
        val lines = text.lines()
        var n = 0
        while (n < lines.size) {
            val line = lines[n].trim()
            n++
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "[[$name]]") {
                tables += LinkedHashMap()
                continue
            }
            val match =
                Regex("""([A-Za-z0-9_-]+)\s*=\s*(.*)""").matchEntire(line)
                    ?: throw IllegalArgumentException("line $n: unsupported TOML: $line")
            val table =
                tables.lastOrNull()
                    ?: throw IllegalArgumentException("line $n: key outside a table")
            val (key, rest) = match.destructured
            val value =
                if (rest.startsWith("\"\"\"")) {
                    val raw = StringBuilder(rest.removePrefix("\"\"\""))
                    while (!raw.endsWith("\"\"\"")) {
                        require(n < lines.size) { "unterminated multi-line string" }
                        raw.append('\n').append(lines[n++])
                    }
                    var body = raw.removeSuffix("\"\"\"").toString()
                    // A newline right after the opening delimiter is trimmed.
                    body = body.removePrefix("\n")
                    // A line-ending backslash trims the newline and the whitespace that follows.
                    body = body.replace(Regex("""\\\n\s*"""), "")
                    unescape(body)
                } else {
                    require(rest.startsWith("\"") && rest.endsWith("\"") && rest.length >= 2) {
                        "line $n: unsupported value: $rest"
                    }
                    unescape(rest.substring(1, rest.length - 1))
                }
            require(key !in table) { "line $n: duplicate key $key" }
            table[key] = value
        }
        return tables
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') {
                sb.append(c)
                continue
            }
            when (val e = s[i++]) {
                '"',
                '\\' -> sb.append(e)
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'e' -> sb.append('\u001b')
                'u' -> {
                    sb.appendCodePoint(s.substring(i, i + 4).toInt(16))
                    i += 4
                }
                'U' -> {
                    sb.appendCodePoint(s.substring(i, i + 8).toInt(16))
                    i += 8
                }
                else -> throw IllegalArgumentException("bad escape \\$e")
            }
        }
        return sb.toString()
    }
}
