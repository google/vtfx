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

import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.LocaleList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.CellStyle
import com.google.vtfx.core.CursorShape
import com.google.vtfx.core.FoundLink
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.LineSnapshot
import com.google.vtfx.core.TerminalColor
import com.google.vtfx.core.TerminalLimits
import com.google.vtfx.core.TerminalSnapshot
import com.google.vtfx.core.UnderlineStyle
import kotlin.math.ceil

/** The size of a cell in pixels, and the baseline from its top. */
internal class CellMetrics(val width: Float, val height: Float, val baseline: Float) {
    companion object {
        fun measure(paints: TextPaints): CellMetrics {
            val paint = paints.regular
            val metrics = paint.fontMetrics
            return CellMetrics(
                width = paint.measureText("M".repeat(SAMPLE_LENGTH)) / SAMPLE_LENGTH,
                height = metrics.descent - metrics.ascent + metrics.leading,
                baseline = -metrics.ascent,
            )
        }

        /** Measuring several characters averages out the rounding of one. */
        private const val SAMPLE_LENGTH = 16
    }
}

/**
 * The paints for each combination of bold and italic, with the style's font, size, and locale. Text
 * is drawn with `Canvas.drawText`, without a text layout per run, which made frames slow
 * (docs/decisions/0014). The paints are mutated per run, so they are used on one thread.
 */
internal class TextPaints(
    private val terminalStyle: TerminalStyle,
    private val density: Density,
    typefaces: TextTypefaces,
) {
    private fun paint(typeface: Typeface): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.textSize = with(density) { terminalStyle.fontSize.toPx() }
        terminalStyle.locale?.let { locale ->
            paint.textLocales =
                LocaleList.forLanguageTags(locale.joinToString(",") { it.toLanguageTag() })
        }
        return paint
    }

    val regular: Paint = paint(typefaces.regular)
    private val bold = paint(typefaces.bold)
    private val italic = paint(typefaces.italic)
    private val boldItalic = paint(typefaces.boldItalic)

    fun forStyle(isBold: Boolean, isItalic: Boolean): Paint =
        when {
            isBold && isItalic -> boldItalic
            isBold -> bold
            isItalic -> italic
            else -> regular
        }
}

/**
 * Fills rectangles that tile the cell grid, such as the backgrounds of rows, without antialiasing:
 * a rectangle covers the pixels whose centers are inside it. Rectangles that share an edge then
 * cover each pixel once. The cell height is rarely a whole number of pixels, and with antialiasing
 * each of two rows covered part of the pixel on their edge, which then showed a seam of the color
 * beneath. Holds a paint that it changes, so it is used on one thread.
 */
internal class GridFill {
    private val paint = ComposePaint().apply { isAntiAlias = false }

    fun rect(scope: DrawScope, color: Color, topLeft: Offset, size: Size) {
        paint.color = color
        scope.drawContext.canvas.drawRect(
            topLeft.x,
            topLeft.y,
            topLeft.x + size.width,
            topLeft.y + size.height,
            paint,
        )
    }
}

/**
 * Draws a [TerminalSnapshot], the links found on it, and the IME's composing text.
 *
 * Runs of narrow ASCII characters with the same style are drawn as one string, which a monospace
 * font aligns to the grid. Other characters are drawn one cell at a time, since a fallback font
 * (CJK, for example) does not have the cell width.
 *
 * Box drawing characters and block elements are drawn as shapes ([BoxDrawing]), and so are
 * underlines and overlines, over whole cells (docs/decisions/0030).
 */
internal class TerminalRenderer(
    private val paints: TextPaints,
    style: TerminalStyle,
    private val metrics: CellMetrics,
    density: Density,
) {
    /**
     * The drawing of each line, by content, so that a line that did not change is not drawn again.
     */
    private val lineCache = LineCache(metrics, density)
    private val colors = style.colors

    /** The text of a run, reused across runs, since a frame draws many. */
    private val runText = StringBuilder()

    /** Fills backgrounds, the selection, and the cursor, so that rows join without seams. */
    private val fill = GridFill()

    /** The path of a curly underline, reused across runs. */
    private val wave = Path()

    /** The thickness of underlines and overlines, from the font, as for underlined text. */
    private val lineThickness = maxOf(paints.regular.underlineThickness, 1f)

    /** The center of an underline below the top of its cell, where the font puts underlines. */
    private val underlineCenter =
        metrics.baseline + paints.regular.underlinePosition + lineThickness / 2

    /** The rectangles and paint to draw images with. */
    private val imageSource = android.graphics.Rect()
    private val imageTarget = android.graphics.RectF()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun DrawScope.draw(
        snapshot: TerminalSnapshot,
        /** The bitmaps of the images of [snapshot]'s display. */
        images: ImageBitmaps,
        /**
         * The links that patterns found, underlined over the lines. OSC 8 hyperlinks are underlined
         * with their text.
         */
        links: List<FoundLink>,
        composingText: String,
        ambiguousWidth: AmbiguousWidth,
        focused: Boolean,
        selection: TerminalSelection?,
        /** The matches of a search that [snapshot] shows, highlighted under the selection. */
        searchMatches: VisibleMatches?,
        /** False during the off phase of a blinking cursor. */
        cursorOn: Boolean,
    ) {
        val reverse = snapshot.modes.reverseVideo
        val defaultForeground = if (reverse) colors.background else colors.foreground
        val defaultBackground = if (reverse) colors.foreground else colors.background
        drawRect(defaultBackground)
        images.startFrame()
        // Images with a negative z-index go under the text (docs/decisions/0038).
        drawImages(snapshot, images, underText = true)
        val nativeCanvas = drawContext.canvas.nativeCanvas
        if (nativeCanvas.isHardwareAccelerated) {
            lineCache.startFrame(snapshot.columns, reverse)
            for (row in 0 until snapshot.rows) {
                val line = snapshot.line(row)
                val node =
                    lineCache.nodeFor(line) {
                        // Drawn as row 0; the node is placed at its row.
                        drawBackgrounds(line, 0, defaultForeground, defaultBackground)
                        drawText(line, 0, defaultForeground, defaultBackground)
                        drawDecorations(line, 0, defaultForeground, defaultBackground)
                    }
                nativeCanvas.save()
                nativeCanvas.translate(0f, row * metrics.height)
                nativeCanvas.drawRenderNode(node)
                nativeCanvas.restore()
            }
            lineCache.endFrame()
        } else {
            for (row in 0 until snapshot.rows) {
                val line = snapshot.line(row)
                drawBackgrounds(line, row, defaultForeground, defaultBackground)
                drawText(line, row, defaultForeground, defaultBackground)
                drawDecorations(line, row, defaultForeground, defaultBackground)
            }
        }
        drawImages(snapshot, images, underText = false)
        drawImageTiles(snapshot, images)
        images.endFrame()
        // Over the cached lines, since a match depends on the rows that autowrap joins to a line.
        drawLinkUnderlines(links, snapshot, defaultForeground, defaultBackground)
        searchMatches?.let { drawSearchMatches(it, snapshot) }
        selection?.let { drawSelection(it, snapshot) }
        // The cursor is in the screen's coordinates; the screen is shifted down when the display
        // shows the scrollback.
        val cursorColumn = snapshot.cursor.column
        val cursorRow = snapshot.cursor.row + snapshot.scrollOffset
        if (cursorRow >= snapshot.rows) return
        // In reverse video, the cursor color would often match the background.
        val cursorColor = if (reverse) defaultForeground else colors.cursor
        val cursorTextColor =
            if (reverse || colors.cursorText.isUnspecified) defaultBackground else colors.cursorText
        if (composingText.isNotEmpty()) {
            val layout =
                ComposingLayout(
                    composingText,
                    cursorColumn,
                    cursorRow,
                    snapshot.columns,
                    snapshot.rows,
                    ambiguousWidth,
                )
            drawComposing(
                layout,
                composingText,
                snapshot.rows,
                cursorColor,
                defaultForeground,
                defaultBackground,
            )
        } else if (snapshot.modes.cursorVisible && (cursorOn || !focused)) {
            drawCursor(
                snapshot,
                cursorColumn,
                cursorRow,
                focused,
                cursorColor,
                cursorTextColor,
                defaultForeground,
                defaultBackground,
            )
        }
    }

    /**
     * Draws the images of [snapshot] with a negative z-index if [underText], else the others, cut
     * at the edges of the grid. Images under the text are drawn under the backgrounds of cells as
     * well, which kitty does only below a z-index of -1073741824: the lines are recorded with their
     * backgrounds.
     */
    private fun DrawScope.drawImages(
        snapshot: TerminalSnapshot,
        images: ImageBitmaps,
        underText: Boolean,
    ) {
        val placements = snapshot.imagePlacements
        if (placements.isEmpty()) return
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.clipRect(0f, 0f, snapshot.columns * metrics.width, snapshot.rows * metrics.height)
        for (placement in placements) {
            if ((placement.z < 0) != underText) continue
            imageSource.set(
                placement.sourceX,
                placement.sourceY,
                placement.sourceX + placement.sourceWidth,
                placement.sourceY + placement.sourceHeight,
            )
            val left = (placement.column + placement.left) * metrics.width
            val top = (placement.row + placement.top) * metrics.height
            imageTarget.set(
                left,
                top,
                left + placement.width * metrics.width,
                top + placement.height * metrics.height,
            )
            canvas.drawBitmap(
                images.bitmapOf(placement.image),
                imageSource,
                imageTarget,
                imagePaint,
            )
        }
        canvas.restore()
    }

    /**
     * Draws the placeholders of images: each cell shows its part of the image fitted into the box
     * of its virtual placement, keeping the aspect ratio, and centered (docs/decisions/0038). The
     * whole image is drawn at the box's place, cut to the cell.
     */
    private fun DrawScope.drawImageTiles(snapshot: TerminalSnapshot, images: ImageBitmaps) {
        val tiles = snapshot.imageTiles
        if (tiles.isEmpty()) return
        val canvas = drawContext.canvas.nativeCanvas
        val cellWidth = metrics.width
        val cellHeight = metrics.height
        for (tile in tiles) {
            val image = tile.image
            val boxWidth = tile.columns * cellWidth
            val boxHeight = tile.rows * cellHeight
            val scale = minOf(boxWidth / image.width, boxHeight / image.height)
            val width = image.width * scale
            val height = image.height * scale
            // The top left of the box, so that the cell is at its row and column of the box.
            val boxLeft = (tile.column - tile.imageColumn) * cellWidth
            val boxTop = (tile.row - tile.imageRow) * cellHeight
            val left = boxLeft + (boxWidth - width) / 2
            val top = boxTop + (boxHeight - height) / 2
            canvas.save()
            canvas.clipRect(
                tile.column * cellWidth,
                tile.row * cellHeight,
                (tile.column + 1) * cellWidth,
                (tile.row + 1) * cellHeight,
            )
            imageTarget.set(left, top, left + width, top + height)
            canvas.drawBitmap(images.bitmapOf(image), null, imageTarget, imagePaint)
            canvas.restore()
        }
    }

    private fun DrawScope.drawLinkUnderlines(
        links: List<FoundLink>,
        snapshot: TerminalSnapshot,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        if (links.isEmpty()) return
        val thickness = lineThickness
        val y = underlineCenter
        for (link in links) {
            for (span in link.spans) {
                val color =
                    foreground(
                        snapshot.line(span.row).styleAt(span.start),
                        defaultForeground,
                        defaultBackground,
                    )
                val top = span.row * metrics.height
                drawLine(
                    color,
                    Offset(span.start * metrics.width, top + y),
                    Offset(span.end * metrics.width, top + y),
                    thickness,
                )
            }
        }
    }

    private fun DrawScope.drawSearchMatches(visible: VisibleMatches, snapshot: TerminalSnapshot) {
        for (match in visible.matches) {
            val color =
                if (match == visible.current) colors.searchCurrentMatch else colors.searchMatch
            val firstRow = match.firstLine - snapshot.firstLine
            val lastRow = match.lastLine - snapshot.firstLine
            for (row in maxOf(firstRow, 0L)..minOf(lastRow, snapshot.rows - 1L)) {
                val from = if (row == firstRow) match.startColumn else 0
                val to = if (row == lastRow) match.endColumn else snapshot.columns
                fill.rect(
                    this,
                    color,
                    Offset(from * metrics.width, row * metrics.height),
                    Size((to - from) * metrics.width, metrics.height),
                )
            }
        }
    }

    private fun DrawScope.drawSelection(selection: TerminalSelection, snapshot: TerminalSnapshot) {
        val start = selection.start
        val end = selection.end
        for (row in start.row..minOf(end.row, snapshot.rows - 1)) {
            val from = if (row == start.row) start.column else 0
            val to = if (row == end.row) end.column + 1 else snapshot.columns
            fill.rect(
                this,
                colors.selection,
                Offset(from * metrics.width, row * metrics.height),
                Size((to - from) * metrics.width, metrics.height),
            )
        }
    }

    private fun DrawScope.drawBackgrounds(
        line: LineSnapshot,
        row: Int,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        var start = 0
        while (start < line.columns) {
            val color = background(line.styleAt(start), defaultForeground, defaultBackground)
            var end = start + 1
            while (
                end < line.columns &&
                    background(line.styleAt(end), defaultForeground, defaultBackground) == color
            ) end++
            if (color != defaultBackground) {
                fill.rect(
                    this,
                    color,
                    Offset(start * metrics.width, row * metrics.height),
                    Size((end - start) * metrics.width, metrics.height),
                )
            }
            start = end
        }
    }

    private fun DrawScope.drawText(
        line: LineSnapshot,
        row: Int,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        var column = 0
        while (column < line.columns) {
            val text = line.textAt(column)
            val cellStyle = line.styleAt(column)
            if (text == null || text == " " || cellStyle.isInvisible) {
                column++
                continue
            }
            if (text.length == 1 && BoxDrawing.isDrawn(text[0].code)) {
                val color = foreground(cellStyle, defaultForeground, defaultBackground)
                // Over both cells of a wide one: these are East Asian Ambiguous, two cells wide
                // when the session takes them so.
                val width = line.widthAt(column)
                with(BoxDrawing) {
                    drawBox(
                        text[0].code,
                        Offset(column * metrics.width, row * metrics.height),
                        Size(width * metrics.width, metrics.height),
                        color,
                        fill,
                    )
                }
                column += width
                continue
            }
            if (!isGridAligned(text, line, column)) {
                drawRun(text, column, row, cellStyle, defaultForeground, defaultBackground)
                column += line.widthAt(column)
                continue
            }
            val run = runText
            run.setLength(0)
            run.append(text)
            var end = column + 1
            while (end < line.columns && line.styleAt(end) == cellStyle) {
                val next = line.textAt(end) ?: break
                if (!isGridAligned(next, line, end)) break
                run.append(next)
                end++
            }
            drawRun(run, column, row, cellStyle, defaultForeground, defaultBackground)
            column = end
        }
    }

    private fun isGridAligned(text: String, line: LineSnapshot, column: Int): Boolean =
        text.length == 1 && text[0] in ' '..'~' && line.widthAt(column) == 1

    private fun DrawScope.drawRun(
        text: CharSequence,
        column: Int,
        row: Int,
        cellStyle: CellStyle,
        defaultForeground: Color,
        defaultBackground: Color,
        colorOverride: Color? = null,
    ) {
        val color = textColor(cellStyle, defaultForeground, defaultBackground, colorOverride)
        val paint = paints.forStyle(cellStyle.isBold, cellStyle.isItalic)
        paint.color = color.toArgb()
        paint.isStrikeThruText = cellStyle.isStrikethrough
        drawContext.canvas.nativeCanvas.drawText(
            text,
            0,
            text.length,
            column * metrics.width,
            row * metrics.height + metrics.baseline,
            paint,
        )
    }

    /** The color of the text of a cell with [cellStyle], or of [colorOverride], with faint. */
    private fun textColor(
        cellStyle: CellStyle,
        defaultForeground: Color,
        defaultBackground: Color,
        colorOverride: Color? = null,
    ): Color {
        val color = colorOverride ?: foreground(cellStyle, defaultForeground, defaultBackground)
        return if (cellStyle.isFaint) color.copy(alpha = color.alpha * FAINT_ALPHA) else color
    }

    /**
     * Draws the underlines and overlines of [line], over whole cells: spaces written with an
     * underline are underlined too, as in xterm. OSC 8 hyperlinks are underlined, so that they can
     * be told from plain text, unless the cell has an underline of its own.
     */
    private fun DrawScope.drawDecorations(
        line: LineSnapshot,
        row: Int,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        var column = 0
        while (column < line.columns) {
            val cellStyle = line.styleAt(column)
            val linked = line.hyperlinkAt(column) != null
            if (
                cellStyle.isInvisible ||
                    (cellStyle.underline == UnderlineStyle.None &&
                        !cellStyle.isOverlined &&
                        !linked)
            ) {
                column++
                continue
            }
            val underlineColor = line.underlineColorAt(column)
            var end = column + 1
            while (
                end < line.columns &&
                    line.styleAt(end) == cellStyle &&
                    (line.hyperlinkAt(end) != null) == linked &&
                    line.underlineColorAt(end) == underlineColor
            ) end++
            drawDecorations(
                column,
                end,
                row,
                cellStyle,
                underlineColor,
                linked,
                defaultForeground,
                defaultBackground,
            )
            column = end
        }
    }

    /**
     * Draws the underline and overline of the cells [start] until [end] of [row]. The patterns are
     * anchored to the cell grid, so that runs drawn separately join, and the lines are kept inside
     * the cells.
     */
    private fun DrawScope.drawDecorations(
        start: Int,
        end: Int,
        row: Int,
        cellStyle: CellStyle,
        underlineColor: TerminalColor,
        linked: Boolean,
        defaultForeground: Color,
        defaultBackground: Color,
        colorOverride: Color? = null,
    ) {
        val textColor = textColor(cellStyle, defaultForeground, defaultBackground, colorOverride)
        val thickness = lineThickness
        val top = row * metrics.height
        val bottom = top + metrics.height
        val left = start * metrics.width
        val right = end * metrics.width
        if (cellStyle.isOverlined) {
            val y = top + thickness / 2
            drawLine(textColor, Offset(left, y), Offset(right, y), thickness)
        }
        val underline =
            if (cellStyle.underline == UnderlineStyle.None && linked) UnderlineStyle.Single
            else cellStyle.underline
        if (underline == UnderlineStyle.None) return
        val color =
            if (colorOverride != null || underlineColor.isDefault) textColor
            else colors.resolve(underlineColor, defaultForeground)
        val y = top + underlineCenter
        when (underline) {
            UnderlineStyle.None -> {}
            UnderlineStyle.Single -> {
                val lineY = minOf(y, bottom - thickness / 2)
                drawLine(color, Offset(left, lineY), Offset(right, lineY), thickness)
            }
            UnderlineStyle.Double -> {
                val gap = thickness * 2
                val first = minOf(y, bottom - gap - thickness / 2)
                drawLine(color, Offset(left, first), Offset(right, first), thickness)
                drawLine(color, Offset(left, first + gap), Offset(right, first + gap), thickness)
            }
            UnderlineStyle.Curly -> {
                // One period per cell, from the center up, down, and back.
                val amplitude = thickness
                val center = minOf(y, bottom - amplitude - thickness / 2)
                val path = wave
                path.reset()
                path.moveTo(left, center)
                val width = metrics.width
                for (column in start until end) {
                    val x = column * width
                    path.quadraticTo(x + width / 4, center - 2 * amplitude, x + width / 2, center)
                    path.quadraticTo(x + width * 3 / 4, center + 2 * amplitude, x + width, center)
                }
                drawPath(path, color, style = Stroke(width = thickness))
            }
            UnderlineStyle.Dotted -> {
                val lineY = minOf(y, bottom - thickness / 2)
                val period = thickness * 2
                drawLine(
                    color,
                    Offset(left, lineY),
                    Offset(right, lineY),
                    thickness,
                    pathEffect =
                        PathEffect.dashPathEffect(
                            floatArrayOf(thickness, thickness),
                            left % period,
                        ),
                )
            }
            UnderlineStyle.Dashed -> {
                val lineY = minOf(y, bottom - thickness / 2)
                val width = metrics.width
                val dash = width * DASH_FRACTION
                drawLine(
                    color,
                    Offset(left, lineY),
                    Offset(right, lineY),
                    thickness,
                    pathEffect =
                        PathEffect.dashPathEffect(floatArrayOf(dash, width - dash), left % width),
                )
            }
        }
    }

    private fun DrawScope.drawCursor(
        snapshot: TerminalSnapshot,
        cursorColumn: Int,
        cursorRow: Int,
        focused: Boolean,
        cursorColor: Color,
        cursorTextColor: Color,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        val line = snapshot.line(cursorRow)
        var column = cursorColumn
        if (line.widthAt(column) == 0 && column > 0) column--
        val width = maxOf(line.widthAt(column), 1)
        val topLeft = Offset(column * metrics.width, cursorRow * metrics.height)
        val size = Size(width * metrics.width, metrics.height)
        if (!focused) {
            drawRect(cursorColor, topLeft, size, style = Stroke(width = 1f))
            return
        }
        when (snapshot.modes.cursorShape) {
            CursorShape.Underline -> {
                fill.rect(
                    this,
                    cursorColor,
                    Offset(topLeft.x, topLeft.y + size.height - CURSOR_LINE_WIDTH),
                    Size(size.width, CURSOR_LINE_WIDTH),
                )
                return
            }
            CursorShape.Bar -> {
                fill.rect(this, cursorColor, topLeft, Size(CURSOR_LINE_WIDTH, size.height))
                return
            }
            CursorShape.Block -> fill.rect(this, cursorColor, topLeft, size)
        }
        val cellStyle = line.styleAt(column)
        if (cellStyle.isInvisible) return
        line.textAt(column)?.let { text ->
            drawRun(
                text,
                column,
                cursorRow,
                cellStyle,
                defaultForeground,
                defaultBackground,
                colorOverride = cursorTextColor,
            )
        }
        // The block hides the cell's underline and overline; they are drawn again over it.
        if (cellStyle.underline != UnderlineStyle.None || cellStyle.isOverlined) {
            drawDecorations(
                column,
                column + width,
                cursorRow,
                cellStyle,
                line.underlineColorAt(column),
                linked = false,
                defaultForeground,
                defaultBackground,
                colorOverride = cursorTextColor,
            )
        }
    }

    private fun DrawScope.drawComposing(
        layout: ComposingLayout,
        text: String,
        rows: Int,
        cursorColor: Color,
        defaultForeground: Color,
        defaultBackground: Color,
    ) {
        val composingStyle = CellStyle.Default
        for (cell in layout.cells) {
            val topLeft = Offset(cell.column * metrics.width, cell.row * metrics.height)
            fill.rect(
                this,
                defaultBackground,
                topLeft,
                Size(cell.width * metrics.width, metrics.height),
            )
            drawRun(
                text.substring(cell.start, cell.end),
                cell.column,
                cell.row,
                composingStyle,
                defaultForeground,
                defaultBackground,
            )
            // The underline marks the text as not yet sent.
            val underlineY = (cell.row + 1) * metrics.height - UNDERLINE_WIDTH
            drawLine(
                defaultForeground,
                Offset(topLeft.x, underlineY),
                Offset(topLeft.x + cell.width * metrics.width, underlineY),
                UNDERLINE_WIDTH,
            )
        }
        // The cursor after the composing text, unless it is below the grid.
        if (layout.endRow < rows)
            drawRect(
                cursorColor,
                Offset(layout.endColumn * metrics.width, layout.endRow * metrics.height),
                Size(UNDERLINE_WIDTH * 2, metrics.height),
            )
    }

    private fun foreground(
        cellStyle: CellStyle,
        defaultForeground: Color,
        defaultBackground: Color,
    ): Color =
        if (cellStyle.isInverse) {
            colors.resolve(cellStyle.background, defaultBackground)
        } else {
            colors.resolve(cellStyle.foreground, defaultForeground)
        }

    private fun background(
        cellStyle: CellStyle,
        defaultForeground: Color,
        defaultBackground: Color,
    ): Color =
        if (cellStyle.isInverse) {
            colors.resolve(cellStyle.foreground, defaultForeground)
        } else {
            colors.resolve(cellStyle.background, defaultBackground)
        }

    private companion object {
        const val FAINT_ALPHA = 0.5f
        const val UNDERLINE_WIDTH = 2f
        const val CURSOR_LINE_WIDTH = 3f

        /** The part of a cell that the dash of a dashed underline takes. */
        const val DASH_FRACTION = 0.6f
    }
}

/**
 * The drawing of lines, recorded in `RenderNode`s and keyed by the lines' content, so that a frame
 * draws again only the lines that changed, even when they moved to another row by scrolling
 * (docs/decisions/0014). Nodes not used in the last two frames are released, and kept for reuse by
 * later lines, since a screen that changes entirely replaces every node on every frame.
 *
 * The renderer discards the display list of a node that a frame did not draw, so a node is recorded
 * again if it lost its display list, as `View` does.
 */
internal class LineCache(private val metrics: CellMetrics, private val density: Density) {
    /** A line, equal to the lines of the same content. Its hash is computed once. */
    private class Key(val line: LineSnapshot) {
        private val hash = line.contentHashCode()

        override fun equals(other: Any?): Boolean = other is Key && line.contentEquals(other.line)

        override fun hashCode(): Int = hash
    }

    private class Entry(val node: RenderNode, var frame: Int)

    private val entries = HashMap<Key, Entry>()
    private val unusedNodes = ArrayDeque<RenderNode>()
    private val recorder = CanvasDrawScope()
    private var frame = 0
    private var columns = -1
    private var reverse = false

    fun startFrame(columns: Int, reverse: Boolean) {
        frame++
        // The width and the colors of every line change with these.
        if (columns != this.columns || reverse != this.reverse) {
            clear()
            this.columns = columns
            this.reverse = reverse
        }
    }

    fun nodeFor(line: LineSnapshot, draw: DrawScope.() -> Unit): RenderNode {
        val key = Key(line)
        entries[key]?.let {
            it.frame = frame
            if (!it.node.hasDisplayList()) record(it.node, draw)
            return it.node
        }
        val node = unusedNodes.removeLastOrNull() ?: RenderNode("vtfx-line")
        record(node, draw)
        entries[key] = Entry(node, frame)
        return node
    }

    private fun record(node: RenderNode, draw: DrawScope.() -> Unit) {
        val width = columns * metrics.width
        node.setPosition(0, 0, ceil(width).toInt(), ceil(metrics.height).toInt())
        // Glyphs may reach outside the cell, as fallback fonts and underlines do.
        node.clipToBounds = false
        val canvas = node.beginRecording()
        recorder.draw(
            density,
            LayoutDirection.Ltr,
            Canvas(canvas),
            Size(width, metrics.height),
            draw,
        )
        node.endRecording()
    }

    fun endFrame() {
        entries.values.removeIf { entry ->
            (entry.frame < frame - 1).also { if (it) release(entry.node) }
        }
    }

    private fun release(node: RenderNode) {
        node.discardDisplayList()
        // At most as many as two frames use, so that a shrinking screen does not keep them all.
        if (unusedNodes.size < MAX_UNUSED_NODES) unusedNodes.addLast(node)
    }

    private fun clear() {
        entries.values.forEach { release(it.node) }
        entries.clear()
    }

    private companion object {
        val MAX_UNUSED_NODES = 2 * TerminalLimits.MAX_DIMENSION
    }
}
