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

package com.google.vtfx.core

/**
 * Bounds on the numbers that the terminal takes from host output and from the display, shared by
 * the modules that produce the numbers (vtfx-core) and those that check them again before they
 * reach the host (vtfx-gate) or the terminal (vtfx-session).
 *
 * The gate checks the numbers of a reply with `require` at the end of each write of host output, so
 * a bound that the terminal relaxed on its own would let host output make the write throw. One
 * definition keeps the two sides equal.
 *
 * Plain vals rather than consts, so that each module reads the values of the vtfx-core it runs
 * with, not those it was compiled with (docs/decisions/0037).
 *
 * @suppress
 */
@InternalVtfxApi
public object ProtocolLimits {
    /**
     * The largest numeric parameter of a control sequence. Larger values are clamped to it, so that
     * a hostile sequence cannot overflow.
     */
    public val MAX_PARAMETER: Int = 65535

    /**
     * The largest width or height of a cell in pixels, so that the text area in pixels fits in an
     * Int at [TerminalLimits.MAX_DIMENSION]. `Terminal.setCellSize` and `TerminalSession.resize`
     * state this value in their KDoc, which cannot link to internal API.
     */
    public val MAX_CELL_SIZE: Int = 1000

    /**
     * The largest unsigned number of a kitty graphics command, 2^32 - 1, as the protocol defines
     * them (docs/decisions/0038). It also bounds the ids and numbers of images and placements that
     * graphics replies carry.
     */
    public val MAX_GRAPHICS_NUMBER: Long = 0xFFFF_FFFFL
}
