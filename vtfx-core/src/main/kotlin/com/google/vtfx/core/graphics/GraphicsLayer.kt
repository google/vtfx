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

package com.google.vtfx.core.graphics

import com.google.vtfx.core.Line
import com.google.vtfx.core.ScreenBuffer
import com.google.vtfx.core.Scrollback
import java.util.IdentityHashMap

/**
 * The images and placements of one screen (docs/decisions/0038). The main screen's placements can
 * be in its [scrollback]; the alternate screen has none.
 */
internal class GraphicsLayer(
    private val graphics: Graphics,
    private val screen: ScreenBuffer,
    private val scrollback: Scrollback?,
) {
    /** The images, oldest first. */
    private val images = LinkedHashSet<StoredImage>()
    private val imagesById = HashMap<Long, StoredImage>()

    /** The live placements, anchored and virtual, oldest first. */
    private val placements = LinkedHashSet<Placement>()

    val imageCount: Int
        get() = images.size

    val placementCount: Int
        get() = placements.size

    /** The number of live virtual placements, for Unicode placeholders. */
    private var virtualCount = 0

    /** Whether any image has a virtual placement, without which no placeholder shows an image. */
    val hasVirtualPlacements: Boolean
        get() = virtualCount > 0

    fun images(): Collection<StoredImage> = images

    fun imageById(id: Long): StoredImage? = imagesById[id]

    /** The newest image with [number], or null. */
    fun newestImageByNumber(number: Long): StoredImage? = images.lastOrNull { it.number == number }

    /**
     * Adds [image], replacing an image with the same id together with its placements. The caller
     * has made room for it.
     */
    fun add(image: StoredImage) {
        if (image.id != 0L) imagesById[image.id]?.let { delete(it) }
        images += image
        if (image.id != 0L) imagesById[image.id] = image
        graphics.onImageAdded(image)
    }

    /** Deletes [image] and its placements. */
    fun delete(image: StoredImage) {
        if (!images.remove(image)) return
        if (image.id != 0L && imagesById[image.id] === image) imagesById.remove(image.id)
        // forget changes neither list.
        for (placement in image.placements) forget(placement)
        for (placement in image.virtualPlacements) forget(placement)
        image.placements.clear()
        image.virtualPlacements.clear()
        graphics.onImageDeleted(image)
    }

    /** Deletes every image and placement. */
    fun clear() {
        for (image in images.toTypedArray()) delete(image)
    }

    /**
     * Adds [placement] of its image, anchored at [row] and [column] of the screen unless it is
     * virtual, replacing the placement of the same image with the same nonzero id. The oldest
     * placement goes beyond the limit.
     */
    fun add(placement: Placement, row: Int, column: Int) {
        val image = checkNotNull(placement.image)
        if (placement.id != 0L) {
            val list = if (placement.virtual) image.virtualPlacements else image.placements
            list.firstOrNull { it.id == placement.id }?.let { remove(it) }
        }
        if (placement.virtual) {
            image.virtualPlacements += placement
            virtualCount++
        } else {
            image.placements += placement
            screen[row].anchor(placement, column)
        }
        placements += placement
        while (placements.size > Graphics.MAX_PLACEMENTS) remove(placements.first())
    }

    /**
     * Deletes [placement]. An image that nothing can refer to (without an id or number) goes with
     * its last placement.
     */
    fun remove(placement: Placement) {
        val image = placement.image ?: return
        forget(placement)
        if (placement.virtual) image.virtualPlacements.remove(placement)
        else image.placements.remove(placement)
        if (image.id == 0L && !image.hasPlacements) delete(image)
    }

    private fun forget(placement: Placement) {
        if (placements.remove(placement) && placement.virtual) virtualCount--
        // A placement that is neither virtual nor on a line of the screen is in the scrollback.
        if (!placement.virtual && placement.line == null) scrollback?.unanchor(placement)
        placement.forget()
    }

    /**
     * Calls [action] with each placement anchored to a line and the row of the image's top, from
     * the top of the screen: negative for a row in the scrollback or above it. The action may
     * delete placements.
     */
    fun forEachAnchored(action: (Placement, Int) -> Unit) {
        if (placements.isEmpty()) return
        val rows = IdentityHashMap<Line, Int>(screen.rows)
        for (row in 0 until screen.rows) rows[screen[row]] = row
        val scrollback = scrollback
        for (placement in placements.toTypedArray()) {
            if (placement.virtual || !placement.isAlive) continue
            val line = placement.line
            val row =
                when {
                    line != null -> rows[line] ?: continue
                    scrollback != null ->
                        (placement.scrollbackSerial - scrollback.firstSerial).toInt() -
                            scrollback.size
                    else -> continue
                }
            action(placement, row - placement.rowsAbove)
        }
    }

    /** Calls [action] with each live placement, anchored or virtual. */
    fun forEachPlacement(action: (Placement) -> Unit) {
        for (placement in placements.toTypedArray()) if (placement.isAlive) action(placement)
    }
}
