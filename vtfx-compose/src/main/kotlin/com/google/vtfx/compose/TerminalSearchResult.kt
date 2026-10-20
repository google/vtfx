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

import androidx.compose.runtime.Immutable

// Not a data class, so that properties can be added without changing what apps compiled earlier
// call (docs/decisions/0037). Only vtfx makes it.
/**
 * What a search of a [Terminal] found ([TerminalState.search]): how many matches, and which of them
 * the terminal moved to. Immutable, and equal to a result with the same values.
 */
@Immutable
public class TerminalSearchResult
internal constructor(
    /** The number of matches kept: the newest ones, at most 1,000. */
    public val matchCount: Int,
    /**
     * Whether there are more matches than [matchCount], which then counts only the newest, so that
     * text full of matches takes bounded memory. Show the count as a lower bound.
     */
    public val isMatchCountCapped: Boolean,
    /**
     * The position of the current match among the [matchCount] matches, from 0 for the oldest (the
     * top), or null if there are no matches.
     */
    public val currentMatchIndex: Int?,
) {
    override fun equals(other: Any?): Boolean =
        other is TerminalSearchResult &&
            other.matchCount == matchCount &&
            other.isMatchCountCapped == isMatchCountCapped &&
            other.currentMatchIndex == currentMatchIndex

    override fun hashCode(): Int =
        (matchCount * 31 + isMatchCountCapped.hashCode()) * 31 + (currentMatchIndex ?: -1)

    override fun toString(): String =
        "TerminalSearchResult(matchCount=$matchCount, isMatchCountCapped=$isMatchCountCapped, " +
            "currentMatchIndex=$currentMatchIndex)"
}
