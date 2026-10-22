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

/** Reads the names of a face from its `name` table, for [FaceInfo]. */
internal object Names {
    private const val FAMILY = 1
    private const val SUBFAMILY = 2
    private const val TYPOGRAPHIC_FAMILY = 16
    private const val TYPOGRAPHIC_SUBFAMILY = 17

    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_WINDOWS = 3
    private const val ENGLISH_US = 0x0409

    /** The longest name returned, in code points. */
    private const val MAX_LENGTH = 64

    class FaceNames(val family: String?, val style: String?)

    fun read(name: Reader?): FaceNames {
        if (name == null) return FaceNames(null, null)
        val count = name.u16(2)
        val storage = name.from(name.u16(4))
        val records = name.sub(6, count * 12)
        return FaceNames(
            find(records, count, storage, TYPOGRAPHIC_FAMILY)
                ?: find(records, count, storage, FAMILY),
            find(records, count, storage, TYPOGRAPHIC_SUBFAMILY)
                ?: find(records, count, storage, SUBFAMILY),
        )
    }

    /**
     * The name [nameId] in UTF-16: for Windows in US English if there is one, then for Windows in
     * any language, then for Unicode. Names only for the Macintosh platform, in its legacy
     * encodings, are not read; the face then has no name, and apps show it by its index.
     */
    private fun find(records: Reader, count: Int, storage: Reader, nameId: Int): String? {
        var best: Reader? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val record = i * 12
            if (records.u16(record + 6) != nameId) continue
            val rank = rank(records.u16(record), records.u16(record + 2), records.u16(record + 4))
            if (rank < bestRank) {
                best = storage.sub(records.u16(record + 10), records.u16(record + 8))
                bestRank = rank
            }
        }
        return best?.let { clean(decodeUtf16(it)) }
    }

    /** The preference of a record by its platform, encoding, and language; lower is better. */
    private fun rank(platform: Int, encoding: Int, language: Int): Int =
        when {
            platform == PLATFORM_WINDOWS && encoding in WINDOWS_UTF16 ->
                if (language == ENGLISH_US) 0 else 1
            platform == PLATFORM_UNICODE && encoding in UNICODE_UTF16 -> 2
            else -> Int.MAX_VALUE
        }

    /** The Windows encodings in UTF-16: symbol, BMP, and full repertoire. */
    private val WINDOWS_UTF16 = setOf(0, 1, 10)

    /** The Unicode encodings in UTF-16; 5 is variation sequences, which has no strings. */
    private val UNICODE_UTF16 = setOf(0, 1, 2, 3, 4, 6)

    private fun decodeUtf16(bytes: Reader): String {
        if (bytes.length % 2 != 0) malformed("name of odd length")
        val chars = CharArray(bytes.length / 2) { bytes.u16(it * 2).toChar() }
        return String(chars)
    }

    /**
     * Removes control characters, format characters (among them those that change the direction of
     * text, zero width ones, and tags), and line and paragraph separators, which could make the
     * name show as something else, on more than one line, or as nothing; replaces unpaired
     * surrogates; and cuts the name to [MAX_LENGTH] code points. Returns null for a name left
     * blank.
     */
    private fun clean(name: String): String? {
        val out = StringBuilder()
        var codePoints = 0
        var i = 0
        while (i < name.length && codePoints < MAX_LENGTH) {
            val c = name[i]
            val next = name.getOrNull(i + 1)
            val codePoint =
                if (c.isHighSurrogate() && next != null && next.isLowSurrogate()) {
                    Character.toCodePoint(c, next)
                } else {
                    c.code
                }
            i += Character.charCount(codePoint)
            when {
                Character.isSurrogate(c) && codePoint == c.code -> out.append(REPLACEMENT)
                Character.getType(codePoint) in REMOVED_TYPES -> continue
                else -> out.appendCodePoint(codePoint)
            }
            codePoints++
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    private const val REPLACEMENT = '\uFFFD'

    /** The general categories removed: Cc, Cf, Zl, and Zp. */
    private val REMOVED_TYPES =
        setOf(
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
        )
}
