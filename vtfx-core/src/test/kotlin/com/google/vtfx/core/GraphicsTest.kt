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

import com.google.vtfx.core.graphics.PlaceholderDiacritics
import com.google.vtfx.core.graphics.PngTestEncoder
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * The kitty graphics protocol (docs/decisions/0038): transmissions and their replies, placements
 * and how they follow the text, deletions, and the limits. The expected cursor positions after
 * placements are those that kitty 0.48.2 reported under Xvfb (the decision's background).
 */
@RunWith(Enclosed::class)
class GraphicsTest {
    class Replies : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun aQueryIsAnsweredWithItsId() {
            val terminal = terminal()
            terminal.write(apc("i=31,s=1,v=1,a=q,t=d,f=24", byteArrayOf(0, 0, 0)) + "\u001b[c")
            assertEquals(listOf(reply(31, GraphicsStatus.Ok)), replies())
            // The graphics reply comes before the device attributes, so programs can tell.
            assertTrue(events.indexOfFirst { it is TerminalEvent.ReplyRequested } == 0)
            assertEquals(0, terminal.graphicsLayer.imageCount, "a query stores nothing")
        }

        @Test
        fun commandsWithoutAnIdOrNumberAreNotAnswered() {
            val terminal = terminal()
            terminal.write(apc("a=q,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,s=2,v=1,f=24", byteArrayOf(0, 0, 0)))
            assertEquals(emptyList(), replies())
        }

        @Test
        fun quietSuppressesOkOrEveryReply() {
            val terminal = terminal()
            terminal.write(apc("a=t,i=1,q=1,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=p,i=999,q=1"))
            terminal.write(apc("a=p,i=999,q=2"))
            assertEquals(listOf(reply(999, GraphicsStatus.NotFound)), replies())
        }

        @Test
        fun errorsAreReported() {
            val terminal = terminal()
            terminal.write(apc("a=q,i=1,s=2,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=2,f=100", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=3,I=3,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=4,s=1,v=1,f=24,t=f", "/etc/passwd".toByteArray()))
            terminal.write(apc("a=q,i=5,s=1,v=1,f=24,t=t", "/tmp/x".toByteArray()))
            terminal.write(apc("a=q,i=6,s=1,v=1,f=24,t=s", "/shm".toByteArray()))
            terminal.write(apc("a=T,i=7,s=0,v=0,f=24"))
            terminal.write(apc("a=T,i=8,s=1,v=1,f=25", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=T,i=9,s=1,v=1,f=24,o=z", byteArrayOf(0, 0, 0, 0)))
            terminal.write(apc("a=f,i=10,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=T,i=11,s=20000,v=1,f=24", byteArrayOf(0, 0, 0)))
            assertEquals(
                listOf(
                    reply(1, GraphicsStatus.InsufficientData),
                    reply(2, GraphicsStatus.BadPng),
                    reply(3, GraphicsStatus.InvalidCommand, number = 3),
                    reply(4, GraphicsStatus.UnsupportedMedium),
                    reply(5, GraphicsStatus.UnsupportedMedium),
                    reply(6, GraphicsStatus.UnsupportedMedium),
                    reply(7, GraphicsStatus.InvalidCommand),
                    reply(8, GraphicsStatus.InvalidCommand),
                    reply(9, GraphicsStatus.InflateFailed),
                    reply(10, GraphicsStatus.Unsupported),
                    reply(11, GraphicsStatus.TooLarge),
                ),
                replies(),
            )
            assertEquals(0, terminal.graphicsLayer.imageCount)
        }

        @Test
        fun malformedCommandsAreIgnoredAsKittyDoes() {
            val terminal = terminal()
            // An unknown action, an unknown key, an id beyond 32 bits, a value that is no number.
            terminal.write(apc("a=x,i=1"))
            terminal.write(apc("a=q,i=2,K=1,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=4294967296,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=3,s=x,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=q,i=4294967295,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            assertEquals(listOf(reply(4294967295, GraphicsStatus.Ok)), replies())
        }

        @Test
        fun anImageNumberGetsAnIdThatTheReplyTells() {
            val terminal = terminal()
            terminal.write(apc("a=t,I=7,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=p,I=7,p=3"))
            assertEquals(
                listOf(
                    reply(1, GraphicsStatus.Ok, number = 7),
                    reply(1, GraphicsStatus.Ok, number = 7, placement = 3),
                ),
                replies(),
            )
        }

        @Test
        fun theProtocolIsOffWithoutRoomForImages() {
            val terminal = terminal(limits = TerminalLimits(maxImageBytes = 0))
            terminal.write(apc("a=q,i=1,s=1,v=1,f=24", byteArrayOf(0, 0, 0)))
            terminal.place("i=2")
            assertEquals(emptyList(), replies())
            assertEquals(Pair(0, 0), terminal.cursorAt())
        }

        @Test
        fun otherApplicationProgramCommandsAreIgnored() {
            val terminal = terminal()
            terminal.write("\u001b_Xa=q,i=1\u001b\\x")
            assertEquals(emptyList(), replies())
            assertEquals("x", terminal.snapshot().line(0).text().trim())
        }

        @Test
        fun pixelSizesAreAnsweredOnceTheCellSizeIsKnown() {
            val queries = "\u001b[14t\u001b[14;2t\u001b[14;1t\u001b[16t"
            val unknown = Terminal(80, 24) { events += it }
            unknown.write(queries)
            assertEquals(emptyList(), events)
            terminal().write(queries)
            assertEquals(
                listOf(
                    TerminalQuery.TextAreaPixelSize(19 * 24, 10 * 80),
                    TerminalQuery.TextAreaPixelSize(19 * 24, 10 * 80),
                    TerminalQuery.CellPixelSize(19, 10),
                ),
                events.map { (it as TerminalEvent.ReplyRequested).query },
            )
        }
    }

    class Transmission : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun chunksAreJoinedAndAnsweredOnceAtTheEnd() {
            val terminal = terminal()
            val data = Base64.getEncoder().encodeToString(rgba(4, 4, 0xFF00FF00.toInt()))
            terminal.write("\u001b_Ga=t,i=5,f=32,s=4,v=4,m=1;${data.substring(0, 16)}\u001b\\")
            terminal.write("\u001b_Gm=1;${data.substring(16, 40)}\u001b\\")
            assertEquals(emptyList(), replies())
            terminal.write("\u001b_Gm=0;${data.substring(40)}\u001b\\")
            assertEquals(listOf(reply(5, GraphicsStatus.Ok)), replies())
            val image = terminal.graphicsLayer.imageById(5)!!.image
            assertEquals(4, image.width)
            assertTrue(image.pixels.all { it == 0xFF00FF00.toInt() })
        }

        @Test
        fun chunksMayRepeatTheActionAsKittenIcatDoes() {
            val terminal = terminal()
            val data = Base64.getEncoder().encodeToString(rgba(2, 2))
            terminal.write("\u001b_Ga=T,i=6,f=32,s=2,v=2,m=1;${data.substring(0, 8)}\u001b\\")
            terminal.write("\u001b_Ga=T,q=2,m=0;${data.substring(8)}\u001b\\")
            assertEquals(listOf(reply(6, GraphicsStatus.Ok)), replies())
            assertEquals(1, terminal.snapshot().imagePlacements.size)
        }

        @Test
        fun anotherCommandAbandonsATransmission() {
            val terminal = terminal()
            terminal.write("\u001b_Ga=t,i=5,f=24,s=1,v=1,m=1;AA\u001b\\")
            terminal.write(apc("a=d,d=a"))
            terminal.write("\u001b_Gm=0;AA\u001b\\")
            assertEquals(emptyList(), replies())
            assertEquals(0, terminal.graphicsLayer.imageCount)
        }

        @Test
        fun compressedRgbAndPngAreDecoded() {
            val terminal = terminal()
            val deflater = Deflater()
            val raw = byteArrayOf(1, 2, 3, 4, 5, 6)
            deflater.setInput(raw)
            deflater.finish()
            val compressed = ByteArray(100).let { it.copyOf(deflater.deflate(it)) }
            terminal.write(apc("a=t,i=1,f=24,s=2,v=1,o=z", compressed))
            val png =
                PngTestEncoder.encode(
                    PngTestEncoder.Image(2, 1, 2, 8, intArrayOf(10, 20, 30, 40, 50, 60))
                )
            terminal.write(apc("a=t,i=2,f=100", png))
            terminal.write(apc("a=t,i=3,f=100,o=z,S=${png.size}", PngTestEncoder.deflate(png)))
            assertEquals(
                listOf(1L, 2L, 3L).map { reply(it, GraphicsStatus.Ok) },
                replies(),
            )
            assertEquals(
                listOf(0xFF010203.toInt(), 0xFF040506.toInt()),
                terminal.graphicsLayer.imageById(1)!!.image.pixels.toList(),
            )
            for (id in listOf(2L, 3L)) {
                assertEquals(
                    listOf(0xFF0A141E.toInt(), 0xFF28323C.toInt()),
                    terminal.graphicsLayer.imageById(id)!!.image.pixels.toList(),
                )
            }
        }

        @Test
        fun base64IsDecodedAsKittyDoes() {
            val terminal = terminal()
            // Without padding, and stopping at a character that is not base64.
            terminal.write("\u001b_Ga=q,i=1,f=24,s=1,v=1;AAAA\u001b\\")
            terminal.write("\u001b_Ga=q,i=2,f=24,s=1,v=1;AAA\u001b\\")
            terminal.write("\u001b_Ga=q,i=3,f=24,s=1,v=1;AA AA\u001b\\")
            terminal.write("\u001b_Ga=q,i=4,f=24,s=1,v=1;AAAAAAAA\u001b\\")
            assertEquals(
                listOf(
                    reply(1, GraphicsStatus.Ok),
                    reply(2, GraphicsStatus.InsufficientData),
                    reply(3, GraphicsStatus.InsufficientData),
                    reply(4, GraphicsStatus.Ok),
                ),
                replies(),
            )
        }

        @Test
        fun anImageWithTheSameIdReplacesTheOldOneAndItsPlacements() {
            val terminal = terminal()
            terminal.place("i=1")
            terminal.write(apc("a=t,i=1,f=24,s=1,v=1", byteArrayOf(0, 0, 0)))
            assertEquals(1, terminal.graphicsLayer.imageCount)
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
            assertEquals(4L, terminal.imageBytes)
        }
    }

    class Cursor : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun placementsMoveTheCursorToTheirLastRowAfterTheirLastColumn() {
            val terminal = terminal()
            terminal.write("\u001b[2;3H")
            terminal.place("c=3,r=2")
            assertEquals(Pair(5, 2), terminal.cursorAt())
        }

        @Test
        fun theSizeInPixelsGivesTheCells() {
            val terminal = terminal()
            // 20 by 40 pixels in cells of 10 by 19: 2 columns, 3 rows.
            terminal.place("")
            assertEquals(Pair(2, 2), terminal.cursorAt())
            val placement = terminal.snapshot().imagePlacements.single()
            assertEquals(2f, placement.width)
            assertEquals(40f / 19, placement.height)
        }

        @Test
        fun cursorPolicyOneKeepsTheCursor() {
            val terminal = terminal()
            terminal.write("\u001b[2;3H")
            terminal.place("c=3,r=2,C=1")
            assertEquals(Pair(2, 1), terminal.cursorAt())
        }

        @Test
        fun theRightEdgeWrapsToTheNextRow() {
            val terminal = terminal()
            terminal.write("\u001b[1;78H")
            terminal.place("c=3,r=2")
            assertEquals(Pair(0, 2), terminal.cursorAt())
        }

        @Test
        fun theBottomScrolls() {
            val terminal = terminal()
            terminal.write("top\u001b[24;1H")
            terminal.place("c=3,r=4")
            assertEquals(Pair(3, 23), terminal.cursorAt())
            assertEquals(3, terminal.scrollbackSize)
            // The image scrolled with its line: it starts 3 rows above the bottom row.
            assertEquals(20, terminal.snapshot().imagePlacements.single().row)
        }

        @Test
        fun aPendingWrapIsIgnored() {
            val terminal = terminal()
            terminal.write("\u001b[1;80HX")
            terminal.place("c=3,r=2")
            assertEquals(Pair(0, 2), terminal.cursorAt())
            assertEquals(79, terminal.snapshot().imagePlacements.single().column)
        }
    }

    class FollowingTheText : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun placementsScrollIntoTheScrollbackAndShowThere() {
            val terminal = terminal(rows = 5)
            terminal.write("\u001b[2;4H")
            terminal.place("c=2,r=2")
            terminal.write("\r\n".repeat(10))
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
            val placement = terminal.snapshot(scrollOffset = 8).imagePlacements.single()
            assertEquals(1, placement.row)
            assertEquals(3, placement.column)
            // Partly visible at the top.
            assertEquals(-1, terminal.snapshot(scrollOffset = 6).imagePlacements.single().row)
            terminal.checkPlacements()
        }

        @Test
        fun placementsGoWithTheirLineOutOfTheScrollback() {
            val terminal = terminal(rows = 5, limits = TerminalLimits(scrollbackLines = 3))
            terminal.place("i=1,c=1,r=1")
            terminal.write("\r\n".repeat(5))
            assertEquals(1, terminal.graphicsLayer.placementCount)
            terminal.write("\r\n".repeat(3))
            assertEquals(0, terminal.graphicsLayer.placementCount)
            // The image stays, without placements, until it is deleted or evicted.
            assertEquals(1, terminal.graphicsLayer.imageCount)
            terminal.checkPlacements()
        }

        @Test
        fun anImageWithoutAnIdGoesWithItsLastPlacement() {
            val terminal = terminal(rows = 5, limits = TerminalLimits(scrollbackLines = 0))
            terminal.place("c=1,r=1")
            assertEquals(1, terminal.graphicsLayer.imageCount)
            terminal.write("\r\n".repeat(5))
            assertEquals(0, terminal.graphicsLayer.imageCount)
            assertEquals(0L, terminal.imageBytes)
        }

        @Test
        fun scrollingRegionsAndDeletedLinesTakePlacementsAlong() {
            val terminal = terminal(rows = 10)
            terminal.write("\u001b[5;1H")
            terminal.place("c=1,r=1,C=1")
            // Insert two lines above it: it moves down.
            terminal.write("\u001b[3;1H\u001b[2L")
            assertEquals(6, terminal.snapshot().imagePlacements.single().row)
            // Scroll a region that holds it.
            terminal.write("\u001b[4;9r\u001b[9;1H\n")
            assertEquals(5, terminal.snapshot().imagePlacements.single().row)
            // Delete its line.
            terminal.write("\u001b[r\u001b[6;1H\u001b[M")
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
            terminal.checkPlacements()
        }

        @Test
        fun erasingTextLeavesImagesButClearingTheScreenDeletesThem() {
            val terminal = terminal()
            terminal.write("\u001b[3;1H")
            terminal.place("i=1,c=2,r=2")
            terminal.write("\u001b[H\u001b[J\u001b[3;1H\u001b[2K\u001b[5X")
            assertEquals(1, terminal.snapshot().imagePlacements.size)
            terminal.write("\u001b[2J")
            assertEquals(0, terminal.snapshot().imagePlacements.size)
            assertEquals(1, terminal.graphicsLayer.imageCount, "the image data stays")
        }

        @Test
        fun clearingTheScreenDeletesImagesThatStartInTheScrollback() {
            val terminal = terminal(rows = 5)
            terminal.place("i=1,c=1,r=4,C=1")
            terminal.write("\u001b[5;1H\n")
            assertEquals(-1, terminal.snapshot().imagePlacements.single().row)
            terminal.write("\u001b[2J")
            assertEquals(0, terminal.graphicsLayer.placementCount)
        }

        @Test
        fun theAlternateScreenHasItsOwnImagesWhichGoWhenItIsLeft() {
            val terminal = terminal()
            terminal.place("i=1,c=1,r=1")
            terminal.write("\u001b[?1049h")
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
            terminal.write(apc("a=p,i=1"))
            assertEquals(listOf(reply(1, GraphicsStatus.NotFound)), replies())
            terminal.place("i=2,c=1,r=1")
            assertEquals(1, terminal.snapshot().imagePlacements.size)
            terminal.write("\u001b[?1049l")
            assertEquals(1, terminal.snapshot().imagePlacements.size)
            assertEquals(20L * 40 * 4, terminal.imageBytes, "only the main screen's image")
        }

        @Test
        fun aResetDeletesTheImagesOnTheScreen() {
            val terminal = terminal()
            terminal.place("i=1,c=1,r=1")
            terminal.write("\u001bc")
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
        }

        @Test
        fun reflowTakesPlacementsAlongWithTheirCell() {
            val terminal = terminal(columns = 10, rows = 5)
            terminal.write("0123456789ab\u001b[1;3H")
            // On "2".
            terminal.place("c=1,r=1,C=1")
            terminal.resize(4, 5)
            // "0123" "4567" "89ab": "2" is still in the first row.
            assertEquals(Pair(0, 2), terminal.placed())
            terminal.resize(2, 5)
            // "01" "23" ...: six rows, so "01" goes to the scrollback.
            assertEquals(Pair(0, 0), terminal.placed())
            assertEquals(1, terminal.scrollbackSize)
            terminal.resize(20, 5)
            assertEquals(Pair(0, 2), terminal.placed())
            terminal.checkPlacements()
        }

        @Test
        fun aPlacementPastTheContentStaysOnTheContentsLastRow() {
            val terminal = terminal(columns = 10, rows = 5)
            terminal.write("0123456789ab")
            // After "ab", on the second row.
            terminal.place("c=1,r=1,C=1")
            terminal.resize(4, 5)
            // "0123" "4567" "89ab": the cell after "b" would be on a fourth row, which reflow does
            // not add for an image.
            assertEquals(Pair(2, 3), terminal.placed())
            terminal.checkPlacements()
        }

        private fun Terminal.placed(): Pair<Int, Int> =
            snapshot().imagePlacements.single().let { Pair(it.row, it.column) }

        @Test
        fun aLineWithOnlyAPlacementIsKeptByReflow() {
            val terminal = terminal(columns = 10, rows = 5)
            terminal.write("\u001b[4;5H")
            terminal.place("c=1,r=1,C=1")
            terminal.write("\u001b[H")
            terminal.resize(8, 5)
            assertEquals(1, terminal.snapshot().imagePlacements.size)
            terminal.checkPlacements()
        }

        @Test
        fun theAlternateScreenKeepsPlacementsWithinTheNewSize() {
            val terminal = terminal(columns = 10, rows = 5)
            terminal.write("\u001b[?1049h\u001b[2;3H")
            terminal.place("c=1,r=1,C=1")
            terminal.write("\u001b[4;9H")
            terminal.place("c=1,r=1,C=1")
            terminal.resize(6, 3)
            val placement = terminal.snapshot().imagePlacements.single()
            assertEquals(Pair(1, 2), Pair(placement.row, placement.column))
            terminal.checkPlacements()
        }
    }

    class Deletion : GraphicsEvents by GraphicsRecorder() {
        private fun Terminal.placeAt(row: Int, column: Int, keys: String) {
            write("\u001b[${row};${column}H")
            place("$keys,C=1")
        }

        private fun Terminal.placed(): List<Pair<Int, Int>> =
            snapshot().imagePlacements.map { Pair(it.row, it.column) }

        private fun threeImages(): Terminal =
            terminal().apply {
                placeAt(1, 1, "i=1,c=2,r=2,z=-1")
                placeAt(5, 5, "i=2,c=2,r=2,z=3")
                placeAt(10, 10, "i=3,c=2,r=2,p=7")
            }

        @Test
        fun byKind() {
            val cases =
                mapOf(
                    "d=a" to emptyList(),
                    "" to emptyList(),
                    "d=i,i=2" to listOf(Pair(0, 0), Pair(9, 9)),
                    "d=i,i=3,p=8" to listOf(Pair(0, 0), Pair(4, 4), Pair(9, 9)),
                    "d=i,i=3,p=7" to listOf(Pair(0, 0), Pair(4, 4)),
                    "d=p,x=6,y=6" to listOf(Pair(0, 0), Pair(9, 9)),
                    "d=p,x=7,y=6" to listOf(Pair(0, 0), Pair(4, 4), Pair(9, 9)),
                    "d=q,x=1,y=1,z=-1" to listOf(Pair(4, 4), Pair(9, 9)),
                    "d=q,x=1,y=1,z=0" to listOf(Pair(0, 0), Pair(4, 4), Pair(9, 9)),
                    "d=x,x=11" to listOf(Pair(0, 0), Pair(4, 4)),
                    "d=y,y=2" to listOf(Pair(4, 4), Pair(9, 9)),
                    "d=z,z=3" to listOf(Pair(0, 0), Pair(9, 9)),
                    "d=r,x=2,y=3" to listOf(Pair(0, 0)),
                    "d=f" to listOf(Pair(0, 0), Pair(4, 4), Pair(9, 9)),
                )
            for ((keys, expected) in cases) {
                val terminal = threeImages()
                terminal.write(apc("a=d,$keys"))
                assertEquals(expected, terminal.placed().sortedBy { it.first }, keys)
                terminal.checkPlacements()
            }
        }

        @Test
        fun atTheCursor() {
            val terminal = threeImages()
            terminal.write("\u001b[6;5H" + apc("a=d,d=c"))
            assertEquals(listOf(Pair(0, 0), Pair(9, 9)), terminal.placed().sortedBy { it.first })
        }

        @Test
        fun uppercaseFreesTheImages() {
            val terminal = threeImages()
            terminal.write(apc("a=d,d=i,i=2"))
            assertEquals(3, terminal.graphicsLayer.imageCount)
            terminal.write(apc("a=d,d=I,i=3"))
            assertEquals(2, terminal.graphicsLayer.imageCount)
            terminal.write(apc("a=d,d=A"))
            // Image 2 had no placement left to delete, so it stays.
            assertEquals(listOf(2L), terminal.graphicsLayer.images().map { it.id })
        }

        @Test
        fun placementsOffTheScreenAreNotDeletedByPosition() {
            val terminal = terminal(rows = 5)
            terminal.place("i=1,c=1,r=1")
            terminal.write("\r\n".repeat(10))
            terminal.write(apc("a=d,d=a"))
            assertEquals(1, terminal.graphicsLayer.placementCount)
            terminal.write(apc("a=d,d=i,i=1"))
            assertEquals(0, terminal.graphicsLayer.placementCount)
            terminal.checkPlacements()
        }

        @Test
        fun aPlacementWithTheSameIdReplacesTheOldOne() {
            val terminal = terminal()
            terminal.write(apc("a=t,i=1,f=24,s=1,v=1,q=2", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=p,i=1,p=1,C=1,q=2") + "\u001b[3;3H" + apc("a=p,i=1,p=1,C=1,q=2"))
            terminal.write(apc("a=p,i=1,p=2,C=1,q=2"))
            assertEquals(listOf(Pair(2, 2), Pair(2, 2)), terminal.placed())
        }
    }

    class Limits : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun theOldestImagesGoFirstThoseWithoutPlacementsBeforeTheOthers() {
            // Room for three images of 10 by 10.
            val terminal = terminal(limits = TerminalLimits(maxImageBytes = 1200))
            terminal.place("i=1,c=1,r=1", 10, 10)
            terminal.write(apc("a=t,i=2,f=32,s=10,v=10,q=2", rgba(10, 10)))
            terminal.write(apc("a=t,i=3,f=32,s=10,v=10,q=2", rgba(10, 10)))
            terminal.write(apc("a=t,i=4,f=32,s=10,v=10,q=2", rgba(10, 10)))
            assertEquals(listOf(1L, 3L, 4L), terminal.graphicsLayer.images().map { it.id })
            terminal.write(apc("a=t,i=5,f=32,s=10,v=10,q=2", rgba(10, 10)))
            terminal.write(apc("a=t,i=6,f=32,s=10,v=10,q=2", rgba(10, 10)))
            terminal.write(apc("a=t,i=7,f=32,s=10,v=10,q=2", rgba(10, 10)))
            // Image 1 has a placement, so the newer images without placements go before it.
            assertEquals(listOf(1L, 6L, 7L), terminal.graphicsLayer.images().map { it.id })
            assertEquals(1, terminal.snapshot().imagePlacements.size)
            // When every image has a placement, the oldest goes with its placements.
            terminal.write(apc("a=p,i=6,q=2") + apc("a=p,i=7,q=2"))
            terminal.write(apc("a=t,i=8,f=32,s=10,v=10,q=2", rgba(10, 10)))
            assertEquals(listOf(6L, 7L, 8L), terminal.graphicsLayer.images().map { it.id })
            assertEquals(2, terminal.snapshot().imagePlacements.size)
            assertEquals(1200L, terminal.imageBytes)
            terminal.checkPlacements()
        }

        @Test
        fun anImageLargerThanTheLimitIsRefused() {
            val terminal = terminal(limits = TerminalLimits(maxImageBytes = 399))
            terminal.write(apc("a=t,i=1,f=32,s=10,v=10", rgba(10, 10)))
            assertEquals(listOf(reply(1, GraphicsStatus.TooLarge)), replies())
        }

        @Test
        fun theDataOfATransmissionIsBounded() {
            val terminal = terminal(limits = TerminalLimits(maxImageBytes = 1000))
            val chunk = "A".repeat(4096)
            terminal.write("\u001b_Ga=t,i=1,f=100,m=1;$chunk\u001b\\")
            terminal.write("\u001b_Gm=0;$chunk\u001b\\")
            assertEquals(listOf(reply(1, GraphicsStatus.TooLarge)), replies())
        }

        @Test
        fun theOldestPlacementsGoBeyondTheLimit() {
            val terminal = terminal()
            terminal.write(apc("a=t,i=1,f=24,s=1,v=1,q=2", byteArrayOf(0, 0, 0)))
            repeat(1030) { terminal.write(apc("a=p,i=1,C=1,q=2")) }
            assertEquals(1024, terminal.graphicsLayer.placementCount)
            terminal.checkPlacements()
        }

        @Test
        fun placementsAreAtMostTheLargestDimension() {
            val terminal = terminal()
            terminal.write(apc("a=t,i=1,f=24,s=1,v=1,q=2", byteArrayOf(0, 0, 0)))
            terminal.write(apc("a=p,i=1,c=4294967295,r=4294967295,C=1,q=2"))
            val placement = terminal.snapshot().imagePlacements.single()
            assertEquals(TerminalLimits.MAX_DIMENSION.toFloat(), placement.width)
        }
    }

    class UnicodePlaceholders : GraphicsEvents by GraphicsRecorder() {
        /** A placeholder with the diacritics of [values]: row, column, and high byte. */
        private fun cell(vararg values: Int): String = buildString {
            appendCodePoint(0x10EEEE)
            for (value in values) appendCodePoint(PlaceholderDiacritics.CODE_POINTS[value])
        }

        /** Transmits image [id] with a virtual placement of [keys]. */
        private fun Terminal.virtual(id: Long = 42, keys: String = "c=2,r=2") =
            write(apc("a=T,i=$id,U=1,f=24,s=2,v=2,q=2,$keys", ByteArray(12)))

        private fun Terminal.tiles(): List<String> =
            snapshot().imageTiles.map {
                "${it.row},${it.column}: ${it.imageRow},${it.imageColumn} of ${it.columns}x${it.rows}"
            }

        @Test
        fun placeholdersAreOneCellWideAndNotText() {
            // U+10EEEE is East Asian Ambiguous, as private use characters are.
            val terminal = Terminal(10, 2, ambiguousWidth = AmbiguousWidth.Wide)
            terminal.write("\u001b[38;5;42m" + cell(0, 0) + cell(0, 1) + "x")
            val line = terminal.snapshot().line(0)
            assertEquals(listOf(1, 1, 1), (0..2).map { line.widthAt(it) })
            assertEquals(listOf(null, null, "x"), (0..2).map { line.textAt(it) })
            assertEquals("  x", line.text().trimEnd())
            assertEquals(CursorState(3, 0), terminal.cursor)
        }

        @Test
        fun cellsShowTheirPartOfTheVirtualPlacement() {
            val terminal = terminal()
            terminal.virtual()
            terminal.write(
                "\u001b[38;5;42m" + cell(0, 0) + cell(0, 1) + "\r\n" + cell(1, 0) + cell(1, 1)
            )
            assertEquals(
                listOf("0,0: 0,0 of 2x2", "0,1: 0,1 of 2x2", "1,0: 1,0 of 2x2", "1,1: 1,1 of 2x2"),
                terminal.tiles(),
            )
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
        }

        @Test
        fun missingDiacriticsComeFromTheCellToTheLeft() {
            val terminal = terminal()
            terminal.virtual(keys = "c=3,r=2")
            // Only the row on the first cell of each row, as the protocol's example; then a cell
            // with a row and a column, and one with nothing after a cell of another color.
            terminal.write(
                "\u001b[38;5;42m" +
                    cell(0) +
                    cell() +
                    cell() +
                    "\r\n" +
                    cell(1) +
                    cell() +
                    "\u001b[38;5;7m" +
                    cell() +
                    "\u001b[38;5;42m" +
                    cell()
            )
            assertEquals(
                listOf(
                    "0,0: 0,0 of 3x2",
                    "0,1: 0,1 of 3x2",
                    "0,2: 0,2 of 3x2",
                    "1,0: 1,0 of 3x2",
                    "1,1: 1,1 of 3x2",
                    // Image 7 has no virtual placement; then nothing to the left to follow.
                    "1,3: 0,0 of 3x2",
                ),
                terminal.tiles(),
            )
        }

        @Test
        fun theThirdDiacriticAndDirectColorsGiveLargerIds() {
            val terminal = terminal()
            terminal.virtual(id = 42 + (2L shl 24), keys = "c=1,r=1")
            terminal.virtual(id = 0x010203, keys = "c=1,r=1")
            terminal.write("\u001b[38;5;42m" + cell(0, 0, 2) + "\u001b[38;2;1;2;3m" + cell(0, 0))
            assertEquals(listOf("0,0: 0,0 of 1x1", "0,1: 0,0 of 1x1"), terminal.tiles())
            val images = terminal.snapshot().imageTiles.map { it.image }
            assertEquals(
                listOf(42 + (2L shl 24), 0x010203L).map {
                    terminal.graphicsLayer.imageById(it)!!.image
                },
                images,
            )
        }

        @Test
        fun theUnderlineColorChoosesThePlacementWithoutAnUnderline() {
            val terminal = terminal()
            terminal.write(apc("a=t,i=42,f=24,s=2,v=2,q=2", ByteArray(12)))
            terminal.write(apc("a=p,i=42,U=1,p=1,c=1,r=1,q=2"))
            terminal.write(apc("a=p,i=42,U=1,p=2,c=2,r=2,q=2"))
            terminal.write(apc("a=p,i=42,U=1,p=3,c=3,r=3,q=2"))
            terminal.write("\u001b[38;5;42;58;5;2m" + cell(0, 0) + "\u001b[59m" + cell(0, 0))
            // Without a placement id, the newest virtual placement.
            assertEquals(listOf("0,0: 0,0 of 2x2", "0,1: 0,0 of 3x3"), terminal.tiles())
            // The color does not make the cell underlined.
            assertEquals(UnderlineStyle.None, terminal.snapshot().line(0).styleAt(0).underline)
        }

        @Test
        fun placeholdersShowNothingWithoutAnImage() {
            val terminal = terminal()
            terminal.virtual(keys = "c=2,r=2")
            terminal.write(
                // The default color names no image; image 43 does not exist; row 2 is outside.
                cell(0, 0) + "\u001b[38;5;43m" + cell(0, 0) + "\u001b[38;5;42m" + cell(2, 0)
            )
            assertEquals(emptyList(), terminal.tiles())
        }

        @Test
        fun deletingTheImageOrItsVirtualPlacementRemovesTheTiles() {
            val terminal = terminal()
            terminal.virtual()
            terminal.write("\u001b[38;5;42m" + cell(0, 0))
            assertEquals(1, terminal.tiles().size)
            // Deleting what is on the screen does not touch virtual placements.
            terminal.write(apc("a=d,d=a"))
            assertEquals(1, terminal.tiles().size)
            terminal.write(apc("a=d,d=i,i=42"))
            assertEquals(emptyList(), terminal.tiles())
        }
    }

    /** Placements that the other groups do not cover. */
    class Placement : GraphicsEvents by GraphicsRecorder() {
        @Test
        fun virtualPlacementsNeitherShowNorMoveTheCursor() {
            val terminal = terminal()
            terminal.write(apc("a=T,i=1,U=1,c=2,r=2,f=24,s=1,v=1,q=2", byteArrayOf(0, 0, 0)))
            assertEquals(Pair(0, 0), terminal.cursorAt())
            assertEquals(emptyList(), terminal.snapshot().imagePlacements)
            assertEquals(1, terminal.graphicsLayer.placementCount)
        }

        @Test
        fun sourceRectanglesAndOffsets() {
            val terminal = terminal()
            terminal.write(
                apc("a=T,i=1,f=32,s=20,v=40,x=5,y=10,w=100,h=20,X=4,Y=30,q=2", rgba(20, 40))
            )
            val placement = terminal.snapshot().imagePlacements.single()
            assertEquals(
                listOf(5, 10, 15, 20),
                with(placement) {
                    listOf(sourceX, sourceY, sourceWidth, sourceHeight)
                },
            )
            // The offset in a cell is less than the cell: 4 of 10, and 18 of 19.
            assertEquals(0.4f, placement.left)
            assertEquals(18f / 19, placement.top)
            // 4 + 15 pixels take 2 columns; 18 + 20 take 2 rows.
            assertEquals(Pair(2, 1), terminal.cursorAt())
        }
    }
}

/** The events of the terminals of a test of [GraphicsTest]. */
interface GraphicsEvents {
    val events: MutableList<TerminalEvent>

    /** A terminal with cells of 10 by 19 pixels whose events go to [events]. */
    fun terminal(
        columns: Int = 80,
        rows: Int = 24,
        limits: TerminalLimits = TerminalLimits(),
    ): Terminal = Terminal(columns, rows, limits) { events += it }.apply { setCellSize(10, 19) }

    fun replies(): List<TerminalQuery.GraphicsReply> = events.mapNotNull {
        (it as? TerminalEvent.ReplyRequested)?.query as? TerminalQuery.GraphicsReply
    }
}

/** A fresh [GraphicsEvents] for each test. */
private class GraphicsRecorder : GraphicsEvents {
    override val events = mutableListOf<TerminalEvent>()
}

private fun Terminal.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

private fun reply(id: Long, status: GraphicsStatus, number: Long = 0, placement: Long = 0) =
    TerminalQuery.GraphicsReply(id, number, placement, status)

/** A command with the base64 of [payload], if any. */
private fun apc(control: String, payload: ByteArray? = null): String =
    "\u001b_G$control" +
        (payload?.let { ";" + Base64.getEncoder().encodeToString(it) } ?: "") +
        "\u001b\\"

/** RGBA pixels of [width] by [height], all [color] (0xAARRGGBB). */
private fun rgba(width: Int, height: Int, color: Int = 0xFFFF0000.toInt()): ByteArray {
    val bytes = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        bytes[i * 4] = (color shr 16).toByte()
        bytes[i * 4 + 1] = (color shr 8).toByte()
        bytes[i * 4 + 2] = color.toByte()
        bytes[i * 4 + 3] = (color ushr 24).toByte()
    }
    return bytes
}

/** Transmits and places an RGBA image of [width] by [height] with [keys]. */
private fun Terminal.place(keys: String, width: Int = 20, height: Int = 40) =
    write(apc("a=T,f=32,s=$width,v=$height,q=2,$keys", rgba(width, height)))

private fun Terminal.cursorAt(): Pair<Int, Int> = Pair(cursor.column, cursor.row)

/**
 * Checks that every live placement anchored to a line is found on the screen or in the scrollback:
 * a placement whose line went away without it would be kept but never shown.
 */
private fun Terminal.checkPlacements() {
    var anchored = 0
    graphicsLayer.forEachPlacement { if (!it.virtual) anchored++ }
    var found = 0
    graphicsLayer.forEachAnchored { placement, row ->
        found++
        assertTrue(row >= -scrollbackSize && row < rows, "row $row of $placement")
    }
    assertEquals(anchored, found, "placements not on any line")
}
