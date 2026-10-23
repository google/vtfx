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

import com.google.vtfx.font.gate.FontLimits
import com.google.vtfx.font.gate.InvalidFontException
import com.google.vtfx.font.gate.InvalidFontException.Kind
import kotlin.math.hypot
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SanitizedCffFontTest {
    private val limits = FontLimits()

    private fun sanitize(font: TestCffFont, limits: FontLimits = this.limits) =
        FontFile.sanitize(font.build(), 0, limits)

    private fun contents(font: TestCffFont) = SanitizedContents(sanitize(font))

    private fun failure(font: TestCffFont, limits: FontLimits = this.limits): Kind =
        assertFailsWith<InvalidFontException> { sanitize(font, limits) }.kind

    private fun failure(input: ByteArray): Kind =
        assertFailsWith<InvalidFontException> { FontFile.sanitize(input, 0, limits) }.kind

    /** A font of one glyph besides glyph 0, drawn by [charString]. */
    private fun glyph(charString: String) = TestCffFont(glyphs = listOf("endchar", charString))

    /** The contours of glyphs 1 and 2 of a font drawn by [first] and [second]. */
    private fun bothContours(first: String, second: String): Pair<List<List<P>>, List<List<P>>> {
        val contents = contents(TestCffFont(glyphs = listOf("endchar", first, second)))
        return contents.contours(1) to contents.contours(2)
    }

    /** A CCW square of 300 units at (100, 0), as CFF draws an outer contour. */
    private val square = "100 0 rmoveto 300 0 rlineto 0 300 rlineto -300 0 rlineto"

    /** The square, sides after the first, as a subroutine. */
    private val squareSides = "300 0 rlineto 0 300 rlineto -300 0 rlineto"

    @Test
    fun linesAreKeptExactlyAndReversed() {
        val font = glyph("$square endchar")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun closingLineToTheStartIsDropped() {
        val font = glyph("$square 0 -300 rlineto endchar")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun eachMovetoStartsAContour() {
        val font =
            glyph("$square 500 -300 rmoveto 100 0 rlineto 0 100 rlineto -100 0 rlineto endchar")

        assertEquals(listOf(square(100, 0, 300), square(600, 0, 100)), contents(font).contours(1))
    }

    @Test
    fun movetoWithoutLinesDrawsNothing() {
        val font = glyph("100 0 rmoveto 50 50 rmoveto endchar")

        assertEquals(emptyList(), contents(font).contours(1))
    }

    @Test
    fun glyphOfOnlyEndcharIsEmpty() {
        val font = glyph("endchar")

        assertEquals(emptyList(), contents(font).contours(1))
    }

    @Test
    fun cubicCurveBecomesOffCurvePointsBetweenItsEnds() {
        val font = glyph("100 100 rmoveto 276 0 224 224 0 276 rrcurveto -500 0 rlineto endchar")

        val contour = contents(font).contours(1).single()

        assertEquals(listOf(P(100, 100), P(100, 600), P(600, 600)), contour.take(3))
        assertTrue(contour.size > 3 && contour.drop(3).none { it.on })
    }

    @Test
    fun cubicCurveIsApproximatedWithinTheTolerance() {
        val font = glyph("100 100 rmoveto 276 0 224 224 0 276 rrcurveto -500 0 rlineto endchar")

        val contour = contents(font).contours(1).single()

        // The curve, reversed, from (600, 600) back to the start. The tolerance is 1 unit for 1000
        // units per em, and rounding to integers adds up to 1 unit more.
        val spline = listOf(contour[2]) + contour.drop(3) + contour[0]
        val cubic = cubicPoints(D(600, 600), D(600, 324), D(376, 100), D(100, 100))
        val quadratic = splinePoints(spline)
        assertTrue(maxDistance(cubic, quadratic) <= 2.0, "cubic to spline")
        assertTrue(maxDistance(quadratic, cubic) <= 2.0, "spline to cubic")
    }

    @Test
    fun straightCurveIsApproximated() {
        val font = glyph("100 0 rmoveto 100 0 100 0 100 0 rrcurveto 0 300 rlineto endchar")

        val contour = contents(font).contours(1).single()

        assertTrue(contour.all { it.y == 0 || it == P(400, 300) })
    }

    @Test
    fun hlinetoAndVlinetoAlternateTheirDirections() {
        val font =
            glyph(
                "100 0 rmoveto 300 300 -300 hlineto 400 -300 rmoveto 300 -300 -300 vlineto endchar"
            )

        assertEquals(
            listOf(square(100, 0, 300), listOf(P(500, 0), P(200, 0), P(200, 300), P(500, 300))),
            contents(font).contours(1),
        )
    }

    @Test
    fun hmovetoAndVmovetoMoveAlongOneAxis() {
        val (moved, relative) =
            bothContours(
                "100 hmoveto $squareSides 50 vmoveto $squareSides endchar",
                "100 0 rmoveto $squareSides 0 50 rmoveto $squareSides endchar",
            )

        assertEquals(relative, moved)
    }

    @Test
    fun hhcurvetoDrawsCurvesThatStartAndEndHorizontally() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 20 100 50 30 100 60 40 -10 50 hhcurveto 0 200 rlineto endchar",
                "0 0 rmoveto 100 20 50 30 100 0 60 0 40 -10 50 0 rrcurveto 0 200 rlineto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun vvcurvetoDrawsCurvesThatStartAndEndVertically() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 10 100 50 30 100 60 40 -10 50 vvcurveto -200 0 rlineto endchar",
                "0 0 rmoveto 10 100 50 30 0 100 0 60 40 -10 0 50 rrcurveto -200 0 rlineto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun hvcurvetoAlternatesStartingHorizontally() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 100 50 30 100 60 40 -10 50 25 hvcurveto 0 200 rlineto endchar",
                "0 0 rmoveto 100 0 50 30 0 100 0 60 40 -10 50 25 rrcurveto 0 200 rlineto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun vhcurvetoAlternatesStartingVertically() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 100 50 30 100 60 40 -10 50 25 vhcurveto -200 0 rlineto endchar",
                "0 0 rmoveto 0 100 50 30 100 0 60 0 40 -10 25 50 rrcurveto -200 0 rlineto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun rcurvelineDrawsCurvesThenALine() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 100 0 50 30 0 100 -200 0 rcurveline endchar",
                "0 0 rmoveto 100 0 50 30 0 100 rrcurveto -200 0 rlineto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun rlinecurveDrawsLinesThenACurve() {
        val (curves, relative) =
            bothContours(
                "0 0 rmoveto 100 0 0 100 -50 0 -30 20 -20 -20 rlinecurve endchar",
                "0 0 rmoveto 100 0 0 100 rlineto -50 0 -30 20 -20 -20 rrcurveto endchar",
            )

        assertEquals(relative, curves)
    }

    @Test
    fun flexDrawsTwoCurves() {
        val (flex, relative) =
            bothContours(
                "0 0 rmoveto 100 50 100 0 100 -50 100 -50 100 0 100 50 50 flex 0 300 rlineto endchar",
                "0 0 rmoveto 100 50 100 0 100 -50 100 -50 100 0 100 50 rrcurveto 0 300 rlineto endchar",
            )

        assertEquals(relative, flex)
    }

    @Test
    fun hflexDrawsTwoCurvesBackToTheStartingHeight() {
        val (flex, relative) =
            bothContours(
                "0 0 rmoveto 100 100 50 100 100 100 100 hflex 0 300 rlineto endchar",
                "0 0 rmoveto 100 0 100 50 100 0 100 0 100 -50 100 0 rrcurveto 0 300 rlineto endchar",
            )

        assertEquals(relative, flex)
    }

    @Test
    fun hflex1DrawsTwoCurvesBackToTheStartingHeight() {
        val (flex, relative) =
            bothContours(
                "0 0 rmoveto 100 20 100 30 100 100 100 -30 100 hflex1 0 300 rlineto endchar",
                "0 0 rmoveto 100 20 100 30 100 0 100 0 100 -30 100 -20 rrcurveto 0 300 rlineto endchar",
            )

        assertEquals(relative, flex)
    }

    @Test
    fun flex1EndsAtTheStartingHeightWhenMostlyHorizontal() {
        val (flex, relative) =
            bothContours(
                "0 0 rmoveto 100 20 100 30 100 0 100 0 100 -30 100 flex1 0 300 rlineto endchar",
                "0 0 rmoveto 100 20 100 30 100 0 100 0 100 -30 100 -20 rrcurveto 0 300 rlineto endchar",
            )

        assertEquals(relative, flex)
    }

    @Test
    fun flex1EndsAtTheStartingXWhenMostlyVertical() {
        val (flex, relative) =
            bothContours(
                "0 0 rmoveto 20 100 30 100 0 100 0 100 -30 100 100 flex1 -300 0 rlineto endchar",
                "0 0 rmoveto 20 100 30 100 0 100 0 100 -30 100 -20 100 rrcurveto -300 0 rlineto endchar",
            )

        assertEquals(relative, flex)
    }

    @Test
    fun numbersOfEveryEncodingAreRead() {
        // 99.5 and 300.5 in 16.16 fixed point, 1131 and -1131 in two bytes, 1200 in three.
        val font =
            glyph("99.5 -1131 rmoveto 300.5 0 rlineto 0 1200 rlineto -1131 0 rlineto endchar")

        assertEquals(
            listOf(listOf(P(100, -1131), P(-731, 69), P(400, 69), P(400, -1131))),
            contents(font).contours(1),
        )
    }

    @Test
    fun widthBeforeTheFirstMovetoIsIgnored() {
        val font = glyph("500 $square endchar")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun widthBeforeTheFirstHintIsIgnored() {
        val font = glyph("500 0 10 hstem $square endchar")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun widthOfAnEmptyGlyphIsIgnored() {
        val font = glyph("500 endchar")

        assertEquals(emptyList(), contents(font).contours(1))
    }

    @Test
    fun hintMasksAreSkippedCountingImplicitVerticalStems() {
        // Three horizontal stems, two vertical ones, and four implicit vertical stems before the
        // first mask: nine stems, so each mask takes two bytes. Mask bytes read as operators
        // would fail: 0x80 as a number before rmoveto, 0x15 as rmoveto without arguments.
        val font =
            glyph(
                "0 10 20 10 40 10 hstemhm 0 10 20 10 vstemhm 40 10 60 10 80 10 100 10 " +
                    "hintmask #FF #80 100 0 rmoveto 300 0 rlineto cntrmask #15 #15 " +
                    "0 300 rlineto -300 0 rlineto endchar"
            )

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun localSubroutineIsCalledWithBias107() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr endchar"))
        font.localSubrs = listOf("$squareSides return")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun globalSubroutineIsCalledWithBias107() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callgsubr endchar"))
        font.globalSubrs = listOf("$squareSides return")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun subroutinesOf1240OrMoreHaveBias1131() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -1131 callsubr endchar"))
        font.localSubrs = listOf("$squareSides return") + List(1239) { "return" }

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun subroutinesOf33900OrMoreHaveBias32768() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -32768 callgsubr endchar"))
        font.globalSubrs = listOf("$squareSides return") + List(33899) { "return" }

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun subroutineWithoutReturnReturnsAtItsEnd() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr endchar"))
        font.localSubrs = listOf(squareSides)

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun endcharInASubroutineEndsTheGlyph() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr"))
        font.localSubrs = listOf("$squareSides endchar")

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun subroutinesNestedTenDeepAreRead() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr endchar"))
        font.localSubrs = List(9) { "${it + 1 - 107} callsubr return" } + "$squareSides return"

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun cidKeyedFontCallsTheLocalSubroutinesOfEachGlyphsFontDict() {
        val call = "100 0 rmoveto -107 callsubr endchar"
        val font = TestCffFont(glyphs = listOf("endchar", call, call))
        font.cid =
            TestCid(
                fontDictSubrs =
                    listOf(
                        listOf("$squareSides return"),
                        listOf("100 0 rlineto 0 100 rlineto -100 0 rlineto return"),
                    ),
                fdSelectRanges = listOf(0 to 0, 2 to 1),
            )
        val contents = contents(font)

        assertEquals(
            listOf(listOf(square(100, 0, 300)), listOf(square(100, 0, 100))),
            listOf(contents.contours(1), contents.contours(2)),
        )
    }

    @Test
    fun cidKeyedFontReadsFdSelectFormat0() {
        val call = "100 0 rmoveto -107 callsubr endchar"
        val font = TestCffFont(glyphs = listOf("endchar", call, call))
        font.cid =
            TestCid(
                fontDictSubrs =
                    listOf(
                        listOf("$squareSides return"),
                        listOf("100 0 rlineto 0 100 rlineto -100 0 rlineto return"),
                    ),
                fdSelectRanges = listOf(0 to 1, 2 to 0),
                fdSelectFormat = 0,
            )
        val contents = contents(font)

        assertEquals(
            listOf(listOf(square(100, 0, 100)), listOf(square(100, 0, 300))),
            listOf(contents.contours(1), contents.contours(2)),
        )
    }

    @Test
    fun fontMatrixOfTheEmIsAccepted() {
        val scale = (1.0 / 2048).toString()
        val font =
            TestCffFont(unitsPerEm = 2048, fontMatrix = listOf(scale, "0", "0", scale, "0", "0"))

        assertEquals(listOf(square(100, 0, 300)), contents(font).contours(1))
    }

    @Test
    fun indexOffsetsOfEverySizeAreRead() {
        val contours = (1..3).map { contents(TestCffFont(offSize = it)).contours(1) }

        assertEquals(List(3) { listOf(square(100, 0, 300)) }, contours)
    }

    @Test
    fun closingPointDoesNotCountTowardsThePointLimit() {
        val font = glyph("$square 0 -300 rlineto endchar")

        val contours = SanitizedContents(sanitize(font, FontLimits(maxGlyphPoints = 4))).contours(1)

        assertEquals(listOf(square(100, 0, 300)), contours)
    }

    @Test
    fun glyphWithMorePointsThanTheLimitIsTooComplex() {
        val font = glyph("$square endchar")

        assertEquals(Kind.TooComplex, failure(font, FontLimits(maxGlyphPoints = 3)))
    }

    @Test
    fun pointLimitCountsThePointsOfEachGlyphAlone() {
        val font = TestCffFont(glyphs = listOf("endchar", "$square endchar", "$square endchar"))

        val contents = SanitizedContents(sanitize(font, FontLimits(maxGlyphPoints = 4)))

        assertEquals(
            listOf(listOf(square(100, 0, 300)), listOf(square(100, 0, 300))),
            listOf(contents.contours(1), contents.contours(2)),
        )
    }

    @Test
    fun sanitizingASanitizedCffFontGivesTheSameBytes() {
        val first =
            sanitize(glyph("100 100 rmoveto 276 0 224 224 0 276 rrcurveto -500 0 rlineto endchar"))

        val second = FontFile.sanitize(first.toByteArray(), 0, limits)

        assertContentEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun accentedCharacterOfEndcharIsUnsupported() {
        assertEquals(Kind.Unsupported, failure(glyph("0 0 65 66 endchar")))
    }

    @Test
    fun arithmeticOperatorIsUnsupported() {
        assertEquals(Kind.Unsupported, failure(glyph("1 2 add $square endchar")))
    }

    @Test
    fun moreThan48ArgumentsAreMalformed() {
        val font = glyph("100 0 rmoveto ${"1 ".repeat(49)} rlineto endchar")

        assertEquals(Kind.Malformed, failure(font))
    }

    @Test
    fun subroutinesNestedDeeperThanTenAreTooComplex() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr endchar"))
        font.localSubrs = List(10) { "${it + 1 - 107} callsubr return" } + "$squareSides return"

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun recursiveSubroutineIsTooComplex() {
        val font = TestCffFont(glyphs = listOf("endchar", "100 0 rmoveto -107 callsubr endchar"))
        font.localSubrs = listOf("-107 callsubr")

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun subroutinesRunningTooManyOperatorsAreTooComplex() {
        // Each of nine levels calls the next four times: 4^9 calls, within the depth limit.
        val font = TestCffFont(glyphs = listOf("endchar", "-107 callsubr endchar"))
        font.localSubrs = List(9) { "${"${it + 1 - 107} callsubr ".repeat(4)} return" } + "return"

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun subroutinesThatAllGlyphsCallAreLimitedForTheWholeFace() {
        // Each of seven levels calls the next four times: each glyph runs 65536 operators and
        // operands, of which 43691 operators, within the limit of one glyph; 2048 glyphs run 2^27.
        val glyphs = List(2100) { if (it == 0) "endchar" else "-107 callgsubr endchar" }
        val font = TestCffFont(glyphs = glyphs)
        font.globalSubrs = List(7) { "${"${it + 1 - 107} callgsubr ".repeat(4)} return" } + "return"

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun pointsOfAllGlyphsAreLimitedForTheWholeFace() {
        // The subroutine draws 24 lines to (0, 0), and each glyph calls it 682 times: 16368
        // points, within the limit of one glyph, and about 35,000 operators and operands, so that
        // 2100 glyphs run fewer than the 2^27 of a face but have more than 2^25 points.
        val glyph = "0 0 rmoveto" + " -107 callgsubr".repeat(682) + " endchar"
        val font = TestCffFont(glyphs = List(2100) { if (it == 0) "endchar" else glyph })
        font.globalSubrs = listOf("0 0 ".repeat(24) + "rlineto return")

        assertEquals(Kind.TooComplex, failure(font))
    }

    @Test
    fun pathBeforeMovetoIsMalformed() {
        assertEquals(Kind.Malformed, failure(glyph("300 0 rlineto endchar")))
    }

    @Test
    fun charstringWithoutEndcharIsMalformed() {
        assertEquals(Kind.Malformed, failure(glyph(square)))
    }

    @Test
    fun pathOperatorWithWrongArgumentsIsMalformed() {
        assertEquals(Kind.Malformed, failure(glyph("100 0 rmoveto 300 rlineto endchar")))
    }

    @Test
    fun calledSubroutineThatDoesNotExistIsMalformed() {
        assertEquals(Kind.Malformed, failure(glyph("100 0 rmoveto -107 callsubr endchar")))
    }

    @Test
    fun charStringsOfAnotherCountThanTheGlyphsAreMalformed() {
        assertEquals(Kind.Malformed, failure(TestCffFont(numGlyphs = 3)))
    }

    @Test
    fun charstringTypeOtherThan2IsUnsupported() {
        assertEquals(Kind.Unsupported, failure(TestCffFont(charstringType = 1)))
    }

    @Test
    fun skewedFontMatrixIsUnsupported() {
        val font = TestCffFont(fontMatrix = listOf("0.001", "0", "0.0002", "0.001", "0", "0"))

        assertEquals(Kind.Unsupported, failure(font))
    }

    @Test
    fun cffOfTwoFontsIsUnsupported() {
        assertEquals(Kind.Unsupported, failure(TestCffFont(fontNames = listOf("A", "B"))))
    }

    @Test
    fun cffOfAnotherMajorVersionIsUnsupported() {
        val font = TestCffFont()
        val cff = font.cffTable().also { it[0] = 2 }

        assertEquals(Kind.Unsupported, failure(TestSfnt.font(font.tables(cff), Tag.OPEN_TYPE_CFF)))
    }

    @Test
    fun indexOfOffsetSize0IsMalformed() {
        val font = TestCffFont()
        // The offset size of the Name INDEX, after the header and its count.
        val cff = font.cffTable().also { it[6] = 0 }

        assertEquals(Kind.Malformed, failure(TestSfnt.font(font.tables(cff), Tag.OPEN_TYPE_CFF)))
    }

    @Test
    fun indexNotStartingAtOffset1IsMalformed() {
        val font = TestCffFont()
        // The last byte of the first offset of the Name INDEX.
        val cff = font.cffTable().also { it[10] = 2 }

        assertEquals(Kind.Malformed, failure(TestSfnt.font(font.tables(cff), Tag.OPEN_TYPE_CFF)))
    }

    @Test
    fun indexBeyondTheTableIsMalformed() {
        val font = TestCffFont()
        // The first byte of the last offset of the Name INDEX.
        val cff = font.cffTable().also { it[11] = 0x7F }

        assertEquals(Kind.Malformed, failure(TestSfnt.font(font.tables(cff), Tag.OPEN_TYPE_CFF)))
    }

    /** A point with coordinates that are not rounded. */
    private data class D(val x: Double, val y: Double) {
        constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())
    }

    private fun cubicPoints(p0: D, p1: D, p2: D, p3: D): List<D> =
        (0..SAMPLES).map {
            val t = it.toDouble() / SAMPLES
            val s = 1 - t
            D(
                s * s * s * p0.x + 3 * s * s * t * p1.x + 3 * s * t * t * p2.x + t * t * t * p3.x,
                s * s * s * p0.y + 3 * s * s * t * p1.y + 3 * s * t * t * p2.y + t * t * t * p3.y,
            )
        }

    /**
     * Points along a TrueType spline: an on-curve point, off-curve points with on-curve points
     * implied between them, and an on-curve point.
     */
    private fun splinePoints(spline: List<P>): List<D> {
        val points = spline.map { D(it.x, it.y) }
        val result = ArrayList<D>()
        var start = points[0]
        for (i in 1 until points.size - 1) {
            val control = points[i]
            val next = points[i + 1]
            val end =
                if (i == points.size - 2) next
                else D((control.x + next.x) / 2, (control.y + next.y) / 2)
            for (step in 0..SAMPLES) {
                val t = step.toDouble() / SAMPLES
                val s = 1 - t
                result +=
                    D(
                        s * s * start.x + 2 * s * t * control.x + t * t * end.x,
                        s * s * start.y + 2 * s * t * control.y + t * t * end.y,
                    )
            }
            start = end
        }
        return result
    }

    /** The farthest that a point of [points] is from the polyline through [line]. */
    private fun maxDistance(points: List<D>, line: List<D>): Double = points.maxOf { p ->
        line.zipWithNext().minOf { (a, b) -> segmentDistance(p, a, b) }
    }

    private fun segmentDistance(p: D, a: D, b: D): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        val t =
            if (lengthSquared == 0.0) 0.0
            else (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private companion object {
        const val SAMPLES = 200
    }
}
