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

/** The tables of one face of a font file, each checked to lie within the file. */
internal class Face(private val tables: Map<Int, Reader>) {
    fun has(tag: Int): Boolean = tag in tables

    fun table(tag: Int): Reader? = tables[tag]

    fun require(tag: Int): Reader = tables[tag] ?: malformed("no ${Tag.toString(tag)} table")

    /** The format of the outlines, from the tables that the face has. */
    val outlineFormat: OutlineFormat
        get() {
            val glyf = has(Tag.GLYF) && has(Tag.LOCA)
            val cff = has(Tag.CFF)
            val cff2 = has(Tag.CFF2)
            return when {
                glyf && !cff && !cff2 -> OutlineFormat.TrueType
                cff && !glyf && !cff2 -> OutlineFormat.Cff
                cff2 && !glyf && !cff -> OutlineFormat.Cff2
                else -> OutlineFormat.None
            }
        }
}

/**
 * Reads the containers of fonts: a single sfnt (TrueType or OpenType), or a collection of them (TTC
 * or OTC), whose faces may share tables.
 */
internal object Sfnt {
    /** The most faces of a collection that are read. Real collections have a few dozen at most. */
    private const val MAX_FACES = 256

    /** The offsets of the faces' table directories in [data]. */
    fun faceOffsets(data: ByteArray): IntArray {
        val reader = Reader(data)
        if (data.size < 4) malformed("too short for a font")
        return when (val version = reader.s32(0)) {
            Tag.COLLECTION -> {
                val major = reader.u16(4)
                if (major != 1 && major != 2) unsupported("collection version $major")
                val count = reader.u32(8)
                if (count == 0L) malformed("collection without faces")
                if (count > MAX_FACES) unsupported("collection of $count faces")
                IntArray(count.toInt()) { reader.index32(12 + it * 4) }
            }
            else -> {
                checkVersion(version)
                intArrayOf(0)
            }
        }
    }

    /** The face whose table directory starts at [offset] in [data]. */
    fun face(data: ByteArray, offset: Int): Face {
        val reader = Reader(data).from(offset)
        checkVersion(reader.s32(0))
        val numTables = reader.u16(4)
        val tables = HashMap<Int, Reader>(numTables * 2)
        val file = Reader(data)
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            val tag = reader.s32(record)
            val tableOffset = reader.index32(record + 8)
            val tableLength = reader.index32(record + 12)
            val table = file.sub(tableOffset, tableLength)
            if (tables.put(tag, table) != null) malformed("two ${Tag.toString(tag)} tables")
        }
        return Face(tables)
    }

    private fun checkVersion(version: Int) {
        when (version) {
            Tag.TRUE_TYPE,
            Tag.APPLE_TRUE_TYPE,
            Tag.OPEN_TYPE_CFF -> Unit
            Tag.WOFF,
            Tag.WOFF2 -> unsupported("WOFF")
            Tag.COLLECTION -> malformed("collection inside a collection")
            else -> unsupported("not a TrueType or OpenType font")
        }
    }
}
