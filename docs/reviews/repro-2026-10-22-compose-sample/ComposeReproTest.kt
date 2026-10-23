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

import android.app.Activity
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import com.google.vtfx.core.AmbiguousWidth
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.session.TerminalSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ComposeReproTest {
    @get:Rule val composeRule = createComposeRule()

    // R1: An empty LocaleList in TerminalStyle crashes Paint.setTextLocales with
    // IllegalArgumentException ("locales cannot be null or empty").
    @Test
    fun emptyLocaleListInTerminalStyleDoesNotCrash() {
        val session = TerminalSession({})
        composeRule.setContent {
            Terminal(
                session = session,
                modifier = Modifier.size(200.dp, 100.dp),
                style = TerminalStyle(locale = LocaleList.Empty),
            )
        }
        composeRule.waitForIdle()
    }

    // R2: Clicking an OSC 8 hyperlink that wraps before a wide character (leaving a wide-padding
    // cell at the end of the first row) truncates the reported link text at the wrap boundary.
    @Test
    fun clickingAnOsc8HyperlinkWrappedBeforeAWideCharacterReportsFullText() {
        val session = TerminalSession({})
        val display = TerminalDisplay(session)
        val clicked = mutableListOf<TerminalLink>()
        composeRule.setContent {
            Terminal(
                display = display,
                modifier = Modifier.size(300.dp, 200.dp),
                onLinkClick = { clicked += it.link },
            )
        }
        composeRule.waitForIdle()
        // Resize to 5 columns so "abcd" fills columns 0..3 of row 0 and wide "あ" wraps to row 1,
        // leaving a wide-padding cell at column 4 of row 0.
        session.resize(columns = 5, rows = 4)
        session.feed("\u001b]8;;https://example.com/\u0007abcdあ\u001b]8;;\u0007".toByteArray())
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        composeRule.waitForIdle()

        assertEquals(listOf("abcdあ"), clicked.map { it.text })
    }

    // R3: Word selection treats the right half (width == 0) of any wide character as non-blank,
    // so selecting a word after a concealed wide character or U+3000 IDEOGRAPHIC SPACE includes it.
    @Test
    fun wordSelectionDoesNotIncludePrecedingBlankOrConcealedWideCharacter() {
        val session = TerminalSession({})
        session.resize(columns = 20, rows = 4)
        session.feed("　foo \u001b[8m秘密\u001b[28mbar".toByteArray())
        val snapshot = session.snapshot()

        // "foo" is at columns 2..4 after U+3000 at columns 0..1.
        val firstWord = TerminalSelection.word(snapshot, Cell(2, 0))
        assertEquals("foo", firstWord.text(snapshot))
        assertEquals(Cell(2, 0), firstWord.start)

        // "bar" is at columns 10..12 after concealed "秘密" at columns 6..9.
        val secondWord = TerminalSelection.word(snapshot, Cell(10, 0))
        assertEquals("bar", secondWord.text(snapshot))
        assertEquals(Cell(10, 0), secondWord.start)
    }

    // R4: Touch gestures in the inner padding treat drags and long presses as taps (hiding the
    // selection toolbar), and a tap in the padding does not clear the selection.
    @Test
    fun paddingIgnoresDragsAndClearsSelectionOnTap() {
        val session = TerminalSession({})
        val display = TerminalDisplay(session)
        val toolbar =
            object : TextToolbar {
                override var status = TextToolbarStatus.Hidden

                override fun showMenu(
                    rect: Rect,
                    onCopyRequested: (() -> Unit)?,
                    onPasteRequested: (() -> Unit)?,
                    onCutRequested: (() -> Unit)?,
                    onSelectAllRequested: (() -> Unit)?,
                ) {
                    status = TextToolbarStatus.Shown
                }

                override fun hide() {
                    status = TextToolbarStatus.Hidden
                }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Terminal(
                    display = display,
                    modifier = Modifier.size(300.dp, 200.dp),
                    style = TerminalStyle(padding = PaddingValues(30.dp)),
                )
            }
        }
        composeRule.waitForIdle()
        session.feed("hello world".toByteArray())
        composeRule.waitForIdle()

        // Long-press on the grid (inside the 30.dp padding) to select "hello" and show the menu.
        val gridCell = with(composeRule.density) { Offset(35.dp.toPx(), 35.dp.toPx()) }
        composeRule.onRoot().performTouchInput { longClick(gridCell) }
        composeRule.waitForIdle()
        assertNotNull(display.selection)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        // A drag entirely within the top padding must do nothing (section 10.2 of compose-input.md).
        composeRule.onRoot().performTouchInput {
            down(Offset(5f, 5f))
            moveTo(Offset(100f, 5f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        // A tap in the padding must clear the selection, as a tap on the grid does (section 4 and
        // section 10.2 of compose-input.md).
        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        composeRule.waitForIdle()
        assertNull(display.selection)
    }

    // R5: A focused block cursor on a box-drawing or block-element character draws the font glyph
    // instead of BoxDrawing.drawBox, leaving gaps and failing to span both cells when wide.
    @Test
    fun focusedBlockCursorOnFullBlockDrawsBoxDrawingInsteadOfFontGlyph() {
        val session = TerminalSession({}, ambiguousWidth = AmbiguousWidth.Wide)
        composeRule.setContent { Terminal(session, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        // Focus the terminal so the block cursor is filled and redraws the character in
        // cursorTextColor (the default background color, 0xFF000000).
        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        composeRule.waitForIdle()
        // Print U+2588 FULL BLOCK at (0, 0) and move the cursor back onto it at (0, 0).
        session.feed("\u2588\u001b[1;1H".toByteArray())
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // Because U+2588 is a full block drawn in cursorTextColor (default background) over the
        // block cursor (cursorColor = 0xFFE5E5E5), no pixels of cursorColor should remain visible.
        val cursorColor = 0xFFE5E5E5.toInt()
        var leftoverCursorPixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == cursorColor) leftoverCursorPixels++
            }
        }
        assertEquals(0, leftoverCursorPixels)
    }

    // R6: TerminalDisplay.detach() removes mainHandler callbacks without resetting
    // invalidationPending, permanently wedging display updates if reattached.
    @Test
    fun detachingWhileBackgroundInvalidationIsPendingDoesNotWedgeFutureUpdates() {
        val session = TerminalSession({})
        val display = TerminalDisplay(session)
        display.attach()

        // Feed from a background thread so invalidate() sets invalidationPending = true and posts
        // to the main looper without running the callback yet.
        val latch = CountDownLatch(1)
        thread {
            session.feed("first".toByteArray())
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Detach before the main looper runs the posted runnable, then reattach.
        display.detach()
        display.attach()
        val versionBefore = display.observeVersion()

        session.feed("second".toByteArray())
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNotEquals(versionBefore, display.observeVersion())
    }

    // R7: TerminalInputView.onDetachedFromWindow() calls bind(null), permanently unbinding the
    // display if the view is detached from and reattached to a window without AndroidView.update.
    @Test
    fun reattachingTerminalInputViewToWindowKeepsSessionBound() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = LinearLayout(activity.get())
            val view = TerminalInputView(activity.get())
            parent.addView(view)
            activity.get().setContentView(parent)

            val sent = StringBuilder()
            val session = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
            val display = TerminalDisplay(session)
            view.bind(display)

            // Detach and reattach the view to the window (as happens on reparenting / movableContentOf).
            parent.removeView(view)
            parent.addView(view)
            assertTrue(view.requestFocus())
            view.dispatchWindowFocusChanged(true)

            val connection = view.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    // R8: When mouse tracking is enabled, a mouse click reported to the host does not drop the
    // selection (unlike a touch tap or a mouse click when not reporting), violating section 3 and
    // section 4 of compose-input.md.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mouseClickReportedToHostDropsSelection() {
        val sent = StringBuilder()
        val session = TerminalSession({ sent.append(String(it, Charsets.UTF_8)) })
        val display = TerminalDisplay(session)
        composeRule.setContent {
            Terminal(display = display, modifier = Modifier.size(300.dp, 200.dp))
        }
        composeRule.waitForIdle()
        session.feed("\u001b[?1000hhello".toByteArray())
        composeRule.waitForIdle()

        composeRule.runOnIdle { display.selectAll() }
        assertNotNull(display.selection)

        composeRule.onRoot().performMouseInput { click(Offset(5f, 5f)) }
        composeRule.waitForIdle()

        assertTrue(sent.isNotEmpty())
        assertNull(display.selection)
    }
}
