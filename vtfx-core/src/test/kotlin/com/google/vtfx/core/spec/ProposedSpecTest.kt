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

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.Terminal
import java.io.File
import org.junit.ComparisonFailure
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the proposed terminal emulation specs in `spec-proposals/core` (docs/design/testing.md
 * section 2).
 */
@RunWith(TestParameterInjector::class)
class ProposedSpecTest {
    @Test
    fun spec(@TestParameter(valuesProvider = Cases::class) case: VtSpecCase) {
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

    private class Cases : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<*> =
            cases().map { value(it).withName(it.id) }
    }

    private companion object {
        fun cases(): List<VtSpecCase> {
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
