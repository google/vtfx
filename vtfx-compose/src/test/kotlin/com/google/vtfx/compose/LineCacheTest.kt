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

package com.google.vtfx.compose

import androidx.compose.ui.unit.Density
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class LineCacheTest {
    private val cache =
        LineCache(CellMetrics(width = 10f, height = 20f, baseline = 15f), Density(1f))
    private val prompt = Terminal(10, 1).apply { write("$ ".toByteArray()) }.snapshot().line(0)
    private var recordings = 0

    private fun frame(vararg lines: com.google.vtfx.core.LineSnapshot) =
        lines
            .map { line ->
                cache.nodeFor(line) { recordings++ }
            }
            .also { cache.endFrame() }

    private fun startFrame() = cache.startFrame(columns = 10, reverse = false)

    @Test
    fun unchangedLinesAreNotRecordedAgain() {
        startFrame()
        val first = frame(prompt).single()
        startFrame()
        assertSame(first, frame(prompt).single())
        assertEquals(1, recordings)
    }

    @Test
    fun aNodeThatLostItsDisplayListIsRecordedAgain() {
        // Found on a device: the renderer discards the display list of a node that a frame did not
        // draw, and the prompt reused such a node and was not shown.
        startFrame()
        val node = frame(prompt).single()
        node.discardDisplayList()
        startFrame()
        assertSame(node, frame(prompt).single())
        assertEquals(2, recordings)
        assertTrue(node.hasDisplayList())
    }
}
