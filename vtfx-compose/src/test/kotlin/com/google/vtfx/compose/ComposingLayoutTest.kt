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

import com.google.vtfx.core.AmbiguousWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposingLayoutTest {
    private fun layout(
        text: String,
        column: Int = 0,
        row: Int = 0,
        columns: Int = 4,
        rows: Int = 2,
    ) = ComposingLayout(text, column, row, columns, rows, AmbiguousWidth.Narrow)

    @Test
    fun wrapsAtTheRightEdge() {
        val layout = layout("abあc", column = 2)
        assertEquals(
            listOf(2 to 0, 3 to 0, 0 to 1, 2 to 1),
            layout.cells.map { it.column to it.row },
        )
        assertEquals(3, layout.endColumn)
        assertEquals(1, layout.endRow)
    }

    @Test
    fun stopsAtTheBottomOfTheGrid() {
        val layout = layout("abcdefghij")
        assertEquals(8, layout.cells.size)
        assertEquals(2, layout.endRow)
    }

    @Test
    fun combiningCharactersAreBoundedPerCell() {
        val text = "e" + "\u0301".repeat(100) + "x"
        val layout = layout(text)
        assertEquals(2, layout.cells.size)
        assertEquals(ComposingLayout.MAX_CELL_LENGTH, layout.cells[0].end - layout.cells[0].start)
        assertEquals(text.length - 1, layout.cells[1].start)
    }

    @Test
    fun hostileTextTakesWorkBoundedByTheGrid() {
        // The IME is not trusted: ten million characters must not make every frame slow.
        val combining = "e" + "\u0301".repeat(10_000_000)
        val plain = "x".repeat(10_000_000)
        val start = System.nanoTime()
        repeat(100) {
            val unused =
                listOf(
                    layout(combining, columns = 80, rows = 24),
                    layout(plain, columns = 80, rows = 24),
                )
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        // Each layout looks at no more than 80 * 24 * 32 characters; generously below the cost of
        // looking at all ten million, which is about 100 times as many.
        assertTrue("took $millis ms", millis < 10_000)
    }
}
