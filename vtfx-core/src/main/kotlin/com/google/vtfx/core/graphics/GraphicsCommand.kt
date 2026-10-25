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

package com.google.vtfx.core.graphics

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.ProtocolLimits

/**
 * The control data of a kitty graphics command (`ESC _ G <control> ; <payload> ESC \`), and where
 * its payload starts in the string (docs/decisions/0080).
 *
 * Keys that are absent keep the protocol's defaults. Values that the protocol defines as unsigned
 * 32-bit integers are kept as [Long]s from 0 to 4294967295; the signed one ([z]) as an [Int]. Keys
 * that vtfx parses and ignores (`O`, `P`, `Q`, `H`, `V`, `N`) are checked like the others but not
 * kept.
 */
internal class GraphicsCommand {
    /** The action (`a`), or 0 if absent, which means transmit. */
    var action: Char = NONE

    /** Whether to suppress replies (`q`): 1 for OK replies, 2 for all. */
    var quiet: Long = 0

    /** The format (`f`): 24, 32, or 100. */
    var format: Long = 32

    /** The transmission medium (`t`). */
    var medium: Char = 'd'

    /** The compression (`o`), or 0 for none. */
    var compression: Char = NONE

    /** The width and height of raw pixel data (`s`, `v`). */
    var width: Long = 0
    var height: Long = 0

    /** The size of the data (`S`). */
    var size: Long = 0

    /** The image id (`i`), number (`I`), and placement id (`p`). */
    var imageId: Long = 0
    var imageNumber: Long = 0
    var placementId: Long = 0

    /** Whether more chunks follow (`m`). */
    var more: Long = 0

    /** What to delete (`d`). */
    var delete: Char = 'a'

    /** The source rectangle in pixels (`x`, `y`, `w`, `h`); also cells for deletions. */
    var x: Long = 0
    var y: Long = 0
    var w: Long = 0
    var h: Long = 0

    /** The offset within the first cell, in pixels (`X`, `Y`). */
    var cellX: Long = 0
    var cellY: Long = 0

    /** The columns and rows to display over (`c`, `r`). */
    var columns: Long = 0
    var rows: Long = 0

    /** The cursor movement policy (`C`): 1 to not move the cursor. */
    var cursorPolicy: Long = 0

    /** Whether to make a virtual placement for Unicode placeholders (`U`). */
    var virtual: Long = 0

    /** The z-index (`z`). */
    var z: Int = 0

    /** Where the payload starts in the string, or its length if there is none. */
    var payloadStart: Int = 0

    companion object {
        const val NONE: Char = '\u0000'

        /**
         * Parses the graphics command in [data], which starts with `G`. Returns null if kitty would
         * reject it: an unknown key, a malformed or out of range number, or a flag value that the
         * key does not take. Kitty ignores such commands without a reply.
         */
        fun parse(data: CharSequence): GraphicsCommand? {
            val command = GraphicsCommand()
            var i = 1
            val end = data.length
            while (i < end && data[i] != ';') {
                val key = data[i]
                if (i + 1 >= end || data[i + 1] != '=') return null
                var j = i + 2
                while (j < end && data[j] != ',' && data[j] != ';') j++
                if (!command.set(key, data, i + 2, j)) return null
                i = if (j < end && data[j] == ',') j + 1 else j
            }
            command.payloadStart = if (i < end) i + 1 else end
            return command
        }
    }

    /** Sets [key] from the value in [from, to) of [data]. Returns false if it is invalid. */
    private fun set(key: Char, data: CharSequence, from: Int, to: Int): Boolean {
        when (key) {
            'a' -> action = flag(data, from, to, "tTqpdfac") ?: return false
            't' -> medium = flag(data, from, to, "dfts") ?: return false
            'o' -> compression = flag(data, from, to, "z") ?: return false
            'd' -> delete = flag(data, from, to, "aAiIcCfFnNpPqQrRxXyYzZ") ?: return false
            'z' -> z = signed(data, from, to) ?: return false
            // The offsets of relative placements, parsed and ignored.
            'H',
            'V' -> if (signed(data, from, to) == null) return false
            else -> {
                val value = unsigned(data, from, to) ?: return false
                when (key) {
                    'q' -> quiet = value
                    'f' -> format = value
                    's' -> width = value
                    'v' -> height = value
                    'S' -> size = value
                    'i' -> imageId = value
                    'I' -> imageNumber = value
                    'p' -> placementId = value
                    'm' -> more = value
                    'x' -> x = value
                    'y' -> y = value
                    'w' -> w = value
                    'h' -> h = value
                    'X' -> cellX = value
                    'Y' -> cellY = value
                    'c' -> columns = value
                    'r' -> rows = value
                    'C' -> cursorPolicy = value
                    'U' -> virtual = value
                    // The offset in a file, relative placements, and usage hints, parsed and
                    // ignored.
                    'O',
                    'P',
                    'Q',
                    'N' -> {}
                    else -> return false
                }
            }
        }
        return true
    }

    private fun flag(data: CharSequence, from: Int, to: Int, allowed: String): Char? {
        if (to - from != 1) return null
        return data[from].takeIf { it in allowed }
    }

    private fun unsigned(data: CharSequence, from: Int, to: Int): Long? {
        if (from == to || to - from > 10) return null
        var value = 0L
        for (k in from until to) {
            val digit = data[k] - '0'
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return value.takeIf { it <= ProtocolLimits.MAX_GRAPHICS_NUMBER }
    }

    private fun signed(data: CharSequence, from: Int, to: Int): Int? {
        val negative = from < to && data[from] == '-'
        val magnitude = unsigned(data, if (negative) from + 1 else from, to) ?: return null
        val value = if (negative) -magnitude else magnitude
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }
}
