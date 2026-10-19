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

/** The tags of the tables and the versions of the containers that the readers look at. */
internal object Tag {
    const val TRUE_TYPE = 0x00010000
    const val APPLE_TRUE_TYPE = 0x74727565 // 'true'
    const val OPEN_TYPE_CFF = 0x4F54544F // 'OTTO'
    const val COLLECTION = 0x74746366 // 'ttcf'
    const val WOFF = 0x774F4646 // 'wOFF'
    const val WOFF2 = 0x774F4632 // 'wOF2'

    const val CFF = 0x43464620 // 'CFF '
    const val CFF2 = 0x43464632 // 'CFF2'
    const val OS2 = 0x4F532F32 // 'OS/2'
    const val CMAP = 0x636D6170 // 'cmap'
    const val GLYF = 0x676C7966 // 'glyf'
    const val HEAD = 0x68656164 // 'head'
    const val HHEA = 0x68686561 // 'hhea'
    const val HMTX = 0x686D7478 // 'hmtx'
    const val LOCA = 0x6C6F6361 // 'loca'
    const val MAXP = 0x6D617870 // 'maxp'
    const val NAME = 0x6E616D65 // 'name'
    const val POST = 0x706F7374 // 'post'

    fun toString(tag: Int): String =
        String(CharArray(4) { (tag ushr (24 - it * 8) and 0xFF).toChar() })
}
