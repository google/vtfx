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

package com.google.vtfx.core.diff

import com.google.vtfx.core.CellStyle
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalColor
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.UnderlineStyle
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.HexFormat

/**
 * Batch runner for [Terminal] used by `scripts/alacritty_diff.py` (docs/design/testing.md section
 * 15).
 *
 * Reads the same `CASE` stream from stdin as `tools/alacritty-diff` and writes `OK` or `ERR` lines
 * to stdout.
 */
object DifferentialHost {
    private val hex = HexFormat.of()

    private sealed interface Op {
        class Write(val bytes: ByteArray) : Op

        class Resize(val columns: Int, val rows: Int) : Op
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(System.out, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val parts = line.split('\t', limit = 7)
            require(parts.size >= 6 && parts[0] == "CASE") { "malformed CASE line" }
            val idHex = parts[1]
            val columns = parts[2].toInt()
            val rows = parts[3].toInt()
            val scrollback = parts[4].toInt()
            val includeStyles = parts[5] == "1"
            val ops = parseOps(if (parts.size == 7) parts[6] else "")
            try {
                val result = runCase(columns, rows, scrollback, includeStyles, ops)
                writer.write("OK\t$idHex\t$result\n")
            } catch (t: Throwable) {
                val msg = (t.message ?: t.javaClass.name).toByteArray(Charsets.UTF_8)
                writer.write("ERR\t$idHex\t${hex.formatHex(msg)}\n")
            }
            writer.flush()
        }
    }

    private fun parseOps(raw: String): List<Op> {
        if (raw.isBlank()) return emptyList()
        return raw.trim().split(Regex("\\s+")).map { token ->
            when {
                token.startsWith("W:") -> Op.Write(hex.parseHex(token.removePrefix("W:")))
                token.startsWith("R:") -> {
                    val dims = token.removePrefix("R:").split('x', limit = 2)
                    Op.Resize(dims[0].toInt(), dims[1].toInt())
                }
                else -> error("unknown op: $token")
            }
        }
    }

    private fun runCase(
        columns: Int,
        rows: Int,
        scrollback: Int,
        includeStyles: Boolean,
        ops: List<Op>,
    ): String {
        val terminal = Terminal(columns, rows, TerminalLimits(scrollbackLines = scrollback))
        for (op in ops) {
            when (op) {
                is Op.Write -> terminal.write(op.bytes)
                is Op.Resize -> terminal.resize(op.columns, op.rows)
            }
        }
        val snapshot = terminal.snapshot()
        val lineHexes = ArrayList<String>(snapshot.rows)
        val styleHexes = ArrayList<String>(if (includeStyles) snapshot.rows else 0)
        for (row in 0 until snapshot.rows) {
            val lineSnapshot = snapshot.line(row)
            lineHexes += hex.formatHex(lineSnapshot.text().toByteArray(Charsets.UTF_8))
            if (includeStyles) {
                val cellStyles = ArrayList<String>(lineSnapshot.columns)
                for (col in 0 until lineSnapshot.columns) {
                    if (lineSnapshot.widthAt(col) == 0) continue
                    cellStyles +=
                        formatStyle(
                            lineSnapshot.styleAt(col),
                            lineSnapshot.underlineColorAt(col),
                        )
                }
                styleHexes +=
                    hex.formatHex(cellStyles.joinToString(";").toByteArray(Charsets.UTF_8))
            }
        }
        return "${snapshot.cursor.column},${snapshot.cursor.row}\t" +
            "${lineHexes.joinToString(",")}\t${styleHexes.joinToString(",")}"
    }

    private fun formatColor(color: TerminalColor): String =
        when {
            color.isDefault -> "d"
            color.isIndexed -> "i${color.index}"
            else -> "r%06x".format(color.rgb)
        }

    private fun formatStyle(style: CellStyle, underlineColor: TerminalColor): String = buildString {
        append(formatColor(style.foreground))
        append('/')
        append(formatColor(style.background))
        append('/')
        if (style.isBold) append('B')
        if (style.isFaint) append('F')
        if (style.isItalic) append('I')
        when (style.underline) {
            UnderlineStyle.None -> {}
            UnderlineStyle.Single -> append("U1")
            UnderlineStyle.Double -> append("U2")
            UnderlineStyle.Curly -> append("U3")
            UnderlineStyle.Dotted -> append("U4")
            UnderlineStyle.Dashed -> append("U5")
        }
        if (style.isInverse) append('V')
        if (style.isInvisible) append('H')
        if (style.isStrikethrough) append('S')
        if (style.isOverlined) append('O')
        append('/')
        if (style.underline != UnderlineStyle.None && !underlineColor.isDefault) {
            append(formatColor(underlineColor))
        }
    }
}
