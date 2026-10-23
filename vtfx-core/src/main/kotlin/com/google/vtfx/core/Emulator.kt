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

package com.google.vtfx.core

import com.google.vtfx.core.graphics.Graphics
import com.google.vtfx.core.graphics.GraphicsLayer
import com.google.vtfx.core.graphics.Placeholders
import com.google.vtfx.core.parser.Params
import com.google.vtfx.core.parser.ParserHandler
import java.util.Base64

/**
 * Applies parsed control functions to the terminal state.
 *
 * Coordinates are 0-based. The semantics follow DEC STD 070 and xterm's ctlseqs
 * (docs/design/emulation.md section 1).
 */
internal class Emulator(
    columns: Int,
    rows: Int,
    limits: TerminalLimits,
    private val ambiguousWidth: AmbiguousWidth,
    private val listener: TerminalEventListener,
) : ParserHandler {
    var columns: Int = columns
        private set

    var rows: Int = rows
        private set

    val scrollback = Scrollback(limits.scrollbackLines)
    private val mainScreen = ScreenBuffer(columns, rows, scrollback)
    private val alternateScreen = ScreenBuffer(columns, rows, null)
    var screen: ScreenBuffer = mainScreen
        private set

    val isAlternateScreen: Boolean
        get() = screen === alternateScreen

    var cursorX: Int = 0
        private set

    var cursorY: Int = 0
        private set

    /** Set after writing the last column with autowrap on: the next character wraps first. */
    var pendingWrap: Boolean = false
        private set

    /**
     * The number of times the cursor went to another logical line: to a row that autowrap does not
     * join with its own, or onto a line that a line feed or a reverse index scrolled in. Autowrap,
     * and moves between rows that autowrap joined, do not count.
     */
    var lineChanges: Long = 0L
        private set

    /** The rendition that SGR set: the style and the underline color of printed characters. */
    private var rendition = Rendition.Default
    private val style: CellStyle
        get() = rendition.style

    private var mainSavedCursor = SavedCursor()
    private var alternateSavedCursor = SavedCursor()

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var tabStops = defaultTabStops(columns)
    private var insertMode = false
    private var originMode = false
    private var autowrap = true
    var modes: TerminalModes = TerminalModes()
        private set

    /** The cursor style that the app chose, shown until the host sets one (DECSCUSR, mode 12). */
    private var defaultCursorShape = CursorShape.Block
    private var defaultCursorBlinking = false

    /** Whether the host set the cursor style, which then wins over the app's default. */
    private var cursorStyleFromHost = false

    /** The last graphic character printed, for REP, or -1. */
    private var lastPrinted = -1

    private var charsets = CharsetState()

    private val hyperlinks = HyperlinkTable(MAX_HYPERLINKS)

    /** The hyperlink of the characters printed now (OSC 8), or 0. */
    private var activeLink = 0

    /** The window title that the host set last (OSC 0 and 2). Untrusted text. */
    private var title = ""
    private val savedTitles = TitleStack(MAX_SAVED_TITLES, limits.maxStringLength)

    /** Images of the kitty graphics protocol (docs/decisions/0038), one layer per screen. */
    val graphics = Graphics(limits.maxImageBytes.toLong()) { reply(it) }
    private val mainLayer = GraphicsLayer(graphics, mainScreen, scrollback)
    private val alternateLayer = GraphicsLayer(graphics, alternateScreen, null)

    init {
        graphics.register(mainLayer)
        graphics.register(alternateLayer)
    }

    /** The images and placements of the screen displayed. */
    val layer: GraphicsLayer
        get() = if (isAlternateScreen) alternateLayer else mainLayer

    private val graphicsScreen =
        object : Graphics.Screen {
            override val layer: GraphicsLayer
                get() = this@Emulator.layer

            override val cursorColumn: Int
                get() = cursorX

            override val cursorRow: Int
                get() = cursorY

            override val rows: Int
                get() = this@Emulator.rows

            override fun moveCursorAfterImage(columns: Int, rows: Int) =
                this@Emulator.moveCursorAfterImage(columns, rows)
        }

    // --- Printing ---

    override fun print(codePoint: Int) {
        // REP repeats the character as received, before the character set maps it.
        lastPrinted = codePoint
        printMapped(if (codePoint < 0x7F) charsets.map(codePoint) else codePoint, insertMode)
    }

    override fun printAscii(bytes: ByteArray, from: Int, to: Int) {
        if (insertMode || !charsets.isAscii) {
            for (i in from until to) print(bytes[i].toInt())
            return
        }
        // Plain text: one cell per character, written directly.
        val styleBits = style.bits
        val underlineColor = rendition.cellUnderlineColor
        var i = from
        while (i < to) {
            if (pendingWrap && autowrap) wrap()
            val line = screen[cursorY]
            // The characters that fit on this line before the last column.
            val run = minOf(to - i, columns - 1 - cursorX)
            for (k in 0 until run) {
                line.setNarrow(
                    cursorX + k,
                    bytes[i + k].toInt(),
                    styleBits,
                    activeLink,
                    underlineColor,
                )
            }
            cursorX += run
            i += run
            if (i < to) {
                // The last column: the next character wraps first, as print does.
                line.setNarrow(cursorX, bytes[i].toInt(), styleBits, activeLink, underlineColor)
                pendingWrap = autowrap
                i++
            }
        }
        lastPrinted = bytes[to - 1].toInt()
    }

    /**
     * Prints [codePoint], after the character set. With [insert], it first inserts the cells it
     * takes, shifting the rest of the line (IRM).
     */
    private fun printMapped(codePoint: Int, insert: Boolean) {
        if (isRegionalIndicator(codePoint) && pairRegionalIndicator(codePoint)) return
        val width = CharWidth.of(codePoint, ambiguousWidth)
        if (width == 0) {
            combine(codePoint)
            return
        }
        if (pendingWrap && autowrap) wrap()
        if (width == 2 && cursorX == columns - 1) {
            if (columns < 2) return
            if (autowrap) {
                screen[cursorY].erase(cursorX, columns, style.erased())
                wrap()
            } else {
                cursorX = columns - 2
            }
        }
        val line = screen[cursorY]
        if (insert) line.insertBlanks(cursorX, width, style.erased())
        // A placeholder of an image keeps the underline color without an underline: it is the
        // placement id (docs/decisions/0038).
        val underlineColor =
            if (codePoint == Placeholders.CODE_POINT) rendition.underlineColor.bits
            else rendition.cellUnderlineColor
        line.set(cursorX, codePoint, width, style, activeLink, underlineColor)
        advanceCursor(width)
    }

    /**
     * Moves the cursor past [cells] just written, stopping at the last column, where the next
     * character wraps first if autowrap is on.
     */
    private fun advanceCursor(cells: Int) {
        val next = cursorX + cells
        if (next >= columns) {
            cursorX = columns - 1
            pendingWrap = autowrap
        } else {
            cursorX = next
        }
    }

    /**
     * REP. In insert mode, each character would shift the rest of the line, so a few bytes would
     * shift lines count times; instead, the cells for the characters that fit on the cursor's row
     * are inserted at once, with the same result.
     */
    private fun repeatLastPrinted(count: Int) {
        val mapped = if (lastPrinted < 0x7F) charsets.map(lastPrinted) else lastPrinted
        val width = CharWidth.of(mapped, ambiguousWidth)
        if (!insertMode || width == 0) {
            repeat(count) { print(lastPrinted) }
            return
        }
        var left = count
        while (left > 0) {
            if (pendingWrap && autowrap) wrap()
            val run = minOf(left, (columns - cursorX) / width)
            if (run == 0) {
                // A wide character at the last column, which print moves or wraps.
                print(lastPrinted)
                left--
                continue
            }
            screen[cursorY].insertBlanks(cursorX, run * width, style.erased())
            repeat(run) { printMapped(mapped, insert = false) }
            left -= run
        }
    }

    /**
     * Joins [codePoint] to an unpaired regional indicator just before the cursor, making a flag
     * that occupies the two cells the pair takes anyway (docs/decisions/0011). Returns false if
     * there is no such indicator.
     */
    private fun pairRegionalIndicator(codePoint: Int): Boolean {
        if (pendingWrap || insertMode || cursorX == 0) return false
        val column = cursorX - 1
        val line = screen[cursorY]
        val previous = line.textAt(column) ?: return false
        if (
            previous.codePointCount(0, previous.length) != 1 ||
                !isRegionalIndicator(previous.codePointAt(0))
        )
            return false
        if (line.isWideHead(column) || line.linkAt(column) != activeLink) return false
        line.makeWide(column, codePoint)
        advanceCursor(1)
        return true
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

    private fun combine(codePoint: Int) {
        var column = if (pendingWrap) cursorX else cursorX - 1
        if (column < 0) return
        val line = screen[cursorY]
        if (line.codes[column] == Line.WIDE_TAIL) column--
        if (column >= 0) line.combine(column, codePoint)
    }

    /** Moves to the start of the next line, marking the current line as wrapped. */
    private fun wrap() {
        screen[cursorY].isWrapped = true
        cursorX = 0
        index()
    }

    // --- C0 controls ---

    override fun execute(control: Int) {
        when (control) {
            BEL -> listener.onEvent(TerminalEvent.Bell)
            BS ->
                if (cursorX > 0) {
                    cursorX--
                    pendingWrap = false
                }
            HT -> forwardTab(1)
            LF,
            VT,
            FF -> {
                index()
                if (modes.newLine) cursorX = 0
            }
            CR -> {
                cursorX = 0
                pendingWrap = false
            }
            SO -> charsets = charsets.copy(shiftOut = true)
            SI -> charsets = charsets.copy(shiftOut = false)
        }
    }

    /**
     * IND: moves down, scrolling the region up at the bottom margin. Either way, the cursor goes on
     * to the next line, which continues its line if [wrap] marked it.
     */
    private fun index() {
        pendingWrap = false
        if (cursorY == scrollBottom) {
            if (!screen[cursorY].isWrapped) lineChanges++
            screen.scrollUp(scrollTop, scrollBottom, 1, style, saveToScrollback = true)
        } else if (cursorY < rows - 1) {
            if (!screen[cursorY].isWrapped) lineChanges++
            cursorY++
        }
    }

    /** RI: moves up, scrolling the region down at the top margin. */
    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == scrollTop) {
            // The cursor is on the new blank line.
            lineChanges++
            screen.scrollDown(scrollTop, scrollBottom, 1, style)
        } else if (cursorY > 0) {
            if (!screen[cursorY - 1].isWrapped) lineChanges++
            cursorY--
        }
    }

    // The tab movements stop at the margin, so that the count (up to 65535) does no work beyond it.

    private fun forwardTab(count: Int) {
        var x = cursorX
        var remaining = count
        while (remaining > 0 && x < columns - 1) {
            x++
            while (x < columns - 1 && !tabStops[x]) x++
            remaining--
        }
        cursorX = minOf(x, columns - 1)
    }

    private fun backwardTab(count: Int) {
        var x = cursorX
        var remaining = count
        while (remaining > 0 && x > 0) {
            x--
            while (x > 0 && !tabStops[x]) x--
            remaining--
        }
        cursorX = maxOf(x, 0)
        pendingWrap = false
    }

    // --- Escape sequences ---

    override fun escDispatch(intermediates: Int, final: Int) {
        when (intermediates) {
            0 ->
                when (final.toChar()) {
                    '7' -> saveCursor()
                    '8' -> restoreCursor()
                    'D' -> index()
                    'E' -> {
                        index()
                        cursorX = 0
                    }
                    'H' -> tabStops[cursorX] = true
                    'M' -> reverseIndex()
                    'c' -> reset()
                    // DECID: an obsolete form of DA1.
                    'Z' -> reply(TerminalQuery.PrimaryDeviceAttributes)
                    '=' -> modes = modes.copy(applicationKeypad = true)
                    '>' -> modes = modes.copy(applicationKeypad = false)
                }
            '#'.code -> if (final == '8'.code) screenAlignment()
            '('.code,
            ')'.code,
            '*'.code,
            '+'.code ->
                CharacterSet.forFinal(final)?.let { set ->
                    charsets = charsets.designate(intermediates - '('.code, set)
                }
        }
    }

    /** DECALN: fills the screen with 'E' and resets the margins. */
    private fun screenAlignment() {
        scrollTop = 0
        scrollBottom = rows - 1
        for (row in 0 until rows) {
            val line = screen[row]
            line.clear(CellStyle.Default)
            for (column in 0 until columns) line.set(column, 'E'.code, 1, CellStyle.Default)
        }
        moveTo(0, 0)
    }

    // --- Control sequences ---

    override fun csiDispatch(params: Params, prefix: Int, intermediates: Int, final: Int) {
        when (prefix) {
            0 ->
                when (intermediates) {
                    0 -> csiDispatch(params, final.toChar())
                    '!'.code -> if (final == 'p'.code) softReset()
                    ' '.code -> if (final == 'q'.code) setCursorStyle(params[0])
                    '$'.code -> if (final == 'p'.code) reportMode(params[0], isDecPrivate = false)
                }
            '?'.code ->
                if (intermediates == '$'.code) {
                    if (final == 'p'.code) reportMode(params[0], isDecPrivate = true)
                } else if (intermediates == 0) {
                    when (final.toChar()) {
                        'h' -> for (i in 0 until params.size) setDecMode(params[i], true)
                        'l' -> for (i in 0 until params.size) setDecMode(params[i], false)
                        'J' -> eraseInDisplay(params[0])
                        'K' -> eraseInLine(params[0])
                        'n' -> if (params[0] == 6) reportCursorPosition(isDecPrivate = true)
                    }
                }
            '>'.code ->
                if (intermediates == 0 && params[0] == 0) {
                    when (final.toChar()) {
                        'c' -> reply(TerminalQuery.SecondaryDeviceAttributes)
                        'q' -> reply(TerminalQuery.TerminalVersion)
                    }
                }
        }
    }

    private fun csiDispatch(params: Params, final: Char) {
        val n = params.getOrDefault(0, 1)
        when (final) {
            '@' -> {
                pendingWrap = false
                screen[cursorY].insertBlanks(cursorX, n, style.erased())
            }
            'A' -> cursorUp(n)
            'B',
            'e' -> cursorDown(n)
            'C',
            'a' -> moveTo(cursorX + n, cursorY)
            'D' -> moveTo(cursorX - n, cursorY)
            'E' -> {
                cursorDown(n)
                cursorX = 0
            }
            'F' -> {
                cursorUp(n)
                cursorX = 0
            }
            'G',
            '`' -> moveTo(n - 1, cursorY)
            'H',
            'f' -> cursorPosition(params.getOrDefault(0, 1), params.getOrDefault(1, 1))
            'I' -> forwardTab(n)
            'J' -> eraseInDisplay(params[0])
            'K' -> eraseInLine(params[0])
            'L' -> insertLines(n)
            'M' -> deleteLines(n)
            'P' -> {
                pendingWrap = false
                screen[cursorY].deleteChars(cursorX, n, style.erased())
            }
            'S' -> screen.scrollUp(scrollTop, scrollBottom, n, style, saveToScrollback = true)
            // With more parameters, CSI T is mouse highlight tracking, which is not supported.
            'T' -> if (params.size <= 1) screen.scrollDown(scrollTop, scrollBottom, n, style)
            'X' -> {
                pendingWrap = false
                screen[cursorY].erase(cursorX, cursorX + n, style.erased())
            }
            'Z' -> backwardTab(n)
            // Repeating more than a screenful only scrolls, so the count is bounded to keep a few
            // bytes from costing unbounded work (docs/decisions/0002).
            'b' -> if (lastPrinted >= 0) repeatLastPrinted(minOf(n, columns * rows))
            'c' -> if (params[0] == 0) reply(TerminalQuery.PrimaryDeviceAttributes)
            'd' -> cursorPosition(n, cursorX + 1)
            'g' ->
                when (params[0]) {
                    0 -> tabStops[cursorX] = false
                    3 -> tabStops.fill(false)
                }
            'h' -> for (i in 0 until params.size) setAnsiMode(params[i], true)
            'l' -> for (i in 0 until params.size) setAnsiMode(params[i], false)
            'm' -> rendition = Sgr.apply(rendition, params)
            'n' ->
                when (params[0]) {
                    5 -> reply(TerminalQuery.OperatingStatus)
                    6 -> reportCursorPosition(isDecPrivate = false)
                }
            'r' -> setScrollingRegion(params.getOrDefault(0, 1), params.getOrDefault(1, rows))
            's' -> saveCursor()
            't' ->
                when (params[0]) {
                    // XTWINOPS 14;2 asks for the window, which here is the text area too.
                    14 -> if (params[1] == 0 || params[1] == 2) reportPixelSize(cell = false)
                    16 -> reportPixelSize(cell = true)
                    18 -> reply(TerminalQuery.TextAreaSize(rows, columns))
                    22,
                    23 -> saveOrRestoreTitle(params)
                }
            'u' -> restoreCursor()
        }
    }

    /**
     * XTWINOPS 22 saves the window title and 23 restores it, for 0 (the title and the icon name)
     * and 2 (the title). vtfx has no icon name, so 1 (the icon name alone) does nothing, as in VTE;
     * xterm saves the icon name in its own entry (docs/decisions/0009). xterm's third parameter, an
     * index into the stack, is not supported: the sequence is ignored.
     */
    private fun saveOrRestoreTitle(params: Params) {
        if (params.size > 2 || (params[1] != 0 && params[1] != 2)) return
        if (params[0] == 22) {
            savedTitles.push(title)
        } else {
            savedTitles.pop()?.let { setTitle(it) }
        }
    }

    /**
     * The title in [data] from [start], without control and reordering characters, cut to
     * [MAX_TITLE_LENGTH] (docs/decisions/0063).
     */
    private fun cleanTitle(data: CharSequence, start: Int): String {
        val title = StringBuilder(minOf(data.length - start, MAX_TITLE_LENGTH))
        var i = start
        while (i < data.length) {
            val codePoint = Character.codePointAt(data, i)
            val length = Character.charCount(codePoint)
            if (!isRemovedFromTitle(codePoint)) {
                if (title.length + length > MAX_TITLE_LENGTH) break
                title.appendCodePoint(codePoint)
            }
            i += length
        }
        return title.toString()
    }

    /**
     * C0 and C1 controls, DEL, the line and paragraph separators, and the bidirectional formatting
     * characters.
     */
    private fun isRemovedFromTitle(codePoint: Int): Boolean =
        codePoint < 0x20 ||
            codePoint in 0x7F..0x9F ||
            codePoint == 0x061C ||
            codePoint == 0x200E ||
            codePoint == 0x200F ||
            codePoint in 0x2028..0x202E ||
            codePoint in 0x2066..0x2069

    private fun setTitle(newTitle: String) {
        title = newTitle
        listener.onEvent(TerminalEvent.TitleChanged(newTitle))
    }

    private fun reply(query: TerminalQuery) {
        listener.onEvent(TerminalEvent.ReplyRequested(query))
    }

    /**
     * XTWINOPS 14 and 16: the size of the text area or of a cell in pixels, which programs that
     * show images need. Not answered until the display gives the size of a cell, as colors are not
     * (docs/decisions/0038).
     */
    private fun reportPixelSize(cell: Boolean) {
        if (!graphics.isCellSizeKnown) return
        val width = graphics.cellWidth
        val height = graphics.cellHeight
        reply(
            if (cell) TerminalQuery.CellPixelSize(height, width)
            else TerminalQuery.TextAreaPixelSize(height * rows, width * columns)
        )
    }

    /**
     * DECSCUSR: 1 is a blinking block, then steady block, underline, and bar in pairs. 0 returns to
     * the app's default, as in VTE and Windows Terminal; xterm documents 0 as a blinking block
     * (docs/decisions/0009).
     */
    private fun setCursorStyle(ps: Int) {
        if (ps == 0) {
            cursorStyleFromHost = false
            modes =
                modes.copy(cursorShape = defaultCursorShape, cursorBlinking = defaultCursorBlinking)
            return
        }
        val shape =
            when (ps) {
                1,
                2 -> CursorShape.Block
                3,
                4 -> CursorShape.Underline
                5,
                6 -> CursorShape.Bar
                else -> return
            }
        cursorStyleFromHost = true
        modes = modes.copy(cursorShape = shape, cursorBlinking = ps % 2 == 1)
    }

    /**
     * Sets the app's default cursor style, which is shown unless the host set one. Returns whether
     * the displayed style changed.
     */
    fun setDefaultCursorStyle(shape: CursorShape, blinking: Boolean): Boolean {
        defaultCursorShape = shape
        defaultCursorBlinking = blinking
        if (cursorStyleFromHost || (modes.cursorShape == shape && modes.cursorBlinking == blinking))
            return false
        modes = modes.copy(cursorShape = shape, cursorBlinking = blinking)
        return true
    }

    /**
     * DECRQM. The modes that vtfx implements report their state. Modes that vtfx recognizes but
     * keeps off report reset, or permanently reset, as xterm does (docs/decisions/0009).
     */
    private fun reportMode(mode: Int, isDecPrivate: Boolean) {
        val state =
            if (isDecPrivate) {
                when (mode) {
                    1 -> modeState(modes.applicationCursorKeys)
                    66 -> modeState(modes.applicationKeypad)
                    5 -> modeState(modes.reverseVideo)
                    6 -> modeState(originMode)
                    7 -> modeState(autowrap)
                    9 -> modeState(modes.mouseTracking == MouseTracking.X10)
                    12 -> modeState(modes.cursorBlinking)
                    25 -> modeState(modes.cursorVisible)
                    47,
                    1047,
                    1049 -> modeState(isAlternateScreen)
                    1000 -> modeState(modes.mouseTracking == MouseTracking.Normal)
                    1002 -> modeState(modes.mouseTracking == MouseTracking.ButtonEvent)
                    1003 -> modeState(modes.mouseTracking == MouseTracking.AnyEvent)
                    1004 -> modeState(modes.focusEvents)
                    1005 -> modeState(modes.mouseEncoding == MouseEncoding.Utf8)
                    1006 -> modeState(modes.mouseEncoding == MouseEncoding.Sgr)
                    1015 -> modeState(modes.mouseEncoding == MouseEncoding.Urxvt)
                    2004 -> modeState(modes.bracketedPaste)
                    2026 -> modeState(modes.synchronizedOutput)
                    // DEC modes that vtfx recognizes but never sets (docs/decisions/0009): DECCOLM,
                    // DECSCLM, DECPFF, DECPEX, DECHEBM, DECNRCM, DECBKM, DECLRMM.
                    3,
                    4,
                    18,
                    19,
                    35,
                    42,
                    67,
                    69 -> ModeState.Reset
                    60 -> ModeState.PermanentlyReset // DECHCCM
                    else -> ModeState.NotRecognized
                }
            } else {
                when (mode) {
                    4 -> modeState(insertMode)
                    20 -> modeState(modes.newLine)
                    // ANSI modes that vtfx recognizes but never sets, with their state as in xterm.
                    1,
                    5,
                    7,
                    10,
                    11,
                    13,
                    14,
                    15,
                    16,
                    17,
                    18,
                    19 -> ModeState.PermanentlyReset
                    2 -> ModeState.Reset // KAM
                    12 -> ModeState.Reset // SRM
                    else -> ModeState.NotRecognized
                }
            }
        reply(TerminalQuery.ModeStatus(mode, isDecPrivate, state))
    }

    private fun modeState(set: Boolean): ModeState = if (set) ModeState.Set else ModeState.Reset

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= scrollTop) scrollTop else 0
        moveTo(cursorX, maxOf(cursorY - n, limit))
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY <= scrollBottom) scrollBottom else rows - 1
        moveTo(cursorX, minOf(cursorY + n, limit))
    }

    /** CUP: [row] and [column] are 1-based and relative to the top margin in origin mode. */
    private fun cursorPosition(row: Int, column: Int) {
        if (originMode) {
            moveTo(column - 1, (scrollTop + row - 1).coerceIn(scrollTop, scrollBottom))
        } else {
            moveTo(column - 1, row - 1)
        }
    }

    /** Moves the cursor, clamped to the screen, and cancels a pending wrap. */
    private fun moveTo(x: Int, y: Int) {
        val row = y.coerceIn(0, rows - 1)
        if (!isSameLogicalLine(cursorY, row)) lineChanges++
        cursorX = x.coerceIn(0, columns - 1)
        cursorY = row
        pendingWrap = false
    }

    /** Whether autowrap joined the rows from [a] to [b] into one line. */
    private fun isSameLogicalLine(a: Int, b: Int): Boolean {
        for (row in minOf(a, b) until maxOf(a, b)) {
            if (!screen[row].isWrapped) return false
        }
        return true
    }

    private fun eraseInDisplay(mode: Int) {
        val erased = style.erased()
        when (mode) {
            0 -> {
                screen[cursorY].erase(cursorX, columns, erased)
                for (row in cursorY + 1 until rows) screen[row].clear(erased)
            }
            1 -> {
                for (row in 0 until cursorY) screen[row].clear(erased)
                screen[cursorY].erase(0, cursorX + 1, erased)
            }
            2 -> {
                // Clearing the screen clears its images too, so that `clear` works, as the
                // protocol says (docs/decisions/0038).
                graphics.deleteVisible(layer, rows)
                screen.eraseAll(style)
            }
            3 -> scrollback.clear()
        }
    }

    private fun eraseInLine(mode: Int) {
        val erased = style.erased()
        val line = screen[cursorY]
        when (mode) {
            0 -> line.erase(cursorX, columns, erased)
            1 -> line.erase(0, cursorX + 1, erased)
            2 -> line.erase(0, columns, erased)
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        screen.scrollDown(cursorY, scrollBottom, n, style)
        moveTo(0, cursorY)
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        screen.scrollUp(cursorY, scrollBottom, n, style, saveToScrollback = false)
        moveTo(0, cursorY)
    }

    /** DECSTBM: [top] and [bottom] are 1-based. An invalid region is ignored. */
    private fun setScrollingRegion(top: Int, bottom: Int) {
        val clampedBottom = minOf(bottom, rows)
        if (top >= clampedBottom) return
        scrollTop = top - 1
        scrollBottom = clampedBottom - 1
        cursorPosition(1, 1)
    }

    private fun reportCursorPosition(isDecPrivate: Boolean) {
        val row = if (originMode) cursorY - scrollTop + 1 else cursorY + 1
        reply(TerminalQuery.CursorPosition(row, cursorX + 1, isDecPrivate))
    }

    private fun setAnsiMode(mode: Int, enabled: Boolean) {
        when (mode) {
            4 -> insertMode = enabled
            20 -> modes = modes.copy(newLine = enabled)
        }
    }

    private fun setDecMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> modes = modes.copy(applicationCursorKeys = enabled)
            5 -> modes = modes.copy(reverseVideo = enabled)
            6 -> {
                originMode = enabled
                cursorPosition(1, 1)
            }
            7 -> {
                autowrap = enabled
                if (!enabled) pendingWrap = false
            }
            9 -> setMouseTracking(MouseTracking.X10, enabled)
            12 -> {
                cursorStyleFromHost = true
                modes = modes.copy(cursorBlinking = enabled)
            }
            25 -> modes = modes.copy(cursorVisible = enabled)
            47 -> switchScreen(enabled, clearAlternate = false)
            66 -> modes = modes.copy(applicationKeypad = enabled)
            1000 -> setMouseTracking(MouseTracking.Normal, enabled)
            1002 -> setMouseTracking(MouseTracking.ButtonEvent, enabled)
            1003 -> setMouseTracking(MouseTracking.AnyEvent, enabled)
            1004 -> modes = modes.copy(focusEvents = enabled)
            1005 -> setMouseEncoding(MouseEncoding.Utf8, enabled)
            1006 -> setMouseEncoding(MouseEncoding.Sgr, enabled)
            1015 -> setMouseEncoding(MouseEncoding.Urxvt, enabled)
            1047 -> {
                if (!enabled && isAlternateScreen) alternateScreen.eraseAll(style)
                switchScreen(enabled, clearAlternate = false)
            }
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 ->
                if (enabled) {
                    saveCursor()
                    switchScreen(true, clearAlternate = true)
                } else {
                    switchScreen(false, clearAlternate = false)
                    restoreCursor()
                }
            2004 -> modes = modes.copy(bracketedPaste = enabled)
            2026 -> modes = modes.copy(synchronizedOutput = enabled)
        }
    }

    private fun setMouseTracking(tracking: MouseTracking, enabled: Boolean) {
        if (enabled) {
            modes = modes.copy(mouseTracking = tracking)
        } else if (modes.mouseTracking == tracking) {
            modes = modes.copy(mouseTracking = MouseTracking.None)
        }
    }

    private fun setMouseEncoding(encoding: MouseEncoding, enabled: Boolean) {
        if (enabled) {
            modes = modes.copy(mouseEncoding = encoding)
        } else if (modes.mouseEncoding == encoding) {
            modes = modes.copy(mouseEncoding = MouseEncoding.Default)
        }
    }

    private fun switchScreen(alternate: Boolean, clearAlternate: Boolean) {
        if (alternate == isAlternateScreen) return
        // The cursor is now on a line of the other screen.
        lineChanges++
        // The images of the alternate screen go when it is left (docs/decisions/0038).
        if (!alternate) alternateLayer.clear()
        screen = if (alternate) alternateScreen else mainScreen
        if (alternate && clearAlternate) alternateScreen.eraseAll(CellStyle.Default)
    }

    /**
     * Moves the cursor after an image placed at it, as kitty does: to the column after the image,
     * on its last row, wrapping to the next row at the right edge and scrolling at the bottom
     * margin (docs/decisions/0038).
     */
    private fun moveCursorAfterImage(columnsSpanned: Int, rowsSpanned: Int) {
        var x = cursorX + columnsSpanned
        var y = cursorY + rowsSpanned - 1
        if (x >= columns) {
            x = 0
            y++
        }
        if (cursorY in scrollTop..scrollBottom && y > scrollBottom) {
            screen.scrollUp(
                scrollTop,
                scrollBottom,
                y - scrollBottom,
                style,
                saveToScrollback = true,
            )
            y = scrollBottom
            lineChanges++
        }
        moveTo(x, y)
    }

    // --- Saving the cursor ---

    private data class SavedCursor(
        val x: Int = 0,
        val y: Int = 0,
        val rendition: Rendition = Rendition.Default,
        val pendingWrap: Boolean = false,
        val originMode: Boolean = false,
        val charsets: CharsetState = CharsetState(),
    )

    /** DECSC. Each screen has its own saved cursor, as in xterm. */
    private fun saveCursor() {
        val saved = SavedCursor(cursorX, cursorY, rendition, pendingWrap, originMode, charsets)
        if (isAlternateScreen) alternateSavedCursor = saved else mainSavedCursor = saved
    }

    /**
     * DECRC. Without a saved cursor, restores the defaults. In origin mode, the cursor is kept
     * inside the margins, which may have changed since it was saved, as xterm does.
     */
    private fun restoreCursor() {
        val saved = if (isAlternateScreen) alternateSavedCursor else mainSavedCursor
        moveTo(
            saved.x,
            if (saved.originMode) saved.y.coerceIn(scrollTop, scrollBottom) else saved.y,
        )
        rendition = saved.rendition
        originMode = saved.originMode
        charsets = saved.charsets
        // A pending wrap holds only at the last column, which a resize since DECSC may have moved.
        pendingWrap = saved.pendingWrap && autowrap && cursorX == columns - 1
    }

    // --- Strings ---

    override fun oscDispatch(data: CharSequence) {
        val separator = data.indexOf(';')
        if (separator < 0) return
        when (data.substring(0, separator)) {
            "0",
            "2" -> setTitle(cleanTitle(data, separator + 1))
            "4" -> paletteQuery(data.substring(separator + 1))
            "8" -> hyperlink(data.substring(separator + 1))
            "10" -> colorQuery(data.substring(separator + 1), ColorTarget.Foreground)
            "11" -> colorQuery(data.substring(separator + 1), ColorTarget.Background)
            "12" -> colorQuery(data.substring(separator + 1), ColorTarget.Cursor)
            "52" -> clipboard(data.substring(separator + 1))
        }
    }

    /** OSC 10, 11, 12 with "?" asks for a color. Setting colors is not supported. */
    private fun colorQuery(argument: String, target: ColorTarget) {
        if (argument == "?") reply(TerminalQuery.Color(target))
    }

    /** OSC 4 with "index;?" pairs asks for palette colors. Setting them is not supported. */
    private fun paletteQuery(arguments: String) {
        val parts = arguments.split(';')
        if (parts.size % 2 != 0) return
        for (i in parts.indices step 2) {
            val index = parts[i].toIntOrNull() ?: continue
            if (index in 0..255 && parts[i + 1] == "?")
                reply(TerminalQuery.Color(ColorTarget.Palette(index)))
        }
    }

    /** OSC 8: `params ; URI`. An empty URI ends the link. */
    private fun hyperlink(arguments: String) {
        val separator = arguments.indexOf(';')
        if (separator < 0) return
        val uri = arguments.substring(separator + 1)
        if (uri.isEmpty() || uri.length > MAX_URI_LENGTH) {
            activeLink = 0
            return
        }
        val id =
            arguments
                .substring(0, separator)
                .split(':')
                .firstOrNull { it.startsWith("id=") }
                ?.removePrefix("id=")
                ?.takeIf { it.isNotEmpty() }
        // The table keeps the id with the link, so it is bounded like the URI.
        if (id != null && id.length > MAX_LINK_ID_LENGTH) {
            activeLink = 0
            return
        }
        activeLink = hyperlinks.intern(Hyperlink(uri, id)) { linksInUse() }
    }

    private fun linksInUse(): Set<Int> {
        val used = HashSet<Int>()
        val collect = { line: Line ->
            if (line.hasLinks()) for (column in 0 until line.columns) used += line.linkAt(column)
        }
        mainScreen.forEachLine(collect)
        alternateScreen.forEachLine(collect)
        scrollback.forEachLink { used += it }
        return used
    }

    /** Returns the hyperlink with [id], or null. */
    fun hyperlink(id: Int): Hyperlink? = hyperlinks[id]

    /**
     * OSC 52: `Pc ; Pd`, where Pd is base64 text, or `?` to read. Malformed requests are ignored.
     */
    private fun clipboard(arguments: String) {
        val separator = arguments.indexOf(';')
        if (separator < 0) return
        val selection = arguments.substring(0, separator).ifEmpty { "s0" }
        if (selection.any { it !in TerminalEvent.CLIPBOARD_SELECTION_CHARACTERS }) return
        val data = arguments.substring(separator + 1)
        if (data == "?") {
            listener.onEvent(TerminalEvent.ClipboardReadRequested(selection))
            return
        }
        val text =
            try {
                String(Base64.getDecoder().decode(data), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // xterm clears the selection when the data is not base64. Ignoring is safer.
                return
            }
        listener.onEvent(TerminalEvent.ClipboardWriteRequested(selection, text))
    }

    override fun dcsDispatch(
        params: Params,
        prefix: Int,
        intermediates: Int,
        final: Int,
        data: CharSequence,
    ) {
        if (prefix == 0 && intermediates == '$'.code && final == 'q'.code)
            requestStatusString(data.toString())
    }

    /** APC: kitty graphics commands start with `G`; other APC strings are ignored. */
    override fun apcDispatch(data: CharSequence) {
        if (data.isNotEmpty() && data[0] == 'G') graphics.handle(data, graphicsScreen)
    }

    /** DECRQSS. The reply carries the setting's values from the state, never the request. */
    private fun requestStatusString(setting: String) {
        reply(
            when (setting) {
                "m" -> TerminalQuery.SgrStatus(style, rendition.underlineColor)
                "r" -> TerminalQuery.ScrollingRegionStatus(scrollTop + 1, scrollBottom + 1)
                " q" -> TerminalQuery.CursorStyleStatus(modes.cursorShape, modes.cursorBlinking)
                else -> TerminalQuery.UnsupportedStatus
            }
        )
    }

    // --- Resets and resizing ---

    /** DECSTR. */
    private fun softReset() {
        insertMode = false
        originMode = false
        autowrap = true
        rendition = Rendition.Default
        scrollTop = 0
        scrollBottom = rows - 1
        modes =
            modes.copy(
                cursorVisible = true,
                applicationCursorKeys = false,
                applicationKeypad = false,
            )
        mainSavedCursor = SavedCursor()
        alternateSavedCursor = SavedCursor()
        charsets = CharsetState()
        pendingWrap = false
    }

    /**
     * RIS. The scrollback, the window title, and the saved titles are kept, as in xterm. The images
     * on the screen go, as the protocol says, and those of the alternate screen.
     */
    fun reset() {
        // The line the cursor was on is gone, even if the cursor stays on its row.
        lineChanges++
        graphics.abort()
        graphics.deleteVisible(mainLayer, rows)
        alternateLayer.clear()
        screen = mainScreen
        mainScreen.eraseAll(CellStyle.Default)
        alternateScreen.eraseAll(CellStyle.Default)
        softReset()
        modes =
            TerminalModes(cursorShape = defaultCursorShape, cursorBlinking = defaultCursorBlinking)
        cursorStyleFromHost = false
        tabStops = defaultTabStops(columns)
        lastPrinted = -1
        activeLink = 0
        moveTo(0, 0)
    }

    /**
     * Changes the size. The main screen and the scrollback are reflowed to the new width, and when
     * the height changes, lines move between the screen and the scrollback so that the cursor stays
     * on the screen (docs/decisions/0013). The alternate screen is cut or padded: the app on it
     * redraws after the host learns the new size.
     */
    fun resize(newColumns: Int, newRows: Int) {
        val mainCursor = if (isAlternateScreen) mainSavedCursor else null
        val cursorColumn = mainCursor?.x ?: cursorX
        val cursorRow = mainCursor?.y ?: cursorY
        val cursorPendingWrap = mainCursor?.pendingWrap ?: pendingWrap
        val allRows = scrollback.toList() + mainScreen.toList()
        val result =
            Reflow.reflow(
                allRows,
                scrollback.size + cursorRow,
                cursorColumn,
                cursorPendingWrap,
                newColumns,
            )
        // The screen shows the last rows, ending at the content or the cursor; the rest is the
        // scrollback.
        val screenStart = maxOf(0, result.rows.size - newRows)
        val screenRows = result.rows.subList(screenStart, result.rows.size).toMutableList()
        while (screenRows.size < newRows) screenRows += Line(newColumns)
        scrollback.replaceAll(result.rows.subList(0, screenStart))
        mainScreen.replaceLines(screenRows, newColumns)
        val newCursorRow = (result.cursorRow - screenStart).coerceIn(0, newRows - 1)
        val newCursorColumn = result.cursorColumn.coerceIn(0, newColumns - 1)
        if (mainCursor != null) {
            mainSavedCursor =
                mainCursor.copy(
                    x = newCursorColumn,
                    y = newCursorRow,
                    pendingWrap = result.pendingWrap,
                )
            // The cursor on the alternate screen, which is not reflowed, is clamped. Unlike moveTo,
            // this does not count as going to another line.
            cursorX = cursorX.coerceIn(0, newColumns - 1)
            cursorY = cursorY.coerceIn(0, newRows - 1)
            pendingWrap = false
        } else {
            cursorX = newCursorColumn
            cursorY = newCursorRow
            pendingWrap = result.pendingWrap
        }
        alternateScreen.resize(newColumns, newRows)
        val oldColumns = columns
        columns = newColumns
        rows = newRows
        tabStops =
            tabStops.copyOf(newColumns).also { stops ->
                for (x in oldColumns until newColumns) stops[x] = x % TAB_WIDTH == 0
            }
        scrollTop = 0
        scrollBottom = newRows - 1
    }

    companion object {
        private const val BEL = 0x07
        private const val BS = 0x08
        private const val HT = 0x09
        private const val LF = 0x0A
        private const val VT = 0x0B
        private const val FF = 0x0C
        private const val CR = 0x0D
        private const val SO = 0x0E
        private const val SI = 0x0F
        private const val TAB_WIDTH = 8

        /** The longest OSC 8 URI kept, as the OSC 8 specification suggests for terminals. */
        const val MAX_URI_LENGTH = 2083

        /**
         * The longest OSC 8 id kept, in UTF-16 units, as VTE limits it (docs/decisions/0002). A
         * link with a longer id is not made.
         */
        const val MAX_LINK_ID_LENGTH = 250

        /** The number of distinct hyperlinks kept at a time (docs/decisions/0002). */
        const val MAX_HYPERLINKS = 4096

        /**
         * The number of window titles that XTWINOPS 22 saves, as in xterm (docs/decisions/0002).
         */
        const val MAX_SAVED_TITLES = 10

        /** The length of the window title, in UTF-16 units (docs/decisions/0063). */
        const val MAX_TITLE_LENGTH = 1024

        private fun defaultTabStops(columns: Int) =
            BooleanArray(columns) { it % TAB_WIDTH == 0 && it > 0 }
    }
}
