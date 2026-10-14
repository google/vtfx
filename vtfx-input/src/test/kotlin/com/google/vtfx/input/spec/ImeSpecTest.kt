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

package com.google.vtfx.input.spec

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.TerminalKey
import com.google.vtfx.input.HostInput
import com.google.vtfx.input.ImeEditor
import com.google.vtfx.testing.ExpectedFailures
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.opentest4j.AssertionFailedError

/**
 * Replays the IME traces listed in spec/ime into [ImeEditor] and compares the bytes for the host
 * with the human-owned expectations (docs/design/testing.md section 4).
 *
 * Only the top-level `InputConnection` calls of a trace are replayed. Nested calls are made by
 * `BaseInputConnection` itself, and the other events are the recorder's own actions.
 */
class ImeSpecTest {
    private val repoRoot =
        File(System.getProperty("vtfx.repoRoot") ?: error("vtfx.repoRoot is not set"))

    @TestFactory
    fun spec(): List<DynamicTest> {
        val directory = File(repoRoot, "spec/ime")
        val files =
            directory.listFiles { file -> file.name.endsWith(".toml") }?.sortedBy { it.name }
                ?: error("No spec directory: $directory")
        val expectedFailures = ExpectedFailures.load(ImeSpecTest::class.java)
        val ids = mutableListOf<String>()
        val tests = files.flatMap { file ->
            MiniToml.parseArrayOfTables(file.readText(), "case").map { case ->
                val id = "${file.name}:${case["name"] ?: error("${file.name}: a case has no name")}"
                ids += id
                expectedFailures.test(id) { run(id, case) }
            }
        }
        return tests + expectedFailures.staleEntriesTest(ids)
    }

    private fun run(id: String, case: Map<String, String>) {
        val trace = File(repoRoot, case["trace"] ?: throw AssertionFailedError("$id: no trace"))
        val expected = case["expected"] ?: throw AssertionFailedError("$id: no expected")
        val actual = replay(trace)
        if (!actual.contentEquals(expected.toByteArray(Charsets.UTF_8))) {
            throw AssertionFailedError(
                "$id: bytes differ",
                escape(expected.toByteArray()),
                escape(actual),
            )
        }
    }

    private fun replay(trace: File): ByteArray {
        val out = ByteArrayOutputStream()
        val editor =
            ImeEditor(
                object : ImeEditor.Listener {
                    override fun send(input: HostInput) {
                        out.write(encode(input))
                    }

                    override fun restartInput() {}
                }
            )
        for (line in trace.readLines()) {
            if (line.isBlank()) continue
            @Suppress("UNCHECKED_CAST") val event = MiniJson.parse(line) as Map<String, Any?>
            if (event["ev"] != "ic" || (event["depth"] as Long) != 0L) continue
            @Suppress("UNCHECKED_CAST") val args = event["args"] as Map<String, Any?>
            fun int(name: String) = (args[name] as Long).toInt()
            fun string(name: String) = args[name] as String
            when (val call = event["call"]) {
                "beginBatchEdit" -> editor.beginBatchEdit()
                "endBatchEdit" -> editor.endBatchEdit()
                "setComposingText" -> editor.setComposingText(string("text"))
                "commitText" -> editor.commitText(string("text"))
                "finishComposingText" -> editor.finishComposingText()
                "setComposingRegion" -> editor.setComposingRegion(int("start"), int("end"))
                "deleteSurroundingText" ->
                    editor.deleteSurroundingText(int("beforeLength"), int("afterLength"))
                "deleteSurroundingTextInCodePoints" ->
                    editor.deleteSurroundingTextInCodePoints(
                        int("beforeLength"),
                        int("afterLength"),
                    )
                "replaceText" -> editor.replaceText(int("start"), int("end"), string("text"))
                "setSelection" -> editor.setSelection(int("start"), int("end"))
                "performEditorAction" -> editor.performEditorAction()
                "closeConnection" -> editor.closeConnection()
                "sendKeyEvent" ->
                    if (int("action") == ACTION_DOWN) {
                        editor.onKeyDown(keyStroke(string("keyCode"), int("meta")))
                    }
                // Queries and calls without effects on the host.
                "getSurroundingText",
                "getTextBeforeCursor",
                "getTextAfterCursor",
                "getSelectedText",
                "getExtractedText",
                "getCursorCapsMode",
                "requestCursorUpdates",
                "requestCursorUpdates+filter",
                "commitCorrection",
                "clearMetaKeyStates" -> {}
                else -> throw AssertionFailedError("${trace.name}: unsupported call $call")
            }
        }
        return out.toByteArray()
    }

    private fun encode(input: HostInput): ByteArray =
        when (input) {
            // As vtfx-gate sends them (ime-contract.md section 4.2).
            is HostInput.Text -> input.text.toByteArray(Charsets.UTF_8)
            is HostInput.Delete -> ByteArray(input.count) { 0x7f }
            is HostInput.Key -> encode(input.stroke)
            is HostInput.Paste -> input.text.toByteArray(Charsets.UTF_8)
        }

    /**
     * Returns the bytes that vtfx-gate's key encoder sends for [stroke] in the default modes, for
     * the keys that the traces press. vtfx-input cannot depend on vtfx-gate (decision 0005), and
     * the encoder has its own tests there.
     */
    private fun encode(stroke: KeyStroke): ByteArray {
        if (!stroke.modifiers.isEmpty) throw AssertionFailedError("unsupported key $stroke")
        val text =
            when (stroke) {
                is KeyStroke.Character -> String(Character.toChars(stroke.codePoint))
                is KeyStroke.Special ->
                    when (stroke.key) {
                        TerminalKey.Enter -> "\r"
                        TerminalKey.Backspace -> "\u007f"
                        TerminalKey.Tab -> "\t"
                        TerminalKey.Escape -> "\u001b"
                        TerminalKey.Up -> "\u001b[A"
                        TerminalKey.Down -> "\u001b[B"
                        TerminalKey.Right -> "\u001b[C"
                        TerminalKey.Left -> "\u001b[D"
                        TerminalKey.Delete -> "\u001b[3~"
                        else -> throw AssertionFailedError("unsupported key $stroke")
                    }
            }
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * Maps an Android key code to a key stroke, as the US keyboard layout of the emulator does. A
     * stand-in for `KeyEvent.getUnicodeChar`, which the Android adapter uses.
     */
    private fun keyStroke(keyCode: String, meta: Int): KeyStroke {
        val shift = meta and META_SHIFT_ON != 0
        val name = keyCode.removePrefix("KEYCODE_")
        if (name.length == 1 && name[0] in 'A'..'Z') {
            return KeyStroke.Character(if (shift) name[0].code else name[0].lowercaseChar().code)
        }
        if (name.length == 1 && name[0] in '0'..'9') return KeyStroke.Character(name[0].code)
        val special =
            when (name) {
                "ENTER",
                "NUMPAD_ENTER" -> TerminalKey.Enter
                "DEL" -> TerminalKey.Backspace
                "FORWARD_DEL" -> TerminalKey.Delete
                "DPAD_LEFT" -> TerminalKey.Left
                "DPAD_RIGHT" -> TerminalKey.Right
                "DPAD_UP" -> TerminalKey.Up
                "DPAD_DOWN" -> TerminalKey.Down
                "TAB" -> TerminalKey.Tab
                "ESCAPE" -> TerminalKey.Escape
                else -> null
            }
        if (special != null) return KeyStroke.Special(special, KeyModifiers.of(shift = shift))
        val character =
            when (name) {
                "SPACE" -> ' '
                "PERIOD" -> '.'
                "COMMA" -> ','
                "MINUS" -> '-'
                "APOSTROPHE" -> '\''
                "SLASH" -> if (shift) '?' else '/'
                else -> throw AssertionFailedError("unsupported key code $keyCode")
            }
        return KeyStroke.Character(character.code)
    }

    private fun escape(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8)
            .replace("\u001b", "\\e")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u007f", "\\x7f")

    private companion object {
        const val ACTION_DOWN = 0
        const val META_SHIFT_ON = 1
    }
}
