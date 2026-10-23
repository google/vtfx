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
 * Converts text for the host (docs/design/ime-contract.md section 4.2).
 *
 * Line breaks (CR LF, CR, LF) become CR and HT is kept. Every other C0 control, DEL, and C1 control
 * is dropped, so that text can never contain ESC and thus never form a control sequence, such as
 * the end of a bracketed paste. Unpaired surrogates become U+FFFD.
 *
 * `ImeEditor` in vtfx-input keeps a copy of the rule for dropping controls, so that its input
 * history matches what the host receives (ime-contract.md invariant 3); this rule stays here so
 * that the gate does not depend on code outside security review. `DroppedControlsAgreementTest` in
 * vtfx-session pins that the two agree for every UTF-16 code unit; change both together.
 */
internal object OutboundText {
    fun encode(text: String): ByteArray {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> {
                    out.append('\r')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\n' -> out.append('\r')
                c == '\t' -> out.append('\t')
                c < ' ' || c in '\u007f'..'\u009f' -> {}
                Character.isHighSurrogate(c) &&
                    i + 1 < text.length &&
                    Character.isLowSurrogate(text[i + 1]) -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> out.append(REPLACEMENT)
                else -> out.append(c)
            }
            i++
        }
        // Every unpaired surrogate was replaced, so the text is well-formed UTF-16, and encoding
        // it whole gives the same bytes as encoding each character on its own.
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private const val REPLACEMENT = '\uFFFD'
}
