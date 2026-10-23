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

/**
 * The steps of some work that all the glyphs of one face may still take together, such as the
 * charstring tokens that they run. Limits on one glyph alone do not bound the time of a face, since
 * every glyph can reach the most that its limits allow through data that the glyphs share, such as
 * subroutines and component glyphs (fonts.md section 9).
 *
 * Not thread-safe: one face is converted on one thread.
 */
internal class WorkBudget(private val limit: Long, private val what: String) {
    private var left = limit

    /** Takes one step, failing as too complex when the face has taken all of them. */
    fun spend() {
        spend(1)
    }

    /** Takes [steps] steps, failing as too complex when the face has taken all of them. */
    fun spend(steps: Int) {
        left -= steps
        if (left < 0) tooComplex("face of more than $limit $what")
    }
}
