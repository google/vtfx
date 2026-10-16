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

package com.google.vtfx.core.graphics

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Line
import com.google.vtfx.core.TerminalImage

/**
 * An image kept by a [GraphicsLayer]. [id] is 0 for an image that the host gave neither an id nor a
 * number, which nothing can refer to once it has no placements.
 */
internal class StoredImage(
    val layer: GraphicsLayer,
    val id: Long,
    val number: Long,
    val image: TerminalImage,
) {
    /** The placements anchored to lines, oldest first. */
    val placements: ArrayList<Placement> = ArrayList(1)

    /** The virtual placements, for Unicode placeholders, oldest first. */
    val virtualPlacements: ArrayList<Placement> = ArrayList(0)

    /** The memory of the pixels, which counts against the limit. */
    val bytes: Long
        get() = image.width.toLong() * image.height * 4

    /** The order in which images were stored, for eviction and image numbers. */
    val serial: Long
        get() = image.key

    val hasPlacements: Boolean
        get() = placements.isNotEmpty() || virtualPlacements.isNotEmpty()
}

/**
 * Where and how an image is shown. A placement that is not [virtual] is anchored to the cell of a
 * line at [column]: while that line is on the screen, [line] is it; once it is in the scrollback,
 * [scrollbackSerial] tells where (see `Scrollback.firstSerial`).
 *
 * The size is kept in cells, computed when it was placed, so that it does not depend on the display
 * afterwards (docs/decisions/0038).
 */
internal class Placement(
    image: StoredImage,
    /** The placement id, or 0. */
    val id: Long,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** The offset in the first cell and the size of the image's area, in cells. */
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** The cells that the image's area spans. */
    val columns: Int,
    val rows: Int,
    val z: Int,
    val virtual: Boolean,
    /** The order of placing, for the limit on placements and for drawing order. */
    val serial: Long,
) {
    /** The image, or null once the placement is deleted. */
    var image: StoredImage? = image
        private set

    val isAlive: Boolean
        get() = image != null

    var line: Line? = null
    var column: Int = 0
    var scrollbackSerial: Long = -1L
        private set

    /** Notes that the line this placement is anchored to went into the scrollback as [serial]. */
    fun moveToScrollback(serial: Long) {
        if (!isAlive) return
        line = null
        scrollbackSerial = serial
    }

    /** Deletes this placement, if it is not deleted yet. */
    fun kill() {
        image?.layer?.remove(this)
    }

    /** Called by the layer when it deletes this placement. */
    fun forget() {
        image = null
        line?.unanchor(this)
        line = null
    }
}
