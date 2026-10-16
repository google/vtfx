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
 * Every reply the gate can make with the default policy and without [ReportedColors], with numbers
 * only where the terminal state supplies them. Host text is never part of a reply.
 */
internal object KnownReplies {
    val pattern: Regex =
        Regex(
            listOf(
                    """\u001b\[\?62;22c""",
                    """\u001b\[>0;10;1c""",
                    """\u001b\[0n""",
                    """\u001b\[\??\d{1,4};\d{1,4}R""",
                    """\u001b\[\??\d{1,5};[0-4]\${'$'}y""",
                    """\u001bP1\${'$'}r[0-9;:]*m\u001b\\""",
                    """\u001bP1\${'$'}r\d{1,4};\d{1,4}r\u001b\\""",
                    """\u001bP1\${'$'}r[1-6] q\u001b\\""",
                    """\u001bP0\${'$'}r\u001b\\""",
                    """\u001bP>\|vtfx\(0\.1\)\u001b\\""",
                    """\u001b\[8;\d{1,4};\d{1,4}t""",
                    // Pixel sizes, only once a display gave the size of a cell.
                    """\u001b\[[46];\d{1,7};\d{1,7}t""",
                    // Kitty graphics: the numbers of the command and a fixed status.
                    """\u001b_G(?:i=\d{1,10})?(?:,?I=\d{1,10})?(?:,?p=\d{1,10})?;""" +
                        "(?:OK|EINVAL:invalid command|ENOENT:no such image|" +
                        "ENODATA:insufficient image data|EBADPNG:malformed PNG data|" +
                        "EFBIG:image too large|EBADF:only direct transmission is supported|" +
                        "EINVAL:cannot inflate the data|EINVAL:animation is not supported)" +
                        """\u001b\\""",
                )
                .joinToString("|")
        )
}
