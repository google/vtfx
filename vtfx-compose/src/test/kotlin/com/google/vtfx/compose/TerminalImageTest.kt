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

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.vtfx.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks the images of the kitty graphics protocol that a `Terminal` draws (docs/decisions/0038).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK])
class TerminalImageTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * A session showing, at the top left, an image of one pixel of [rgba] (base64), 4 by 2 cells.
     */
    private fun sessionWithImage(rgba: String): TerminalSession {
        val session = TerminalSession({})
        session.feed("\u001b_Ga=T,f=32,s=1,v=1,c=4,r=2,q=2;$rgba\u001b\\".toByteArray())
        return session
    }

    @Test
    fun switchingTheSession_drawsTheImageOfTheNewSession() {
        // Each session numbers its images from 1, so both images have the same key.
        val redSession = sessionWithImage("/wAA/w==")
        val greenSession = sessionWithImage("AP8A/w==")
        var shown by mutableStateOf(redSession)
        composeRule.setContent { Terminal(shown, Modifier.size(300.dp, 200.dp)) }
        composeRule.waitForIdle()
        // Draws the red session once, so that the terminal makes a bitmap of its image.
        composeRule.onRoot().captureToImage()

        composeRule.runOnIdle { shown = greenSession }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(0xFF00FF00.toInt(), bitmap.getPixel(5, 5))
    }
}
