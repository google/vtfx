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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.vtfx.session.TerminalSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the output of real apps, recorded by tools/recordings, against provisional goldens
 * (docs/design/testing.md section 10). vtfx-core's RecordingReplayTest checks the text against a
 * reference terminal, and GraphicsRecordingTest where the images are; this checks how it looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SDK], qualifiers = "w720dp-h480dp-xhdpi")
class RecordingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val directory =
        File(System.getProperty("vtfx.recordingsDir") ?: error("vtfx.recordingsDir is not set"))

    private fun replay(name: String) {
        val session = TerminalSession({}, columns = COLUMNS, rows = ROWS)
        composeRule.setContent {
            // Exactly the recorded size, so that the session is not resized.
            val metrics =
                CellMetrics.measure(
                    TerminalStyle().let {
                        TextPaints(
                            it,
                            LocalDensity.current,
                            TextTypefaces.resolve(
                                // The default font of TerminalStyle, a system font.
                                (it.font as TerminalFont.System).toFontFamily(),
                                LocalFontFamilyResolver.current,
                            ),
                        )
                    }
                )
            val size =
                with(LocalDensity.current) {
                    Modifier.size(
                        (metrics.width * COLUMNS + 0.5f).toDp(),
                        (metrics.height * ROWS + 0.5f).toDp(),
                    )
                }
            Terminal(session, size)
        }
        composeRule.waitForIdle()
        assertEquals(COLUMNS, session.snapshot().columns)
        assertEquals(ROWS, session.snapshot().rows)
        session.feed(File(directory, "$name.out").readBytes())
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test fun vim() = replay("vim")

    @Test fun less() = replay("less")

    @Test fun htop() = replay("htop")

    @Test fun tmux() = replay("tmux")

    // Programs that show images with the kitty graphics protocol (docs/decisions/0038). They chose
    // the sizes of the images from cells of 10 by 20 pixels, and the display takes each image's
    // size in cells from its own cells, as on a device.

    @Test fun timg() = replay("graphics/timg")

    @Test fun icat() = replay("graphics/icat")

    @Test fun icatPlaceholder() = replay("graphics/icat-placeholder")

    @Test fun fastfetch() = replay("graphics/fastfetch")

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
    }
}
