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
 * titles, at most [maxLength] UTF-16 code units in all, dropping the oldest to make room. The total
 * bounds the memory to one string of the longest OSC, which a title alone can already use; the
 * depth alone would let the host keep [maxDepth] of them (docs/decisions/0002).
 */
internal class TitleStack(private val maxDepth: Int, private val maxLength: Int) {
    private val titles = ArrayDeque<String>()

    /** The sum of the lengths of [titles]. */
    private var length = 0

    init {
        require(maxDepth > 0) { "maxDepth must be positive: $maxDepth" }
    }

    /** Saves [title], which must be at most [maxLength] long. */
    fun push(title: String) {
        require(title.length <= maxLength) { "title longer than $maxLength: ${title.length}" }
        while (titles.size >= maxDepth || length + title.length > maxLength) {
            length -= titles.removeFirst().length
        }
        titles.addLast(title)
        length += title.length
    }

    /** Removes and returns the title saved last, or null if none is saved. */
    fun pop(): String? = titles.removeLastOrNull()?.also { length -= it.length }
}
