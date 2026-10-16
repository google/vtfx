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

package com.google.vtfx.core

/**
 * An image that the host sent with the kitty graphics protocol (docs/decisions/0038). Immutable:
 * its pixels never change, so a display may keep what it made of them for as long as it keeps the
 * image. Images are compared by identity; [key] tells them apart across snapshots.
 *
 * @suppress
 */
@InternalVtfxApi
public class TerminalImage
internal constructor(
    /** A number that no other image of the same terminal has, for caches of the display. */
    public val key: Long,
    /** The width in pixels. */
    public val width: Int,
    /** The height in pixels. */
    public val height: Int,
    /**
     * The pixels as 0xAARRGGBB, not premultiplied, row by row from the top: [width] × [height] of
     * them. Must not be modified.
     */
    public val pixels: IntArray,
) {
    override fun toString(): String = "TerminalImage(key=$key, ${width}x$height)"
}

/**
 * Where an image is drawn in a [TerminalSnapshot]. The image's area is a rectangle that starts at
 * the cell of [row] and [column], moved by [left] and [top] and [width] by [height] in size, all in
 * cells (a fraction of a cell for offsets in pixels), so that it scales with the cells. The part of
 * the image drawn there is the source rectangle in pixels.
 *
 * @suppress
 */
@InternalVtfxApi
public class ImagePlacement
internal constructor(
    /** The image. */
    public val image: TerminalImage,
    /**
     * The row of the cell where the image starts, from the top of the snapshot. Negative if it
     * starts above.
     */
    public val row: Int,
    /** The column of the cell where the image starts. */
    public val column: Int,
    /** The offset of the image from the left of its cell, in cells, less than 1. */
    public val left: Float,
    /** The offset of the image from the top of its cell, in cells, less than 1. */
    public val top: Float,
    /** The width of the image's area, in cells. */
    public val width: Float,
    /** The height of the image's area, in cells. */
    public val height: Float,
    /** The left of the source rectangle, in pixels of the image. */
    public val sourceX: Int,
    /** The top of the source rectangle, in pixels of the image. */
    public val sourceY: Int,
    /** The width of the source rectangle, in pixels of the image. */
    public val sourceWidth: Int,
    /** The height of the source rectangle, in pixels of the image. */
    public val sourceHeight: Int,
    /**
     * The stacking order: images with a negative z are drawn under the text, the others over it.
     * Placements are listed in the order to draw them.
     */
    public val z: Int,
) {
    override fun toString(): String =
        "ImagePlacement(${image.key} at $row,$column ${width}x$height z=$z)"
}
