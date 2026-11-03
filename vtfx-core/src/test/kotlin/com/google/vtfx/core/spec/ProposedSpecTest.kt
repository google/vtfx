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

package com.google.vtfx.core.spec

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import java.io.File
import org.junit.ComparisonFailure
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Runs the proposed terminal emulation specs in `spec-proposals/core` (docs/design/testing.md
 * section 2).
 *
 * Runs with JUnit's `Parameterized` rather than `TestParameterInjector`, because there may be no
 * open proposals, and `TestParameterInjector` fails on an empty list of values.
 */
@RunWith(Parameterized::class)
class ProposedSpecTest(
    @Suppress("unused") private val id: String,
    private val case: VtSpecCase,
) {
    @Test
    fun spec() {
        case.error?.let { throw AssertionError("${case.id}: $it") }
        val terminal = Terminal(case.columns, case.rows)
        for (input in case.inputs) terminal.write(input)
        val snapshot = terminal.snapshot()

        case.screen?.let { expected ->
            val actual = List(snapshot.rows) { snapshot.line(it).text() }
            if (actual != expected) {
                throw ComparisonFailure(
                    "${case.id}: screen differs",
                    expected.joinToString("\n") { "|$it|" },
                    actual.joinToString("\n") { "|$it|" },
                )
            }
        }
        case.cursor?.let { expected ->
            val actual = Pair(snapshot.cursor.column + 1, snapshot.cursor.row + 1)
            if (actual != expected) {
                throw ComparisonFailure(
                    "${case.id}: cursor differs",
                    "${expected.first},${expected.second}",
                    "${actual.first},${actual.second}",
                )
            }
        }
    }

    companion object {
        /** The id names the test; the case is the second argument of the constructor. */
        @JvmStatic
        @Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = cases().map { arrayOf(it.id, it) }

        private fun cases(): List<VtSpecCase> {
            val root = System.getProperty("vtfx.specProposalsDir", "../spec-proposals")
            val directory = File(root, "core")
            if (!directory.isDirectory) return emptyList()
            val files =
                directory.listFiles { file -> file.name.endsWith(".vtspec") }?.sortedBy { it.name }
                    ?: return emptyList()
            val cases = files.flatMap { VtSpecParser.parse(it) }
            val duplicates = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
            check(duplicates.isEmpty()) { "Duplicate proposed case names: $duplicates" }
            return cases
        }
    }
}
