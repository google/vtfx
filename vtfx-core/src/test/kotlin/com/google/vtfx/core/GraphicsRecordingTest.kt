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

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Replays the output of real programs that show images (recordings/graphics, made by
 * tools/recordings/record_graphics.py in a terminal of 80 by 24 cells of 10 by 20 pixels) and
 * checks where the images are and where the cursor ends, which the programs rely on to lay out what
 * they write next (docs/decisions/0038). Each program shows the same image of 64 by 48 pixels.
 */
class GraphicsRecordingTest {
    private val directory =
        File(System.getProperty("vtfx.recordingsDir") ?: error("vtfx.recordingsDir is not set"))
            .resolve("graphics")

    private fun replay(name: String): Terminal {
        val terminal = Terminal(80, 24).apply { setCellSize(10, 20) }
        terminal.write(directory.resolve("$name.out").readBytes())
        return terminal
    }

    private fun TerminalSnapshot.placed(): List<String> = imagePlacements.map {
        "${it.image.width}x${it.image.height} at ${it.row},${it.column} " +
            "over ${it.width}x${it.height}"
    }

    @Test
    fun timg() {
        // timg -pk sends PNG at its natural size and writes CR LF after it.
        val terminal = replay("timg")
        assertEquals(listOf("64x48 at 0,0 over 6.4x2.4"), terminal.snapshot().placed())
        assertEquals(CursorState(0, 3), terminal.cursor)
    }

    @Test
    fun kittenIcat() {
        // kitten icat finds the protocol with a query, sends PNG, and writes CR LF after it.
        val terminal = replay("icat")
        assertEquals(listOf("64x48 at 0,0 over 6.4x2.4"), terminal.snapshot().placed())
        assertEquals(CursorState(0, 3), terminal.cursor)
    }

    @Test
    fun kittenIcatWithUnicodePlaceholders() {
        // A virtual placement of 7 by 3 cells, and placeholders with every diacritic, in the
        // foreground color of a 24-bit id and the third diacritic for its high byte.
        val terminal = replay("icat-placeholder")
        val snapshot = terminal.snapshot()
        assertEquals(emptyList(), snapshot.placed())
        val tiles = snapshot.imageTiles
        assertEquals(21, tiles.size)
        assertEquals(setOf(64 to 48), tiles.map { it.image.width to it.image.height }.toSet())
        for (tile in tiles) {
            assertEquals(Pair(7, 3), Pair(tile.columns, tile.rows))
            assertEquals(Pair(tile.row, tile.column), Pair(tile.imageRow, tile.imageColumn))
        }
        assertEquals("", snapshot.line(0).text().trim(), "placeholders are not text")
    }

    @Test
    fun fastfetch() {
        // fastfetch scales the logo to 16 columns (160 by 120 pixels), then moves up 5 rows from
        // the last row of the image to write the color blocks beside it, on its first two rows.
        val terminal = replay("fastfetch")
        val snapshot = terminal.snapshot()
        assertEquals(listOf("160x120 at 0,0 over 16.0x6.0"), snapshot.placed())
        assertEquals(20, (0 until 80).first { hasBackground(snapshot, 0, it) })
        assertEquals(20, (0 until 80).first { hasBackground(snapshot, 1, it) })
    }

    private fun hasBackground(snapshot: TerminalSnapshot, row: Int, column: Int): Boolean =
        !snapshot.line(row).styleAt(column).background.isDefault
}
