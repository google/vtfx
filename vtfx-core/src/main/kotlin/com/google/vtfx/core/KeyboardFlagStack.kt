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
 * The stack of the flags of the kitty keyboard protocol of one screen (docs/decisions/0083).
 * Immutable. Holds at most [MAX_DEPTH] entries, dropping the oldest to make room, and only the
 * flags that vtfx supports ([SUPPORTED_FLAGS]), so that the reply to a query reports what vtfx
 * does.
 */
internal class KeyboardFlagStack private constructor(private val entries: List<Int>) {
    /** The flags in effect: those of the newest entry, or none if the stack is empty. */
    val flags: Int
        get() = entries.lastOrNull() ?: 0

    /**
     * `CSI = flags ; mode u`: replaces the flags of the newest entry with [flags] (mode 1), adds
     * them (mode 2), or removes them (mode 3). An empty stack gets an entry. Other modes change
     * nothing.
     */
    fun set(flags: Int, mode: Int): KeyboardFlagStack {
        val current = this.flags
        val changed =
            when (mode) {
                1 -> flags
                2 -> current or flags
                3 -> current and flags.inv()
                else -> return this
            }
        return KeyboardFlagStack(entries.dropLast(1) + (changed and SUPPORTED_FLAGS))
    }

    /** `CSI > flags u`: pushes [flags], dropping the oldest entry if the stack is full. */
    fun push(flags: Int): KeyboardFlagStack =
        KeyboardFlagStack(entries.takeLast(MAX_DEPTH - 1) + (flags and SUPPORTED_FLAGS))

    /** `CSI < count u`: removes the newest [count] entries, or all of them if there are fewer. */
    fun pop(count: Int): KeyboardFlagStack = KeyboardFlagStack(entries.dropLast(count))

    companion object {
        /** The depth of a stack, as kitty's. */
        const val MAX_DEPTH = 8

        /** Flag 1: disambiguate escape codes. */
        const val DISAMBIGUATE_ESCAPE_CODES = 1

        /** Flag 4: report alternate keys. */
        const val REPORT_ALTERNATE_KEYS = 4

        /** The flags that vtfx supports. The others are dropped when they are set. */
        const val SUPPORTED_FLAGS = DISAMBIGUATE_ESCAPE_CODES or REPORT_ALTERNATE_KEYS

        /** A stack without entries. */
        val Empty = KeyboardFlagStack(emptyList())
    }
}
