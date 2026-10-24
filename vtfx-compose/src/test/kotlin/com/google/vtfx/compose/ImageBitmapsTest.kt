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

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.session.TerminalSession
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks how long the display keeps the bitmaps of images (section 9.6 of emulation.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class ImageBitmapsTest {
    private val bitmaps = ImageBitmaps()

    /** One frame, as `TerminalRenderer.draw` does it. */
    private fun drawFrame(session: TerminalSession) {
        val snapshot = session.snapshot()
        bitmaps.startFrame()
        for (placement in snapshot.imagePlacements) {
            val unused = bitmaps.bitmapOf(placement.image)
        }
        bitmaps.endFrame()
    }

    /** A zlib-compressed image of [side] by [side] pixels in base64, so that it costs little. */
    private fun compressedImage(side: Int): String {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(ByteArray(side * side * 4) { 0x7F }) }
        return Base64.getEncoder().encodeToString(compressed.toByteArray())
    }

    @Test
    fun replacingTheImageEveryFrame_keepsTheBitmapsWithinTheirBudget() {
        // 2 MiB each, so that more than the budget of bitmaps not drawn would build up.
        val side = 724
        val imageBytes = side.toLong() * side * 4
        val session =
            TerminalSession(
                {},
                columns = 80,
                rows = 24,
                limits = TerminalLimits(maxImageBytes = imageBytes.toInt()),
            )
        val payload = compressedImage(side)
        repeat(40) { frame ->
            // Deletes the image of the last frame and shows a new one.
            session.feed(
                ("\u001b_Ga=d,d=A,q=2\u001b\\" +
                        "\u001b[H\u001b_Ga=T,f=32,o=z,s=$side,v=$side,i=${frame + 1},q=2;" +
                        "$payload\u001b\\")
                    .toByteArray()
            )
            drawFrame(session)
        }

        assertTrue(
            "${bitmaps.bytes} bytes of bitmaps",
            bitmaps.bytes <= imageBytes + ImageBitmaps.MAX_UNDRAWN_BYTES,
        )
    }

    @Test
    fun anImageNotDrawnForAFewFrames_keepsItsBitmap() {
        val session = TerminalSession({}, columns = 80, rows = 24)
        session.feed("\u001b_Ga=t,f=32,s=1,v=1,i=1,q=2;/wAA/w==\u001b\\".toByteArray())
        session.feed("\u001b_Ga=p,i=1,q=2\u001b\\".toByteArray())
        drawFrame(session)
        // Scrolls the image out of the screen and draws a few frames without it.
        session.feed("\r\n".repeat(30).toByteArray())
        repeat(3) { drawFrame(session) }

        assertEquals(4L, bitmaps.bytes)
    }
}
