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

import android.graphics.Bitmap
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.TerminalImage

/**
 * The bitmaps of the images that frames draw (docs/decisions/0038), made once per image. A bitmap
 * that no frame drew for [KEEP_FRAMES] frames goes, so that the bitmaps of deleted images do not
 * stay; images that come back, scrolled into view again for example, are made again. The bitmaps of
 * images that the last frame did not draw are kept up to [MAX_UNDRAWN_BYTES], so that their memory
 * stays bounded when the host replaces an image on every frame (docs/decisions/0052).
 *
 * One per display: the keys of images are unique only within a session.
 */
internal class ImageBitmaps {
    private class Entry(val bitmap: Bitmap, val bytes: Long, var frame: Int)

    private val entries = HashMap<Long, Entry>()
    private var frame = 0

    /** The bytes of the pixels of the bitmaps kept. */
    val bytes: Long
        get() = entries.values.sumOf { it.bytes }

    fun startFrame() {
        frame++
    }

    /** Returns the bitmap of [image], made from its pixels the first time. */
    fun bitmapOf(image: TerminalImage): Bitmap {
        val entry =
            entries.getOrPut(image.key) {
                Entry(
                    Bitmap.createBitmap(
                        image.pixels,
                        image.width,
                        image.height,
                        Bitmap.Config.ARGB_8888,
                    ),
                    image.width.toLong() * image.height * BYTES_PER_PIXEL,
                    frame,
                )
            }
        entry.frame = frame
        return entry.bitmap
    }

    fun endFrame() {
        entries.values.removeIf { frame - it.frame > KEEP_FRAMES }
        // The bitmaps of images that this frame did not draw, the least recently drawn first, go
        // beyond MAX_UNDRAWN_BYTES: the host can replace an image on every frame.
        val undrawn = entries.entries.filter { it.value.frame != frame }
        var undrawnBytes = undrawn.sumOf { it.value.bytes }
        if (undrawnBytes <= MAX_UNDRAWN_BYTES) return
        for ((key, entry) in undrawn.sortedBy { it.value.frame }) {
            entries.remove(key)
            undrawnBytes -= entry.bytes
            if (undrawnBytes <= MAX_UNDRAWN_BYTES) return
        }
    }

    companion object {
        /** The most bytes of bitmaps kept for images that the last frame did not draw. */
        const val MAX_UNDRAWN_BYTES = 32L * 1024 * 1024

        private const val BYTES_PER_PIXEL = 4

        /** About a second of frames at 60 frames per second. */
        private const val KEEP_FRAMES = 60
    }
}
