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

package com.google.vtfx.core.parser

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.ProtocolLimits

/**
 * The numeric parameters of a control sequence.
 *
 * An omitted parameter is 0, which the control functions treat as their default, as ECMA-48
 * specifies. Parameters separated by ':' are sub-parameters of the preceding parameter (ITU T.416,
 * used by SGR). Values are clamped to [ProtocolLimits.MAX_PARAMETER], and parameters beyond
 * [MAX_COUNT] are dropped, so that a hostile sequence cannot overflow or grow without bound.
 */
internal class Params {
    private val values = IntArray(MAX_COUNT)
    private val subParameter = BooleanArray(MAX_COUNT)
    private var started = false
    private var index = 0

    /** The number of parameters. */
    var size: Int = 0
        private set

    /** Returns the parameter at [i], or 0 if it is absent. */
    operator fun get(i: Int): Int = if (i < size) values[i] else 0

    /** Returns the parameter at [i], or [default] if it is absent or 0. */
    fun getOrDefault(i: Int, default: Int): Int {
        val value = get(i)
        return if (value == 0) default else value
    }

    /** Returns whether the parameter at [i] was separated from the previous one by ':'. */
    fun isSubParameter(i: Int): Boolean = i < size && subParameter[i]

    internal fun clear() {
        // Each entry is reset when the sequence reaches it, so that clearing, which every escape
        // sequence does, takes constant time.
        values[0] = 0
        subParameter[0] = false
        started = false
        index = 0
        size = 0
    }

    internal fun addDigit(digit: Int) {
        started = true
        if (index >= MAX_COUNT) return
        val value = values[index] * 10 + digit
        values[index] =
            if (value > ProtocolLimits.MAX_PARAMETER) ProtocolLimits.MAX_PARAMETER else value
    }

    internal fun nextParameter(isSubParameter: Boolean) {
        started = true
        index++
        if (index < MAX_COUNT) {
            values[index] = 0
            subParameter[index] = isSubParameter
        }
    }

    /** Fixes [size] once the sequence is complete. */
    internal fun finish() {
        size = if (started) minOf(index + 1, MAX_COUNT) else 0
    }

    override fun toString(): String =
        (0 until size).joinToString("") { i ->
            (if (i == 0) "" else if (subParameter[i]) ":" else ";") + values[i]
        }

    companion object {
        const val MAX_COUNT: Int = 32
    }
}
