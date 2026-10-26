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

import com.google.vtfx.core.GraphicsStatus
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.ProtocolLimits
import com.google.vtfx.core.TerminalImage
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalQuery

/**
 * Carries out kitty graphics commands (docs/decisions/0080): transmissions, placements, deletions,
 * and the replies to them, within [maxBytes] of decoded pixels for both screens together.
 */
internal class Graphics(
    private val maxBytes: Long,
    private val reply: (TerminalQuery) -> Unit,
) {
    /** The screen where the commands act, and where the cursor is. */
    interface Screen {
        val layer: GraphicsLayer
        val cursorColumn: Int
        val cursorRow: Int
        val rows: Int

        /** Moves the cursor after a placement of [columns] by [rows] cells, as kitty does. */
        fun moveCursorAfterImage(columns: Int, rows: Int)
    }

    /** Whether the protocol is on: with no room for images, commands are ignored. */
    val isEnabled: Boolean
        get() = maxBytes > 0

    /** The size of a cell in pixels, which sizes placements without columns and rows. */
    var cellWidth: Int = DEFAULT_CELL_WIDTH
        private set

    var cellHeight: Int = DEFAULT_CELL_HEIGHT
        private set

    /** Whether the display gave the size of a cell, rather than the default. */
    var isCellSizeKnown: Boolean = false
        private set

    private val layers = ArrayList<GraphicsLayer>(2)

    /** The memory of the pixels of every image. */
    var bytes: Long = 0L
        private set

    private var nextSerial = 1L

    /** The next id to give an image that the host gave a number (`I`) instead of an id. */
    private var nextAssignedId = 1L

    private var transmission: Transmission? = null

    fun setCellSize(width: Int, height: Int) {
        require(
            width in 1..ProtocolLimits.MAX_CELL_SIZE && height in 1..ProtocolLimits.MAX_CELL_SIZE
        ) {
            "cell size out of range: ${width}x$height"
        }
        cellWidth = width
        cellHeight = height
        isCellSizeKnown = true
    }

    fun register(layer: GraphicsLayer) {
        layers += layer
    }

    fun onImageAdded(image: StoredImage) {
        bytes += image.bytes
    }

    fun onImageDeleted(image: StoredImage) {
        bytes -= image.bytes
    }

    /** Abandons a transmission in progress, as a reset does. */
    fun abort() {
        transmission = null
    }

    /** Carries out the graphics command in [data], which starts with `G`, on [screen]. */
    fun handle(data: CharSequence, screen: Screen) {
        if (!isEnabled) return
        val command = GraphicsCommand.parse(data) ?: return
        val loading = transmission
        if (
            loading != null &&
                (command.action == GraphicsCommand.NONE || command.action == loading.command.action)
        ) {
            continueTransmission(loading, command, data, screen)
            return
        }
        // Any other command ends a transmission that did not finish.
        transmission = null
        when (command.action) {
            GraphicsCommand.NONE,
            't',
            'T',
            'q' -> startTransmission(command, data, screen)
            'p' -> put(command, screen)
            'd' -> delete(command, screen)
            // Animation is not supported (docs/decisions/0080).
            'f',
            'a',
            'c' -> respond(command, GraphicsStatus.Unsupported)
        }
    }

    private fun startTransmission(command: GraphicsCommand, data: CharSequence, screen: Screen) {
        if (command.imageId != 0L && command.imageNumber != 0L) {
            respond(command, GraphicsStatus.InvalidCommand)
            return
        }
        val limit = minOf(maxBytes, MAX_TRANSMISSION_BYTES.toLong()).toInt()
        val loading = Transmission(command, screen.layer, limit)
        loading.append(data, command.payloadStart)
        if (command.more == 1L) {
            transmission = loading
        } else {
            finish(loading, screen)
        }
    }

    private fun continueTransmission(
        loading: Transmission,
        command: GraphicsCommand,
        data: CharSequence,
        screen: Screen,
    ) {
        loading.append(data, command.payloadStart)
        if (command.more != 1L) {
            transmission = null
            finish(loading, screen)
        }
    }

    private fun finish(loading: Transmission, screen: Screen) {
        val command = loading.command
        val image = decode(loading) ?: return
        if (command.action == 'q') {
            respond(command, GraphicsStatus.Ok)
            return
        }
        if (screen.layer !== loading.layer) {
            // The screen changed during the transmission: the image is for a screen that is gone.
            respond(command, GraphicsStatus.InvalidCommand)
            return
        }
        val bytes = image.width.toLong() * image.height * 4
        if (bytes > maxBytes) {
            respond(command, GraphicsStatus.TooLarge)
            return
        }
        // The image that this one replaces goes first, so that it does not count against the
        // limits and make other images go.
        if (command.imageId != 0L) {
            screen.layer.imageById(command.imageId)?.let { screen.layer.delete(it) }
        }
        makeRoom(screen.layer, bytes)
        val id =
            when {
                command.imageId != 0L -> command.imageId
                command.imageNumber != 0L -> assignId()
                else -> 0L
            }
        val stored = StoredImage(screen.layer, id, command.imageNumber, image)
        screen.layer.add(stored)
        if (command.action == 'T') {
            respond(command, place(stored, command, screen), id)
        } else {
            respond(command, GraphicsStatus.Ok, id)
        }
    }

    /** Returns the image, or replies why it could not be made and returns null. */
    private fun decode(loading: Transmission): TerminalImage? {
        val command = loading.command
        fun fail(status: GraphicsStatus): TerminalImage? {
            respond(command, status)
            return null
        }
        if (command.medium != 'd') return fail(GraphicsStatus.UnsupportedMedium)
        if (loading.overflowed) return fail(GraphicsStatus.TooLarge)
        val format = command.format
        if (format != 24L && format != 32L && format != 100L) {
            return fail(GraphicsStatus.InvalidCommand)
        }
        val maxPixels = minOf(maxBytes, MAX_BYTES_PER_IMAGE) / 4
        var data = loading.data
        var size = loading.size
        if (format != 100L) {
            val width = command.width
            val height = command.height
            if (width == 0L || height == 0L) return fail(GraphicsStatus.InvalidCommand)
            if (width > MAX_SIDE || height > MAX_SIDE || width * height > maxPixels) {
                return fail(GraphicsStatus.TooLarge)
            }
            val bytesPerPixel = if (format == 24L) 3 else 4
            val expected = (width * height * bytesPerPixel).toInt()
            if (command.compression == 'z') {
                data =
                    Zlib.inflate(data, 0, size, expected)
                        ?: return fail(GraphicsStatus.InflateFailed)
                size = data.size
            }
            if (size < expected) return fail(GraphicsStatus.InsufficientData)
            val pixels = IntArray((width * height).toInt())
            var offset = 0
            for (i in pixels.indices) {
                val r = data[offset].toInt() and 0xFF
                val g = data[offset + 1].toInt() and 0xFF
                val b = data[offset + 2].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) data[offset + 3].toInt() and 0xFF else 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                offset += bytesPerPixel
            }
            return newImage(width.toInt(), height.toInt(), pixels)
        }
        if (command.compression == 'z') {
            val limit =
                if (command.size != 0L) minOf(command.size, loading.limit.toLong())
                else loading.limit.toLong()
            data =
                Zlib.inflate(data, 0, size, limit.toInt())
                    ?: return fail(GraphicsStatus.InflateFailed)
            size = data.size
        }
        val header = PngDecoder.readSize(data, 0, size) ?: return fail(GraphicsStatus.BadPng)
        if (header.width > MAX_SIDE || header.height > MAX_SIDE) {
            return fail(GraphicsStatus.TooLarge)
        }
        return when (val result = PngDecoder.decode(data, 0, size, maxPixels)) {
            is PngDecoder.Result.Decoded ->
                newImage(result.image.width, result.image.height, result.image.pixels)
            PngDecoder.Result.TooLarge -> fail(GraphicsStatus.TooLarge)
            PngDecoder.Result.Malformed -> fail(GraphicsStatus.BadPng)
        }
    }

    private fun newImage(width: Int, height: Int, pixels: IntArray) =
        TerminalImage(nextSerial++, width, height, pixels)

    /** Gives an id that no image has, for an image that the host gave a number. */
    private fun assignId(): Long {
        while (true) {
            val id = nextAssignedId
            nextAssignedId = if (id == ProtocolLimits.MAX_GRAPHICS_NUMBER) 1L else id + 1
            if (layers.none { it.imageById(id) != null }) return id
        }
    }

    /**
     * Deletes images until [bytes], at most [maxBytes], more fit, and [layer] has room for one more
     * image: the oldest first, those without placements before the others, as kitty does.
     */
    private fun makeRoom(layer: GraphicsLayer, bytes: Long) {
        while (layer.imageCount >= MAX_IMAGES) evict(listOf(layer))
        while (this.bytes + bytes > maxBytes) evict(layers)
    }

    private fun evict(from: List<GraphicsLayer>) {
        // The oldest image without placements, or else the oldest of all.
        val victim =
            from
                .asSequence()
                .flatMap { it.images() }
                .minWith(compareBy<StoredImage> { it.hasPlacements }.thenBy { it.serial })
        victim.layer.delete(victim)
    }

    /** `a=p`: places an image that was transmitted before. */
    private fun put(command: GraphicsCommand, screen: Screen) {
        if (command.imageId != 0L && command.imageNumber != 0L) {
            respond(command, GraphicsStatus.InvalidCommand)
            return
        }
        val image =
            when {
                command.imageId != 0L -> screen.layer.imageById(command.imageId)
                command.imageNumber != 0L -> screen.layer.newestImageByNumber(command.imageNumber)
                else -> null
            }
        if (image == null) {
            respond(command, GraphicsStatus.NotFound)
            return
        }
        respond(command, place(image, command, screen), image.id)
    }

    /** Places [image] at the cursor of [screen] as [command] says, and moves the cursor. */
    private fun place(
        image: StoredImage,
        command: GraphicsCommand,
        screen: Screen,
    ): GraphicsStatus {
        val imageWidth = image.image.width
        val imageHeight = image.image.height
        if (command.x >= imageWidth || command.y >= imageHeight) {
            return GraphicsStatus.InvalidCommand
        }
        val sourceX = command.x.toInt()
        val sourceY = command.y.toInt()
        val sourceWidth =
            if (command.w == 0L) imageWidth - sourceX
            else minOf(command.w, (imageWidth - sourceX).toLong()).toInt()
        val sourceHeight =
            if (command.h == 0L) imageHeight - sourceY
            else minOf(command.h, (imageHeight - sourceY).toLong()).toInt()
        val cellWidth = cellWidth.toDouble()
        val cellHeight = cellHeight.toDouble()
        val offsetX = minOf(command.cellX, this.cellWidth - 1L).toDouble()
        val offsetY = minOf(command.cellY, this.cellHeight - 1L).toDouble()
        val maxCells = TerminalLimits.MAX_DIMENSION.toLong()
        val givenColumns = minOf(command.columns, maxCells).toInt()
        val givenRows = minOf(command.rows, maxCells).toInt()
        // The size of the image's area in pixels: the cells given, else the source's size, or its
        // aspect ratio if only one of them is given.
        var width: Double
        var height: Double
        when {
            givenColumns > 0 && givenRows > 0 -> {
                width = givenColumns * cellWidth
                height = givenRows * cellHeight
            }
            givenColumns > 0 -> {
                width = givenColumns * cellWidth
                height = sourceHeight * width / sourceWidth
            }
            givenRows > 0 -> {
                height = givenRows * cellHeight
                width = sourceWidth * height / sourceHeight
            }
            else -> {
                width = sourceWidth.toDouble()
                height = sourceHeight.toDouble()
            }
        }
        // Clamped like the cells, so that the area drawn is never larger than the cells that
        // deletions and visibility go by.
        width = minOf(width, maxCells * cellWidth)
        height = minOf(height, maxCells * cellHeight)
        val columns =
            if (givenColumns > 0) givenColumns else cells(offsetX + width, cellWidth, maxCells)
        val rows = if (givenRows > 0) givenRows else cells(offsetY + height, cellHeight, maxCells)
        val placement =
            Placement(
                image,
                id = command.placementId,
                sourceX = sourceX,
                sourceY = sourceY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                left = (offsetX / cellWidth).toFloat(),
                top = (offsetY / cellHeight).toFloat(),
                width = (width / cellWidth).toFloat(),
                height = (height / cellHeight).toFloat(),
                columns = columns,
                rows = rows,
                z = command.z,
                virtual = command.virtual == 1L,
                serial = nextSerial++,
            )
        screen.layer.add(placement, screen.cursorRow, screen.cursorColumn)
        if (!placement.virtual && command.cursorPolicy != 1L) {
            screen.moveCursorAfterImage(columns, rows)
        }
        return GraphicsStatus.Ok
    }

    /** The number of cells that [pixels] take, at least 1 and at most [max]. */
    private fun cells(pixels: Double, cellSize: Double, max: Long): Int =
        minOf(max, maxOf(1L, kotlin.math.ceil(pixels / cellSize - EPSILON).toLong())).toInt()

    /** `a=d`: deletes placements, and with an uppercase [GraphicsCommand.delete], their images. */
    private fun delete(command: GraphicsCommand, screen: Screen) {
        val layer = screen.layer
        val what = command.delete
        val freeImages = what.isUpperCase()
        val touched = LinkedHashSet<StoredImage>()
        fun removeAll(image: StoredImage, placementId: Long) {
            touched += image
            val all = image.placements + image.virtualPlacements
            for (placement in all) {
                if (placementId == 0L || placement.id == placementId) layer.remove(placement)
            }
        }
        val kind = what.lowercaseChar()
        when (kind) {
            'i' -> layer.imageById(command.imageId)?.let { removeAll(it, command.placementId) }
            'n' ->
                layer.newestImageByNumber(command.imageNumber)?.let {
                    removeAll(it, command.placementId)
                }
            'r' ->
                // removeAll deletes no image here: they all have ids.
                for (image in layer.images()) {
                    if (image.id != 0L && image.id in command.x..command.y) removeAll(image, 0L)
                }
            'f' -> return
            else -> {
                val matches: (Placement, Int) -> Boolean =
                    when (kind) {
                        'a' -> { _, _ ->
                            true
                        }
                        'c' -> { p, row ->
                            p.covers(row, screen.cursorRow.toLong(), screen.cursorColumn.toLong())
                        }
                        'p' -> { p, row ->
                            p.covers(row, command.y - 1, command.x - 1)
                        }
                        'q' -> { p, row ->
                            p.z == command.z && p.covers(row, command.y - 1, command.x - 1)
                        }
                        'x' -> { p, _ ->
                            command.x - 1 in p.column until p.column + p.columns
                        }
                        'y' -> { p, row ->
                            command.y - 1 in row until row + p.rows
                        }
                        'z' -> { p, _ ->
                            p.z == command.z
                        }
                        else -> return
                    }
                layer.forEachAnchored { placement, row ->
                    // Only placements visible on the screen.
                    if (row + placement.rows > 0 && row < screen.rows && matches(placement, row)) {
                        placement.image?.let { touched += it }
                        layer.remove(placement)
                    }
                }
            }
        }
        if (freeImages) {
            for (image in touched) if (!image.hasPlacements) layer.delete(image)
        }
    }

    private fun Placement.covers(row: Int, cellRow: Long, cellColumn: Long): Boolean =
        cellRow in row until row + rows && cellColumn in column until column + columns

    /** Deletes the placements visible on [screen]'s screen, as clearing the screen does. */
    fun deleteVisible(layer: GraphicsLayer, rows: Int) {
        layer.forEachAnchored { placement, row ->
            if (row + placement.rows > 0 && row < rows) layer.remove(placement)
        }
    }

    /** Replies to [command] with [status], if it asks for a reply, as kitty does. */
    private fun respond(command: GraphicsCommand, status: GraphicsStatus, assignedId: Long = 0L) {
        if (command.imageId == 0L && command.imageNumber == 0L) return
        if (command.quiet >= 2L || (command.quiet == 1L && status == GraphicsStatus.Ok)) return
        val id = if (command.imageId != 0L) command.imageId else assignedId
        reply(TerminalQuery.GraphicsReply(id, command.imageNumber, command.placementId, status))
    }

    /** The data of a transmission, decoded from base64 as its chunks arrive. */
    private class Transmission(
        val command: GraphicsCommand,
        val layer: GraphicsLayer,
        /** The most bytes kept; beyond it, the transmission fails. */
        val limit: Int,
    ) {
        var data = ByteArray(minOf(limit, INITIAL_CAPACITY))
            private set

        var size = 0
            private set

        var overflowed = false
            private set

        /**
         * Decodes the base64 payload in [payload] from [from], as kitty does: each chunk on its
         * own, with or without padding, up to the first character that is not base64.
         */
        fun append(payload: CharSequence, from: Int) {
            var bits = 0
            var count = 0
            for (i in from until payload.length) {
                val value = base64Value(payload[i])
                if (value < 0) break
                bits = (bits shl 6) or value
                count++
                if (count == 4) {
                    put(bits shr 16)
                    put(bits shr 8)
                    put(bits)
                    bits = 0
                    count = 0
                }
            }
            when (count) {
                2 -> put(bits shr 4)
                3 -> {
                    put(bits shr 10)
                    put(bits shr 2)
                }
            }
        }

        private fun put(byte: Int) {
            if (overflowed) return
            if (size == data.size) {
                if (size >= limit) {
                    overflowed = true
                    data = ByteArray(0)
                    size = 0
                    return
                }
                data = data.copyOf(minOf(limit.toLong(), size * 2L).toInt())
            }
            data[size++] = byte.toByte()
        }

        private fun base64Value(c: Char): Int =
            when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> -1
            }

        companion object {
            private const val INITIAL_CAPACITY = 64 * 1024
        }
    }

    companion object {
        /** The size of a cell until the display tells it (docs/decisions/0080). */
        const val DEFAULT_CELL_WIDTH = 10
        const val DEFAULT_CELL_HEIGHT = 20

        /** The largest width or height of an image, as in kitty (docs/decisions/0081). */
        const val MAX_SIDE = 10000L

        /**
         * The most memory of the pixels of one image, whatever the limit for all images: below the
         * size of a bitmap that Android refuses to draw (docs/decisions/0080).
         */
        const val MAX_BYTES_PER_IMAGE = 64L * 1024 * 1024

        /** The most images and placements of a screen (docs/decisions/0081). */
        const val MAX_IMAGES = 1024
        const val MAX_PLACEMENTS = 1024

        /** The most decoded bytes a transmission keeps, below the largest array. */
        private const val MAX_TRANSMISSION_BYTES = Int.MAX_VALUE - 8

        /** Below which a fraction of a cell does not take another cell. */
        private const val EPSILON = 1e-9
    }
}
