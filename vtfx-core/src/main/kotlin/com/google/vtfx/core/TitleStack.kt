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
 * The window titles saved by XTWINOPS 22, for XTWINOPS 23 to restore. Holds at most [maxDepth]
 * titles, dropping the oldest to make room. A title is already cut to [Emulator.MAX_TITLE_LENGTH]
 * when it is set, so the depth bounds the memory (docs/decisions/0081).
 */
internal class TitleStack(private val maxDepth: Int) {
    private val titles = ArrayDeque<String>()

    init {
        require(maxDepth > 0) { "maxDepth must be positive: $maxDepth" }
    }

    /** Saves [title]. */
    fun push(title: String) {
        if (titles.size >= maxDepth) titles.removeFirst()
        titles.addLast(title)
    }

    /** Removes and returns the title saved last, or null if none is saved. */
    fun pop(): String? = titles.removeLastOrNull()
}
