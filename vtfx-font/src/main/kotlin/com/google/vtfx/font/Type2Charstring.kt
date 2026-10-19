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

package com.google.vtfx.font

import kotlin.math.abs

/** Receives the path that a charstring draws, in absolute coordinates. */
internal interface CubicPen {
    /** Starts a contour, ending the one before. */
    fun moveTo(x: Double, y: Double)

    fun lineTo(x: Double, y: Double)

    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double)

    /** Ends the last contour; the glyph is complete. */
    fun endGlyph()
}

/**
 * Runs the Type 2 charstring of one glyph (Adobe Technical Note #5177), drawing it into [pen].
 *
 * The advance width that a charstring may give is ignored, since advances come from `hmtx`; hints
 * are counted, so that the masks of `hintmask` can be skipped, and otherwise ignored. What real CFF
 * fonts no longer use is rejected as unsupported: the arithmetic and storage operators, which the
 * specification deprecates, and `endchar` drawing an accented character (seac).
 *
 * The work is bounded: at most [MAX_OPERATORS] operators run, which bounds the time that
 * subroutines calling each other can take, and subroutines nest at most [MAX_SUBR_DEPTH] deep.
 */
internal class Type2Charstring(
    private val globalSubrs: CffIndex,
    private val localSubrs: CffIndex?,
    private val pen: CubicPen,
) {
    private val stack = DoubleArray(MAX_STACK)
    private var sp = 0
    private var stems = 0
    private var widthSeen = false
    private var operators = 0
    private var started = false
    private var x = 0.0
    private var y = 0.0

    /** Runs [charString], which must end with `endchar`. Call once. */
    fun run(charString: Reader) {
        if (!execute(charString, depth = 0)) malformed("charstring without endchar")
    }

    /**
     * Runs [code] at [depth] subroutine calls, returning whether `endchar` ended it rather than
     * `return` or the end of the data (an implicit `return`, as in CFF2).
     */
    private fun execute(code: Reader, depth: Int): Boolean {
        var at = 0
        while (at < code.length) {
            val b0 = code.u8(at++)
            if (b0 >= 32 || b0 == SHORT_INT) {
                if (sp == MAX_STACK) malformed("more than $MAX_STACK charstring arguments")
                stack[sp++] =
                    when (b0) {
                        in 32..246 -> (b0 - 139).toDouble()
                        in 247..250 -> ((b0 - 247) * 256 + code.u8(at++) + 108).toDouble()
                        in 251..254 -> (-(b0 - 251) * 256 - code.u8(at++) - 108).toDouble()
                        SHORT_INT -> code.s16(at).toDouble().also { at += 2 }
                        else -> (code.s32(at) / 65536.0).also { at += 4 }
                    }
                continue
            }
            if (++operators > MAX_OPERATORS)
                tooComplex("charstring of more than $MAX_OPERATORS operators")
            when (b0) {
                HSTEM,
                VSTEM,
                HSTEMHM,
                VSTEMHM -> {
                    val first = firstArgument(hasWidth = sp % 2 == 1)
                    if (sp == first) malformed("stem hint without stems")
                    addStems(first)
                }
                HINTMASK,
                CNTRMASK -> {
                    // Arguments before the first mask are vertical stems without their operator.
                    addStems(firstArgument(hasWidth = sp % 2 == 1))
                    at += (stems + 7) / 8
                    if (at > code.length) malformed("hint mask beyond the charstring")
                }
                RMOVETO -> {
                    val first = firstArgument(hasWidth = sp == 3)
                    requireArguments(first, 2)
                    moveTo(x + stack[first], y + stack[first + 1])
                }
                HMOVETO -> {
                    val first = firstArgument(hasWidth = sp == 2)
                    requireArguments(first, 1)
                    moveTo(x + stack[first], y)
                }
                VMOVETO -> {
                    val first = firstArgument(hasWidth = sp == 2)
                    requireArguments(first, 1)
                    moveTo(x, y + stack[first])
                }
                RLINETO -> {
                    requirePath(sp > 0 && sp % 2 == 0)
                    for (i in 0 until sp step 2) lineTo(x + stack[i], y + stack[i + 1])
                }
                HLINETO -> alternatingLines(horizontal = true)
                VLINETO -> alternatingLines(horizontal = false)
                RRCURVETO -> {
                    requirePath(sp > 0 && sp % 6 == 0)
                    for (i in 0 until sp step 6) relativeCurve(i)
                }
                HHCURVETO -> {
                    requirePath(sp >= 4 && sp % 4 <= 1)
                    var i = sp % 4
                    var dy1 = if (i == 1) stack[0] else 0.0
                    while (i < sp) {
                        curve(stack[i], dy1, stack[i + 1], stack[i + 2], stack[i + 3], 0.0)
                        dy1 = 0.0
                        i += 4
                    }
                }
                VVCURVETO -> {
                    requirePath(sp >= 4 && sp % 4 <= 1)
                    var i = sp % 4
                    var dx1 = if (i == 1) stack[0] else 0.0
                    while (i < sp) {
                        curve(dx1, stack[i], stack[i + 1], stack[i + 2], 0.0, stack[i + 3])
                        dx1 = 0.0
                        i += 4
                    }
                }
                HVCURVETO -> alternatingCurves(horizontal = true)
                VHCURVETO -> alternatingCurves(horizontal = false)
                RCURVELINE -> {
                    requirePath(sp >= 8 && (sp - 2) % 6 == 0)
                    for (i in 0 until sp - 2 step 6) relativeCurve(i)
                    lineTo(x + stack[sp - 2], y + stack[sp - 1])
                }
                RLINECURVE -> {
                    requirePath(sp >= 8 && (sp - 6) % 2 == 0)
                    for (i in 0 until sp - 6 step 2) lineTo(x + stack[i], y + stack[i + 1])
                    relativeCurve(sp - 6)
                }
                CALLSUBR -> {
                    val subrs = localSubrs ?: malformed("callsubr without local subroutines")
                    if (call(subrs, depth)) return true
                    continue
                }
                CALLGSUBR -> {
                    if (call(globalSubrs, depth)) return true
                    continue
                }
                RETURN -> {
                    if (depth == 0) malformed("return outside a subroutine")
                    return false
                }
                ENDCHAR -> {
                    val first = firstArgument(hasWidth = sp == 1 || sp == 5)
                    if (sp - first == 4) unsupported("accented character (seac)")
                    if (sp != first) malformed("endchar with ${sp - first} arguments")
                    pen.endGlyph()
                    return true
                }
                ESCAPE -> escaped(code.u8(at++))
                else -> malformed("charstring operator $b0")
            }
            sp = 0
        }
        return false
    }

    private fun escaped(operator: Int) {
        when (operator) {
            DOTSECTION -> Unit
            FLEX -> {
                requirePath(sp == 13)
                relativeCurve(0)
                relativeCurve(6)
            }
            HFLEX -> {
                requirePath(sp == 7)
                val startY = y
                curve(stack[0], 0.0, stack[1], stack[2], stack[3], 0.0)
                curve(stack[4], 0.0, stack[5], startY - y, stack[6], 0.0)
            }
            HFLEX1 -> {
                requirePath(sp == 9)
                val startY = y
                curve(stack[0], stack[1], stack[2], stack[3], stack[4], 0.0)
                curve(stack[5], 0.0, stack[6], stack[7], stack[8], startY - y - stack[7])
            }
            FLEX1 -> {
                requirePath(sp == 11)
                var dx = 0.0
                var dy = 0.0
                for (i in 0 until 10 step 2) {
                    dx += stack[i]
                    dy += stack[i + 1]
                }
                val startX = x
                val startY = y
                relativeCurve(0)
                val x1 = x + stack[6]
                val y1 = y + stack[7]
                val x2 = x1 + stack[8]
                val y2 = y1 + stack[9]
                // The last point is on the line of the start, horizontal or vertical, whichever is
                // farther from it.
                if (abs(dx) > abs(dy)) {
                    curveTo(x1, y1, x2, y2, x2 + stack[10], startY)
                } else {
                    curveTo(x1, y1, x2, y2, startX, y2 + stack[10])
                }
            }
            in DEPRECATED_OPERATORS -> unsupported("charstring arithmetic operator 12 $operator")
            else -> malformed("charstring operator 12 $operator")
        }
    }

    /** Runs the subroutine whose biased index is on top of the stack. */
    private fun call(subrs: CffIndex, depth: Int): Boolean {
        if (sp == 0) malformed("subroutine call without an index")
        val value = stack[--sp]
        if (value != Math.floor(value) || abs(value) > MAX_SUBRS) {
            malformed("subroutine index $value")
        }
        val bias =
            when {
                subrs.count < 1240 -> 107
                subrs.count < 33900 -> 1131
                else -> 32768
            }
        val index = value.toInt() + bias
        if (index !in 0 until subrs.count) malformed("subroutine $index of ${subrs.count}")
        if (depth == MAX_SUBR_DEPTH) tooComplex("subroutines nested deeper than $MAX_SUBR_DEPTH")
        return execute(subrs.item(index), depth + 1)
    }

    /**
     * The index of the first argument of a stack-clearing operator: 1 if this is the first such
     * operator of the glyph and [hasWidth] says that it has the width as an extra argument.
     */
    private fun firstArgument(hasWidth: Boolean): Int {
        if (widthSeen) return 0
        widthSeen = true
        return if (hasWidth) 1 else 0
    }

    private fun addStems(first: Int) {
        val count = sp - first
        if (count % 2 != 0) malformed("stem hint of an odd number of arguments")
        stems += count / 2
    }

    private fun requireArguments(first: Int, count: Int) {
        if (sp - first != count) malformed("moveto with ${sp - first} arguments")
    }

    private fun requirePath(argumentsValid: Boolean) {
        if (!started) malformed("path before moveto")
        if (!argumentsValid) malformed("path operator with $sp arguments")
    }

    private fun alternatingLines(horizontal: Boolean) {
        requirePath(sp > 0)
        var alongX = horizontal
        for (i in 0 until sp) {
            if (alongX) lineTo(x + stack[i], y) else lineTo(x, y + stack[i])
            alongX = !alongX
        }
    }

    /** `hvcurveto` and `vhcurveto`: curves whose ends alternate between horizontal and vertical. */
    private fun alternatingCurves(horizontal: Boolean) {
        requirePath(sp >= 4 && sp % 4 <= 1)
        var startsAlongX = horizontal
        var i = 0
        while (i + 4 <= sp) {
            // The last curve may have a fifth argument, for the other coordinate of its end.
            val last = if (sp - i == 5) stack[i + 4] else 0.0
            if (startsAlongX) {
                curve(stack[i], 0.0, stack[i + 1], stack[i + 2], last, stack[i + 3])
            } else {
                curve(0.0, stack[i], stack[i + 1], stack[i + 2], stack[i + 3], last)
            }
            startsAlongX = !startsAlongX
            i += 4
        }
    }

    /** A curve of the six relative coordinates on the stack from [i]. */
    private fun relativeCurve(i: Int) {
        curve(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5])
    }

    /** A curve whose points are each relative to the one before. */
    private fun curve(
        dx1: Double,
        dy1: Double,
        dx2: Double,
        dy2: Double,
        dx3: Double,
        dy3: Double,
    ) {
        val x1 = x + dx1
        val y1 = y + dy1
        val x2 = x1 + dx2
        val y2 = y1 + dy2
        curveTo(x1, y1, x2, y2, x2 + dx3, y2 + dy3)
    }

    private fun moveTo(x: Double, y: Double) {
        this.x = x
        this.y = y
        started = true
        pen.moveTo(x, y)
    }

    private fun lineTo(x: Double, y: Double) {
        this.x = x
        this.y = y
        pen.lineTo(x, y)
    }

    private fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        this.x = x
        this.y = y
        pen.curveTo(x1, y1, x2, y2, x, y)
    }

    companion object {
        /** The most operators that one glyph runs, counting those of its subroutines. */
        const val MAX_OPERATORS = 65536

        /** The deepest nesting of subroutines, as the specification limits it (appendix B). */
        const val MAX_SUBR_DEPTH = 10

        /** The most arguments on the stack, as the specification limits it (appendix B). */
        private const val MAX_STACK = 48

        /** More subroutines than an INDEX can hold, so a bound on a meaningful index. */
        private const val MAX_SUBRS = 65536.0

        private const val HSTEM = 1
        private const val VSTEM = 3
        private const val VMOVETO = 4
        private const val RLINETO = 5
        private const val HLINETO = 6
        private const val VLINETO = 7
        private const val RRCURVETO = 8
        private const val CALLSUBR = 10
        private const val RETURN = 11
        private const val ESCAPE = 12
        private const val ENDCHAR = 14
        private const val HSTEMHM = 18
        private const val HINTMASK = 19
        private const val CNTRMASK = 20
        private const val RMOVETO = 21
        private const val HMOVETO = 22
        private const val VSTEMHM = 23
        private const val RCURVELINE = 24
        private const val RLINECURVE = 25
        private const val VVCURVETO = 26
        private const val HHCURVETO = 27
        private const val SHORT_INT = 28
        private const val CALLGSUBR = 29
        private const val VHCURVETO = 30
        private const val HVCURVETO = 31

        private const val DOTSECTION = 0
        private const val HFLEX = 34
        private const val FLEX = 35
        private const val HFLEX1 = 36
        private const val FLEX1 = 37

        /**
         * `and`, `or`, `not`, `abs`, `add`, `sub`, `div`, `neg`, `eq`, `drop`, `put`, `get`,
         * `ifelse`, `random`, `mul`, `sqrt`, `dup`, `exch`, `index`, and `roll`.
         */
        private val DEPRECATED_OPERATORS =
            setOf(3, 4, 5, 9, 10, 11, 12, 14, 15, 18, 20, 21, 22, 23, 24, 26, 27, 28, 29, 30)
    }
}
