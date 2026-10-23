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

package com.google.vtfx.compose

import android.os.Looper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.testing.hyperlink
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalGesturesTest {
    @get:Rule val composeRule = createComposeRule()

    private val sent = StringBuilder()
    private val clipboard = RecordingClipboard()
    private val copied
        get() = clipboard.copied

    private val session =
        TerminalSession(
            { sent.append(String(it, Charsets.UTF_8)) },
            clipboard = clipboard,
        )
    private val display = TerminalDisplay(session)

    private val linkClicks = mutableListOf<TerminalLinkClick>()
    private val clickedLinks
        get() = linkClicks.map { it.link }

    private var onLinkClick: ((TerminalLinkClick) -> Unit)? by
        mutableStateOf({ click -> linkClicks += click })
    private var linkPatterns: List<LinkPattern> by mutableStateOf(listOf(LinkPattern.Urls))

    private fun show(output: String) {
        composeRule.setContent {
            Terminal(
                display,
                Modifier.size(300.dp, 200.dp),
                linkPatterns = linkPatterns,
                onLinkClick = onLinkClick,
            )
        }
        composeRule.waitForIdle()
        session.feed(output.toByteArray())
        composeRule.waitForIdle()
        sent.setLength(0)
    }

    private fun lines(count: Int) = (1..count).joinToString("\r\n") { "line $it" }

    /** Presses at [position] until the long press selects, and keeps the finger down. */
    private fun pressAndHold(position: Offset) {
        composeRule.onRoot().performTouchInput {
            down(position)
            advanceEventTime(LONG_PRESS_MILLIS)
        }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        composeRule.waitForIdle()
    }

    private fun moveAndLift(position: Offset) {
        composeRule.onRoot().performTouchInput {
            moveTo(position)
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun swipeDownShowsTheScrollback() {
        show(lines(100))
        composeRule.onRoot().performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue(display.scrollOffset > 0)
        composeRule.onNode(hasText("line 1\n", substring = true)).assertDoesNotExist()
        val first = display.displaySnapshot().line(0).text().trim()
        assertTrue(first, first.startsWith("line "))
        assertEquals("", sent.toString())
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun longPressSelectsTheWordAndCopies() {
        show("hello world")
        composeRule.onRoot().performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(1f, 5f))
        }
        composeRule.waitForIdle()
        assertEquals(TerminalSelection(Cell(0, 0), Cell(4, 0)), display.selection)
        assertTrue(display.copySelection())
        assertEquals(listOf("hello"), copied)
        assertEquals(null, display.selection)
        assertEquals("", sent.toString())
    }

    @Test
    fun movingAfterALongPressExtendsTheSelection() {
        show("hello world")
        pressAndHold(Offset(1f, 5f))
        moveAndLift(Offset(299f, 5f))
        val selection = display.selection!!
        assertEquals(Cell(0, 0), selection.start)
        assertTrue("${selection.end}", selection.end.column > 4)
    }

    @Test
    fun liftingTheFingerAfterALongPressShowsTheMenu() {
        val toolbar = FakeTextToolbar()
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(display, Modifier.size(300.dp, 200.dp))
            }
        }
        composeRule.waitForIdle()
        session.feed("hello world".toByteArray())
        composeRule.waitForIdle()
        pressAndHold(Offset(1f, 5f))
        assertEquals(emptyList<FakeTextToolbar.Menu>(), toolbar.menus)
        // The lift used to be missed, and the menu shown only on the next touch.
        moveAndLift(Offset(1f, 5f))
        val menu = toolbar.menus.single()
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        composeRule.runOnIdle { menu.onCopyRequested!!() }
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun newOutputDuringALongPressEndsTheSelection() {
        show("hello world")
        pressAndHold(Offset(1f, 5f))
        assertEquals(TerminalSelection(Cell(0, 0), Cell(4, 0)), display.selection)
        session.feed("!".toByteArray())
        composeRule.waitForIdle()
        moveAndLift(Offset(299f, 5f))
        assertEquals(null, display.selection)
    }

    @Test
    fun resizingDuringALongPressEndsTheSelection() {
        var width by mutableStateOf(300.dp)
        composeRule.setContent { Terminal(display, Modifier.size(width, 200.dp)) }
        composeRule.waitForIdle()
        session.feed("a b c d e f g h i j k l m n o p q r".toByteArray())
        composeRule.waitForIdle()
        pressAndHold(Offset(220f, 5f))
        assertTrue(display.selection != null)
        val columns = session.snapshot().columns
        width = 80.dp
        composeRule.waitForIdle()
        // Past the delay that groups resizes.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        composeRule.waitForIdle()
        assertTrue(session.snapshot().columns < columns)
        // A move beyond the new columns used to select cells that no longer exist.
        moveAndLift(Offset(240f, 5f))
        assertEquals(null, display.selection)
        assertFalse(display.copySelection())
        assertEquals(emptyList<String>(), copied)
    }

    @Test
    fun tapAndNewOutputDropTheSelection() {
        show("hello world")
        display.selection = TerminalSelection(Cell(0, 0), Cell(4, 0))
        composeRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(1f, 5f))
        }
        composeRule.waitForIdle()
        assertEquals(null, display.selection)
        display.selection = TerminalSelection(Cell(0, 0), Cell(4, 0))
        session.feed("!".toByteArray())
        composeRule.waitForIdle()
        assertEquals(null, display.selection)
    }

    @Test
    fun typingReturnsToTheBottom() {
        show(lines(100))
        display.scrollBy(10)
        session.sendText("x")
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun newOutputKeepsTheScrolledBackLines() {
        show(lines(100))
        display.scrollBy(10)
        val before = display.displaySnapshot().line(0).text()
        session.feed("\r\n${lines(5)}".toByteArray())
        composeRule.waitForIdle()
        assertEquals(before, display.displaySnapshot().line(0).text())
    }

    @Test
    fun swipeOnTheAlternateScreenSendsArrowKeys() {
        show("\u001b[?1049h")
        composeRule.onRoot().performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue(
            sent.toString(),
            sent.isNotEmpty() && sent.toString().replace("\u001b[A", "").isEmpty(),
        )
        assertEquals(0, display.scrollOffset)
    }

    @Test
    fun swipeOnTheAlternateScreenLeavesTheStickyModifiers() {
        show("\u001b[?1049h")
        composeRule.runOnIdle { session.stickyModifiers = KeyModifiers.of(ctrl = true) }
        composeRule.onRoot().performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue(
            sent.toString(),
            sent.isNotEmpty() && sent.toString().replace("\u001b[A", "").isEmpty(),
        )
        assertEquals(KeyModifiers.of(ctrl = true), session.stickyModifiers)
    }

    @Test
    fun mouseModeReportsTapsAndWheel() {
        show("\u001b[?1000h\u001b[?1006h")
        composeRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(1f, 1f))
        }
        composeRule.waitForIdle()
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", sent.toString())
        sent.setLength(0)
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertTrue(sent.toString(), sent.startsWith("\u001b[<65;"))
    }

    @Test
    fun mouseModeLeavesTouchesToTheTerminalWhileScrolledBack() {
        // Reports carry only a screen row, so a tap on a row of the scrollback would reach the
        // host as a click on a row that the user does not see.
        show("${lines(100)}\u001b[?1000h\u001b[?1006h")
        composeRule.runOnIdle { display.scrollBy(3) }
        composeRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        composeRule.waitForIdle()
        assertEquals("", sent.toString())
        // A swipe that starts scrolled back scrolls the scrollback, and stops at the bottom.
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(0, display.scrollOffset)
        assertEquals("", sent.toString())
        // Back at the bottom, the host gets the touches again.
        composeRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        composeRule.waitForIdle()
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", sent.toString())
    }

    @Test
    fun tapOnAHyperlinkCallsBack() {
        show("\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\ plain")
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.onRoot().performTouchInput { click(Offset(1f, height - 1f)) }
        composeRule.waitForIdle()
        assertEquals(
            listOf<TerminalLink>(
                TerminalLink.Explicit("link", hyperlink("https://example.com/a", null))
            ),
            clickedLinks,
        )
        assertEquals("", sent.toString())
    }

    @Test
    fun aTapOnALinkIsATouchWithoutModifiers() {
        show("\u001b]8;;https://example.com/a\u001b\\link")
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        val click = linkClicks.single()
        assertEquals(PointerType.Touch, click.pointerType)
        assertEquals(PointerKeyboardModifiers(0), click.keyboardModifiers)
    }

    @Test
    fun aFocusRequesterOnTheModifierFocusesTheTerminal() {
        // An app can focus the terminal itself, for example after connecting, as usage.md says.
        val requester = FocusRequester()
        composeRule.setContent {
            Terminal(
                display,
                Modifier.size(300.dp, 200.dp).focusRequester(requester),
            )
        }
        composeRule.waitForIdle()
        session.feed("\u001b[?1004h".toByteArray())
        composeRule.runOnIdle { requester.requestFocus() }
        composeRule.waitForIdle()
        assertEquals("\u001b[I", sent.toString())
    }

    @Test
    fun aHyperlinkThatWrapsIsReportedWithAllOfItsText() {
        show("")
        val columns = session.snapshot().columns
        val text = "x".repeat(columns - 2) + "あいう"
        session.feed(
            "ab\u001b]8;id=1;https://example.com/b\u001b\\$text\u001b]8;;\u001b\\ c".toByteArray()
        )
        composeRule.waitForIdle()
        val rows = session.snapshot().rows
        composeRule.onRoot().performTouchInput { click(Offset(1f, height / rows * 1.5f)) }
        composeRule.waitForIdle()
        assertEquals(
            listOf<TerminalLink>(
                TerminalLink.Explicit(text, hyperlink("https://example.com/b", "1"))
            ),
            clickedLinks,
        )
    }

    @Test
    fun tapOnAHyperlinkCallsTheLatestCallback() {
        show("\u001b]8;;https://example.com/a\u001b\\link")
        val latest = mutableListOf<String>()
        onLinkClick = { latest += it.link.url }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<TerminalLink>(), clickedLinks)
        assertEquals(listOf("https://example.com/a"), latest)
        onLinkClick = null
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.com/a"), latest)
    }

    @Test
    fun tapOnAUrlInTheTextCallsBack() {
        show("https://example.com/a. plain")
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.onRoot().performTouchInput { click(Offset(1f, height - 1f)) }
        composeRule.waitForIdle()
        assertEquals(
            listOf<TerminalLink>(
                TerminalLink.Matched(
                    "https://example.com/a",
                    "https://example.com/a",
                    LinkPattern.Urls,
                )
            ),
            clickedLinks,
        )
        assertEquals("", sent.toString())
    }

    @Test
    fun tapOnTextMatchingTheAppsPatternCallsBackWithItsUrl() {
        val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")
        linkPatterns = listOf(LinkPattern.Urls, crbug)
        show("crbug/123")
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        assertEquals(
            listOf<TerminalLink>(TerminalLink.Matched("crbug/123", "https://crbug.com/123", crbug)),
            clickedLinks,
        )
    }

    @Test
    fun tapUsesTheLatestPatterns() {
        show("crbug/123")
        val crbug = LinkPattern(Regex("""\bcrbug/(\d+)"""), "https://crbug.com/{1}")
        linkPatterns = listOf(crbug)
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://crbug.com/123"), clickedLinks.map { it.url })
        linkPatterns = emptyList()
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { click(Offset(1f, 5f)) }
        composeRule.waitForIdle()
        assertEquals(1, clickedLinks.size)
    }

    @Test
    fun mouseModeGetsTapsOnHyperlinks() {
        show("\u001b]8;;https://example.com/a\u001b\\link\u001b[?1000h\u001b[?1006h")
        composeRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        composeRule.waitForIdle()
        assertEquals(emptyList<TerminalLink>(), clickedLinks)
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", sent.toString())
    }

    private companion object {
        /** Longer than the long press timeout. */
        const val LONG_PRESS_MILLIS = 700L
    }
}
