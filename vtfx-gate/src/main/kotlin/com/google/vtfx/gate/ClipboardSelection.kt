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

package com.google.vtfx.gate

/**
 * The selection parameter of an OSC 52 request, parsed by the gate with its own list of targets.
 *
 * The reply to a read repeats the selection. It is spelled again from the [Target] constants, so
 * the reply never carries the host's text, and its safety does not depend on what vtfx-core lets
 * through (docs/decisions/0083).
 */
internal class ClipboardSelection private constructor(private val targets: List<Target>) {
    /** The targets of xterm's selection parameter. */
    enum class Target(val code: Char) {
        Clipboard('c'),
        Primary('p'),
        Secondary('q'),
        Select('s'),
        CutBuffer0('0'),
        CutBuffer1('1'),
        CutBuffer2('2'),
        CutBuffer3('3'),
        CutBuffer4('4'),
        CutBuffer5('5'),
        CutBuffer6('6'),
        CutBuffer7('7'),
    }

    /** The selection as the reply spells it: the codes of the targets, in the order requested. */
    fun spelled(): String = targets.joinToString("") { it.code.toString() }

    companion object {
        /** The longest selection accepted. xterm's has at most 12 distinct targets. */
        const val MAX_LENGTH = 16

        /**
         * Parses [selection], or returns null if it is empty, longer than [MAX_LENGTH], or has a
         * character that is not the code of a [Target].
         */
        fun parse(selection: String): ClipboardSelection? {
            if (selection.isEmpty() || selection.length > MAX_LENGTH) return null
            val targets = selection.map { c -> Target.entries.find { it.code == c } ?: return null }
            return ClipboardSelection(targets)
        }
    }
}
