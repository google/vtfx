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

package com.google.vtfx.gate

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import com.google.vtfx.core.TerminalLimits
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the attack classes of requirement 6.1 that vtfx-gate is responsible for. Each nested
 * class is one attack class. Host output goes through a real [Terminal], as it would in the app.
 */
class AttackTest {
    private class FakeClipboard(var text: String? = null) : ClipboardAccess {
        val writes = mutableListOf<String>()
        var reads = 0

        override fun write(text: String) {
            writes += text
            this.text = text
        }

        override fun read(): String? {
            reads++
            return text
        }
    }

    private val sent = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val clipboard = FakeClipboard("secret from another app")

    private fun gate(policy: GatePolicy = GatePolicy()) =
        TerminalGate(
            HostWriter { sent += String(it, Charsets.UTF_8) },
            policy,
            clipboard,
            urlOpener = {
                opened += it
                OpenUrlResult.Opened
            },
        )

    private fun host(
        gate: TerminalGate,
        output: String,
        limits: TerminalLimits = TerminalLimits(),
    ) {
        val terminal = Terminal(80, 24, limits) { gate.handle(it) }
        terminal.write(output.toByteArray(Charsets.UTF_8))
    }

    private fun base64(text: String) =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** Command injection to the host through terminal replies. */
    @Nested
    inner class ReplyInjection {
        // ESC would end the string it is in, so a payload inside a string has none.
        private val payload = "[c;rm -rf ~\u009b6n"

        @Test
        fun repliesContainNoHostText() {
            val gate = gate()
            host(
                gate,
                "\u001b]2;$payload\u0007\u001b]0;$payload\u001b\\\u001bP\$q$payload\u001b\\\u001b[c\u001b[>c\u001b[5n\u001b[6n",
            )
            // DECRQSS of an unknown setting is answered as invalid, without the request.
            assertEquals(
                listOf(
                    "\u001bP0\$r\u001b\\",
                    "\u001b[?62;22c",
                    "\u001b[>0;10;1c",
                    "\u001b[0n",
                    "\u001b[1;1R",
                ),
                sent,
            )
        }

        @Test
        fun statusStringRequestsAreNotEchoed() {
            val gate = gate()
            host(gate, "\u001bP\$q$payload\u001b\\\u001bP\$qm;$payload\u001b\\")
            assertEquals(listOf("\u001bP0\$r\u001b\\", "\u001bP0\$r\u001b\\"), sent)
        }

        @Test
        fun graphicsRepliesCarryOnlyNumbersAndFixedStatuses() {
            // Kitty's own messages repeat file names and values; vtfx's never do.
            val gate = gate()
            host(
                gate,
                "\u001b_Ga=q,i=1,t=f;${base64(payload)}\u001b\\" +
                    "\u001b_Ga=q,i=2,f=100;${base64(payload)}\u001b\\" +
                    "\u001b_Ga=p,i=3,p=9\u001b\\" +
                    // Not graphics: ignored.
                    "\u001b_${payload}\u001b\\",
            )
            assertEquals(
                listOf(
                    "\u001b_Gi=1;EBADF:only direct transmission is supported\u001b\\",
                    "\u001b_Gi=2;EBADPNG:malformed PNG data\u001b\\",
                    "\u001b_Gi=3,p=9;ENOENT:no such image\u001b\\",
                ),
                sent,
            )
        }

        @Test
        fun titleAndContentReportsAreNotAnswered() {
            val gate = gate()
            // XTWINOPS title reports, DECRQCRA (screen checksum), and XTGETTCAP.
            host(
                gate,
                "\u001b]2;$payload\u0007\u001b[21t\u001b[20t\u001b[22;0t" +
                    "\u001b[1;1;1;1;24;80*y\u001bP+q544e\u001b\\",
            )
            assertEquals(emptyList(), sent)
        }

        @Test
        fun randomHostOutputOnlyProducesKnownReplies() {
            val tokens =
                listOf(
                    "\u001b[",
                    "\u001b]",
                    "\u001bP",
                    "\u001b\\",
                    "\u0007",
                    ";",
                    "?",
                    ">",
                    "$",
                    "c",
                    "n",
                    "6",
                    "5",
                    "R",
                    "t",
                    "21",
                    "52",
                    "q",
                    "rm -rf ~",
                    "\r",
                    "\n",
                    "\u001b",
                    "あ",
                    "\u009b",
                    "18",
                    "2004",
                    " ",
                    "m",
                    "r",
                    "p",
                    "10",
                    // Kitty graphics commands.
                    "\u001b_G",
                    "a=q",
                    "a=p",
                    "a=T",
                    ",i=",
                    ",I=",
                    ",p=",
                    ",f=24,s=1,v=1",
                    ",m=1",
                    "AAAA",
                    ",t=f",
                    ",q=1",
                )
            val random = Random(1)
            repeat(2000) {
                val output = buildString {
                    repeat(random.nextInt(1, 40)) { append(tokens.random(random)) }
                }
                host(gate(), output)
            }
            val unexpected = sent.filterNot { KnownReplies.pattern.matches(it) }
            assertEquals(emptyList(), unexpected)
        }
    }

    /** Unauthorized clipboard reads and writes through OSC 52. */
    @Nested
    inner class Clipboard {
        @Test
        fun readIsDeniedByDefault() {
            host(gate(), "\u001b]52;c;?\u0007")
            assertEquals(emptyList(), sent)
            assertEquals(0, clipboard.reads)
        }

        @Test
        fun readWhenAllowedRepliesInBase64() {
            clipboard.text = "x\u001b]2;y\u0007"
            host(gate(GatePolicy(allowClipboardRead = true)), "\u001b]52;c;?\u0007")
            assertEquals(listOf("\u001b]52;c;${base64("x\u001b]2;y\u0007")}\u001b\\"), sent)
        }

        @Test
        fun writeIsAllowedUpToTheLimit() {
            val gate = gate(GatePolicy(maxClipboardWriteLength = 5))
            host(gate, "\u001b]52;c;${base64("12345")}\u0007\u001b]52;c;${base64("123456")}\u0007")
            assertEquals(listOf("12345"), clipboard.writes)
        }

        @Test
        fun writeCanBeDisabled() {
            host(gate(GatePolicy(allowClipboardWrite = false)), "\u001b]52;c;${base64("x")}\u0007")
            assertEquals(emptyList(), clipboard.writes)
        }

        @Test
        fun writeLongerThanTheStringLimitIsDiscardedByTheModel() {
            val limits = TerminalLimits(maxStringLength = 100)
            host(gate(), "\u001b]52;c;${base64("x".repeat(200))}\u0007", limits)
            assertEquals(emptyList(), clipboard.writes)
        }

        @Test
        fun requestsAreRefusedWithoutAClipboard() {
            val gate =
                TerminalGate(
                    HostWriter { sent += String(it) },
                    GatePolicy(allowClipboardRead = true),
                )
            host(gate, "\u001b]52;c;?\u0007\u001b]52;c;${base64("x")}\u0007")
            assertEquals(emptyList(), sent)
        }
    }

    /** Spoofed hyperlinks and unintended opening of URLs. */
    @Nested
    inner class Hyperlinks {
        @Test
        fun hostOutputNeverOpensUrls() {
            host(
                gate(),
                "\u001b]8;;https://example.com\u001b\\link\u001b]8;;\u001b\\\u001b]1337;OpenURL=https://x\u0007",
            )
            assertEquals(emptyList(), opened)
        }

        @Test
        fun onlyAllowedSchemesOpen() {
            val gate = gate()
            assertEquals(OpenUrlResult.Opened, gate.openUrl("https://example.com/a?b#c"))
            assertEquals(OpenUrlResult.Opened, gate.openUrl("HTTP://example.com"))
            for (url in
                listOf(
                    "javascript:alert(1)",
                    "file:///data/data/x",
                    "intent://x#launch;end",
                    "content://x",
                    "mailto:a@b",
                )) {
                assertEquals(OpenUrlResult.NotAllowed, gate.openUrl(url), url)
            }
            assertEquals(listOf("https://example.com/a?b#c", "HTTP://example.com"), opened)
        }

        @Test
        fun urlsThatCouldHideTheirTargetAreRejected() {
            val gate = gate()
            for (url in
                listOf(
                    "https://a.com\u0000@b.com",
                    "https://a.com‮/b",
                    "https://a.com b",
                    "https://a.com\nb",
                    "",
                    ":x",
                    "https",
                    "ht tp://a",
                )) {
                assertFalse(gate.isAllowedUrl(url), url)
            }
            assertFalse(gate.isAllowedUrl("https://" + "a".repeat(9000)))
        }

        @Test
        fun defaultIgnorableCharactersAreRejected() {
            val gate = gate()
            // Default-ignorable code points that are not format characters: they draw as nothing,
            // such as U+3164 HANGUL FILLER, or as part of the character before them.
            for (c in
                listOf(
                    '\u034f',
                    '\u115f',
                    '\u1160',
                    '\u17b4',
                    '\u17b5',
                    '\u180b',
                    '\u180c',
                    '\u180d',
                    '\u180f',
                    '\u2065',
                    '\u3164',
                    '\ufe00',
                    '\ufe0f',
                    '\uffa0',
                    '\ufff0',
                    '\ufff8',
                )) {
                assertFalse(gate.isAllowedUrl("https://a.com/$c"), "U+%04X".format(c.code))
            }
        }

        @Test
        fun everyDefaultIgnorableCodePointOfTheUcdIsRejected() {
            val gate = gate()
            val ranges = UnicodeDefaultIgnorables.RANGES
            var count = 0
            for (i in ranges.indices step 2) {
                for (code in ranges[i]..minOf(ranges[i + 1], 0xFFFF)) {
                    assertFalse(
                        gate.isAllowedUrl("https://a.com/${code.toChar()}"),
                        "U+%04X".format(code),
                    )
                    count++
                }
            }
            assertTrue(count > 0)
            // Outside the BMP, through surrogates.
            assertFalse(gate.isAllowedUrl("https://a.com/" + String(Character.toChars(0xE0100))))
        }

        @Test
        fun charactersNextToDefaultIgnorablesAreAllowed() {
            val gate = gate()
            // Letters just before and after ranges of the table, so that the lookup does not
            // overreach.
            for (c in listOf('\u115e', '\u1161', '\u3163', '\u3165', '\ufe10', '\uffa1')) {
                assertTrue(gate.isAllowedUrl("https://a.com/$c"), "U+%04X".format(c.code))
            }
        }

        @Test
        fun userinfoInHttpUrlsIsRejected() {
            val gate = gate()
            for (url in
                listOf(
                    "https://user@example.com/",
                    "http://user:password@example.com",
                    "https://good.example@evil.example",
                    "HTTPS://good.example@evil.example",
                    "https://good.example@evil.example?q",
                    "https://good.example@evil.example#f",
                    // Browsers read these as https://good.example@evil.example.
                    "https:good.example@evil.example",
                    "https:/good.example@evil.example",
                    "https:\\\\good.example@evil.example",
                    // Browsers treat a backslash in the authority as `/`, other parsers do not.
                    "https://evil.example\\@good.example",
                    "https://good.example\\@evil.example",
                    "https://good.example\\evil.example",
                )) {
                assertFalse(gate.isAllowedUrl(url), url)
            }
        }

        @Test
        fun atSignsOutsideTheAuthorityAreAllowed() {
            val gate = gate()
            for (url in
                listOf(
                    "https://medium.com/@user",
                    "https://example.com/a?to=a@example.com",
                    "https://example.com/#a@b",
                    "https://example.com:8080/a\\b",
                    "https://example.com/日本語?q=検索",
                    "http://example.com",
                )) {
                assertTrue(gate.isAllowedUrl(url), url)
            }
        }

        @Test
        fun cyrillicLetterInTheHostIsRejected() {
            val gate = gate()

            // U+0430 CYRILLIC SMALL LETTER A looks like the `a` of apple.com.
            val allowed = gate.isAllowedUrl("https://\u0430pple.com/")

            assertFalse(allowed)
        }

        @Test
        fun greekLetterInTheHostIsRejected() {
            val gate = gate()

            // U+03BF GREEK SMALL LETTER OMICRON looks like the `o` of google.com.
            val allowed = gate.isAllowedUrl("https://g\u03bfogle.com")

            assertFalse(allowed)
        }

        @Test
        fun fullwidthFullStopInTheHostIsRejected() {
            val gate = gate()

            // Browsers map U+FF0E FULLWIDTH FULL STOP to `.`, so the host is
            // good.example.evil.example.
            val allowed = gate.isAllowedUrl("https://good.example\uff0eevil.example")

            assertFalse(allowed)
        }

        @Test
        fun divisionSlashInTheHostIsRejected() {
            val gate = gate()

            // U+2215 DIVISION SLASH looks like `/`, but the host goes on to evil.example.
            val allowed = gate.isAllowedUrl("https://good.example\u2215path.evil.example")

            assertFalse(allowed)
        }

        @Test
        fun fullwidthSolidusInTheHostIsRejected() {
            val gate = gate()

            // U+FF0F FULLWIDTH SOLIDUS looks like `/` but does not end the authority.
            val allowed = gate.isAllowedUrl("https://good.example\uff0f.evil.example")

            assertFalse(allowed)
        }

        @Test
        fun nonAsciiAfterAPortIsRejected() {
            val gate = gate()

            val allowed = gate.isAllowedUrl("https://example.com:80\u0430")

            assertFalse(allowed)
        }

        @Test
        fun nonAsciiHostWithoutSlashesIsRejected() {
            val gate = gate()

            // Browsers read this as https://аpple.com.
            val allowed = gate.isAllowedUrl("https:\u0430pple.com")

            assertFalse(allowed)
        }

        @Test
        fun nonAsciiHostIsRejectedInAnyCaseOfTheScheme() {
            val gate = gate()

            val allowed = gate.isAllowedUrl("HTTP://\u0430pple.com")

            assertFalse(allowed)
        }

        @Test
        fun punycodeHostIsAllowed() {
            val gate = gate()

            // xn--pple-43d.com is the ASCII form of аpple.com, which shows where it goes.
            val allowed = gate.isAllowedUrl("https://xn--pple-43d.com/")

            assertTrue(allowed)
        }

        @Test
        fun nonAsciiAfterTheHostIsAllowed() {
            val gate = gate()

            val allowed = gate.isAllowedUrl("https://example.com/\u0430?q=\u0430#\u0430")

            assertTrue(allowed)
        }

        @Test
        fun nonAsciiRightAfterTheHostInTheQueryIsAllowed() {
            val gate = gate()

            val allowed = gate.isAllowedUrl("https://example.com?q=\u65e5\u672c")

            assertTrue(allowed)
        }

        @Test
        fun nonAsciiInOtherSchemesIsNotCheckedForAHost() {
            val gate = gate(GatePolicy(urlSchemes = setOf("mailto")))

            val allowed = gate.isAllowedUrl("mailto:\u0430@example.com")

            assertTrue(allowed)
        }

        @Test
        fun extraSchemesCanBeAllowed() {
            assertEquals(
                OpenUrlResult.Opened,
                gate(GatePolicy(urlSchemes = setOf("mailto"))).openUrl("mailto:a@b"),
            )
        }

        @Test
        fun schemesAreAllowedInAnyCase() {
            val policy = GatePolicy(urlSchemes = setOf("HTTPS", "Mailto"))
            assertEquals(setOf("https", "mailto"), policy.urlSchemes)
            assertEquals(OpenUrlResult.Opened, gate(policy).openUrl("https://a"))
            assertEquals(OpenUrlResult.Opened, gate(policy).openUrl("MAILTO:a@b"))
        }
    }

    /** Escaping bracketed paste with the pasted content. */
    @Nested
    inner class PasteEscape {
        @Test
        fun pasteCannotEndTheBracketEarly() {
            val writer = HostWriter { sent += String(it, Charsets.UTF_8) }
            writer.sendPaste("echo hi\u001b[201~; rm -rf ~\n", bracketed = true)
            assertEquals(listOf("\u001b[200~echo hi[201~; rm -rf ~\r\u001b[201~"), sent)
        }

        @Test
        fun pasteDropsC1Controls() {
            val writer = HostWriter { sent += String(it, Charsets.UTF_8) }
            // U+009B is CSI in 8-bit environments.
            writer.sendPaste("a\u009b201~b", bracketed = true)
            assertEquals(listOf("\u001b[200~a201~b\u001b[201~"), sent)
        }

        @Test
        fun unbracketedPasteIsSanitizedToo() {
            val writer = HostWriter { sent += String(it, Charsets.UTF_8) }
            writer.sendPaste("a\u0003\u001bb\r\n", bracketed = false)
            assertEquals(listOf("ab\r"), sent)
        }
    }

    /** Unintended leakage of the terminal content. */
    @Nested
    inner class ContentLeakage {
        @Test
        fun screenContentIsNeverReported() {
            val gate = gate()
            host(gate, "secret\u001b[1;1;1;1;1;6*y\u001b[?6n\u001b]10;?\u0007\u001b]4;1;?\u0007")
            // Only the cursor position, not the content, is reported.
            assertEquals(listOf("\u001b[?1;7R"), sent)
        }
    }

    /** Resource exhaustion through the gate. */
    @Nested
    inner class ResourceExhaustion {
        @Test
        fun repliesGrowLinearlyWithTheQueries() {
            val gate = gate()
            val queries = "\u001b[6n".repeat(10_000)
            host(gate, queries)
            val total = sent.sumOf { it.length }
            assertEquals(10_000, sent.size)
            assertTrue(total <= queries.length * 2, "replies: $total bytes")
        }
    }
}
