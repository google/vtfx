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
 * stay; images that come back, scrolled into view again for example, are made again.
 */
internal class ImageBitmaps {
    private class Entry(val bitmap: Bitmap, var frame: Int)

    private val entries = HashMap<Long, Entry>()
    private var frame = 0

    val size: Int
        get() = entries.size

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
                    frame,
                )
            }
        entry.frame = frame
        return entry.bitmap
    }

    fun endFrame() {
        entries.values.removeIf { frame - it.frame > KEEP_FRAMES }
    }

    private companion object {
        /** About a second of frames at 60 frames per second. */
        const val KEEP_FRAMES = 60
    }
}
