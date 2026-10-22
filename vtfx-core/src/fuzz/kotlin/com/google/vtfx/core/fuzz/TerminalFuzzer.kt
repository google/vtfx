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

package com.google.vtfx.core.fuzz

import com.google.vtfx.core.CharWidth
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LinkFinder
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalSnapshot

/**
 * Fuzzes the parser and the terminal model with arbitrary host output (docs/design/testing.md
 * section 7).
 *
 * A libFuzzer entry point. The tests replay its corpus in src/fuzz/corpus/TerminalFuzzer
 * ([TerminalFuzzerCorpusTest]), and the `fuzz` Gradle task fuzzes it; see scripts/fuzz.sh.
 *
 * The input is plain host output with a 3-byte header, so that seeds can be written by hand
 * ([TerminalFuzzInput]).
 */
object TerminalFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(input: ByteArray) {
        val decoded = TerminalFuzzInput.decode(input) ?: return
        val terminal = Terminal(decoded.columns, decoded.rows, decoded.limits)
        for (operation in decoded.operations) {
            when (operation) {
                is TerminalFuzzInput.Operation.Write -> terminal.write(operation.bytes)
                is TerminalFuzzInput.Operation.Resize -> {
                    terminal.resize(operation.columns, operation.rows)
                    checkInvariants(terminal)
                }
            }
        }
        checkInvariants(terminal)
    }

    private fun checkInvariants(terminal: Terminal) {
        val snapshot = terminal.snapshot()
        check(snapshot.cursor.column in 0 until snapshot.columns) { "cursor column out of range" }
        check(snapshot.cursor.row in 0 until snapshot.rows) { "cursor row out of range" }
        for (row in 0 until snapshot.rows) {
            val line = snapshot.line(row)
            check(line.columns == snapshot.columns) { "line $row has ${line.columns} columns" }
            for (column in 0 until line.columns) {
                when (line.widthAt(column)) {
                    0 ->
                        check(column > 0 && line.widthAt(column - 1) == 2) {
                            "orphaned wide tail at $row,$column"
                        }
                    2 ->
                        check(column + 1 < line.columns) {
                            "wide head at the last column of row $row"
                        }
                }
                line.textAt(column)?.let { text ->
                    check(text.length <= MAX_CELL_TEXT_LENGTH) {
                        "cell text too long at $row,$column"
                    }
                    check(line.widthAt(column) == widthOf(text)) {
                        "cell at $row,$column is ${line.widthAt(column)} wide for its text"
                    }
                }
            }
        }
        check(terminal.scrollbackSize <= SCROLLBACK_LINES) { "scrollback exceeds the limit" }
        checkLinks(snapshot)
        checkImages(terminal, snapshot)
    }

    /**
     * Images stay within their limits, and every placement anchored to a line is on the screen or
     * in the scrollback (docs/decisions/0038).
     */
    private fun checkImages(terminal: Terminal, snapshot: TerminalSnapshot) {
        check(terminal.imageBytes in 0..MAX_IMAGE_BYTES) { "images take ${terminal.imageBytes}" }
        val layer = terminal.graphicsLayer
        check(layer.imageCount <= 1024 && layer.placementCount <= 1024) { "too many images" }
        var anchored = 0
        layer.forEachPlacement { if (!it.virtual) anchored++ }
        var found = 0
        layer.forEachAnchored { _, row ->
            found++
            check(row >= -terminal.scrollbackSize && row < snapshot.rows) { "placement at $row" }
        }
        check(found == anchored) { "$anchored placements, $found on lines" }
        for (placement in snapshot.imagePlacements) {
            check(placement.row < snapshot.rows && placement.column < snapshot.columns) {
                "placement out of the snapshot"
            }
        }
    }

    /** The links found on the screen cover cells of the screen without OSC 8 hyperlinks. */
    private fun checkLinks(snapshot: TerminalSnapshot) {
        for (link in LinkFinder(LINK_PATTERNS).find(snapshot)) {
            check(link.text.isNotEmpty() && link.url.isNotEmpty()) { "empty link" }
            check(link.spans.isNotEmpty()) { "link without cells" }
            for (span in link.spans) {
                check(
                    span.row in 0 until snapshot.rows &&
                        0 <= span.start &&
                        span.start < span.end &&
                        span.end <= snapshot.columns
                ) {
                    "link span out of range"
                }
                for (column in span.start until span.end) {
                    check(snapshot.line(span.row).hyperlinkAt(column) == null) {
                        "link over a hyperlink"
                    }
                }
            }
        }
    }

    /**
     * The width that a cell with [text] must have: that of its first code point, at the default
     * ambiguous width, or 2 for a flag, a pair of regional indicators (docs/design/emulation.md
     * section 7.2). This catches a wide character that an edit left in one cell.
     */
    private fun widthOf(text: String): Int {
        val first = text.codePointAt(0)
        val second =
            Character.charCount(first).let { if (it < text.length) text.codePointAt(it) else -1 }
        if (first in REGIONAL_INDICATORS && second in REGIONAL_INDICATORS) return 2
        return CharWidth.of(first)
    }

    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
    private const val MAX_CELL_TEXT_LENGTH = 32
    private const val SCROLLBACK_LINES = 4

    /** Room for a few small images, so that the fuzzer reaches eviction. */
    private const val MAX_IMAGE_BYTES = 4096

    /** The built-in URLs, and a pattern with groups, wide characters, and backtracking. */
    private val LINK_PATTERNS =
        listOf(
            LinkPattern.Urls,
            LinkPattern(Regex("""(a|aa)+(\d*)|あ(\S+)"""), "https://example.com/{1}/{2}/{3}"),
        )
}

/**
 * The input format of [TerminalFuzzer].
 *
 * - Byte 0: the number of columns, 1 + (byte % 12).
 * - Byte 1: the number of rows, 1 + (byte % 6).
 * - Byte 2: the scrollback lines, byte % 5.
 * - The rest: host output. 0xFE splits it into separate writes. 0xFF followed by a byte b resizes
 *   to 1 + (b % 12) columns and 1 + (b / 12 % 6) rows. Neither byte appears in UTF-8.
 */
object TerminalFuzzInput {
    sealed interface Operation {
        class Write(val bytes: ByteArray) : Operation

        class Resize(val columns: Int, val rows: Int) : Operation
    }

    class Decoded(
        val columns: Int,
        val rows: Int,
        val limits: TerminalLimits,
        val operations: List<Operation>,
    )

    private const val SPLIT = 0xFE.toByte()
    private const val RESIZE = 0xFF.toByte()

    fun decode(input: ByteArray): Decoded? {
        if (input.size < 3) return null
        val columns = 1 + (input[0].toInt() and 0xFF) % 12
        val rows = 1 + (input[1].toInt() and 0xFF) % 6
        val limits =
            TerminalLimits(
                scrollbackLines = (input[2].toInt() and 0xFF) % 5,
                maxStringLength = 64,
                maxImageBytes = 4096,
            )
        val operations = mutableListOf<Operation>()
        var start = 3
        var i = 3
        fun flush() {
            if (i > start) operations += Operation.Write(input.copyOfRange(start, i))
        }
        while (i < input.size) {
            when (input[i]) {
                SPLIT -> {
                    flush()
                    start = i + 1
                }
                RESIZE -> {
                    flush()
                    if (i + 1 < input.size) {
                        val b = input[i + 1].toInt() and 0xFF
                        operations += Operation.Resize(1 + b % 12, 1 + b / 12 % 6)
                    }
                    i++
                    start = i + 1
                }
            }
            i++
        }
        flush()
        return Decoded(columns, rows, limits, operations)
    }
}
