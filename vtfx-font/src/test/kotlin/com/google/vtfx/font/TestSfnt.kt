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

import java.io.ByteArrayOutputStream

/** Big-endian writes for building fonts in tests. Each write takes the values in order. */
class TestBytes {
    private val out = ByteArrayOutputStream()

    val size: Int
        get() = out.size()

    fun u8(vararg values: Int) {
        for (value in values) out.write(value)
    }

    fun u16(vararg values: Int) {
        for (value in values) {
            out.write(value ushr 8)
            out.write(value)
        }
    }

    fun u24(vararg values: Int) {
        for (value in values) {
            out.write(value ushr 16)
            u16(value)
        }
    }

    fun u32(vararg values: Int) {
        for (value in values) {
            u16(value ushr 16)
            u16(value)
        }
    }

    fun bytes(value: ByteArray) {
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

fun bytes(build: TestBytes.() -> Unit): ByteArray = TestBytes().apply(build).toByteArray()

fun tag(name: String): Int = name.fold(0) { acc, c -> (acc shl 8) or c.code }

/** A record of a `name` table, with its string already encoded. */
class TestName(
    val platform: Int,
    val encoding: Int,
    val language: Int,
    val nameId: Int,
    val string: ByteArray,
)

fun windowsName(nameId: Int, text: String, language: Int = 0x0409) =
    TestName(3, 1, language, nameId, text.toByteArray(Charsets.UTF_16BE))

/** Builds the containers of fonts from their tables. */
object TestSfnt {
    /** A single font with [tables], in the order of their tags. */
    fun font(tables: Map<String, ByteArray>, version: Int = Tag.TRUE_TYPE): ByteArray =
        collectionOrFont(listOf(tables), listOf(version), collection = false)

    /** A collection of fonts, each with tables of its own. */
    fun collection(faces: List<Map<String, ByteArray>>, versions: List<Int>? = null): ByteArray =
        collectionOrFont(faces, versions ?: faces.map { Tag.TRUE_TYPE }, collection = true)

    private fun collectionOrFont(
        faces: List<Map<String, ByteArray>>,
        versions: List<Int>,
        collection: Boolean,
    ): ByteArray {
        val headerSize = if (collection) 12 + 4 * faces.size else 0
        val directorySizes = faces.map { 12 + 16 * it.size }
        var offset = headerSize + directorySizes.sum()
        // The data of every face's tables follows all the directories.
        val tableOffsets = faces.map { tables ->
            tables.keys.sorted().associateWith { name ->
                offset.also { offset += align4(tables.getValue(name).size) }
            }
        }
        val out = TestBytes()
        if (collection) {
            out.u32(Tag.COLLECTION)
            out.u16(1, 0)
            out.u32(faces.size)
            var directory = headerSize
            for (size in directorySizes) {
                out.u32(directory)
                directory += size
            }
        }
        for ((index, tables) in faces.withIndex()) {
            val numTables = tables.size
            var searchRange = 1
            var entrySelector = 0
            while (searchRange * 2 <= numTables) {
                searchRange *= 2
                entrySelector++
            }
            out.u32(versions[index])
            out.u16(numTables, searchRange * 16, entrySelector, numTables * 16 - searchRange * 16)
            for (name in tables.keys.sorted()) {
                val data = tables.getValue(name)
                out.u32(
                    tag(name),
                    checksum(data),
                    tableOffsets[index].getValue(name),
                    data.size,
                )
            }
        }
        for (tables in faces) {
            for (name in tables.keys.sorted()) {
                val data = tables.getValue(name)
                out.bytes(data)
                out.bytes(ByteArray(align4(data.size) - data.size))
            }
        }
        return out.toByteArray()
    }

    fun nameTable(records: List<TestName>): ByteArray {
        val storageOffset = 6 + 12 * records.size
        val out = TestBytes()
        out.u16(0, records.size, storageOffset)
        var stringOffset = 0
        for (record in records) {
            out.u16(
                record.platform,
                record.encoding,
                record.language,
                record.nameId,
                record.string.size,
                stringOffset,
            )
            stringOffset += record.string.size
        }
        for (record in records) out.bytes(record.string)
        return out.toByteArray()
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (i in 0 until align4(data.size) step 4) {
            var word = 0
            for (j in 0 until 4) {
                word = (word shl 8) or (data.getOrElse(i + j) { 0 }.toInt() and 0xFF)
            }
            sum += word
        }
        return sum
    }

    private fun align4(size: Int) = (size + 3) and 3.inv()
}
