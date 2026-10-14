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

package com.google.vtfx.testing

import org.junit.jupiter.api.DynamicTest
import org.opentest4j.AssertionFailedError

/**
 * The spec cases that are known not to pass yet (docs/design/testing.md section 6).
 *
 * The list is the resource `spec-expected-failures.txt` of a module's tests: one id per line, with
 * `#` comments. A listed case must fail, so that the list is kept accurate as cases start passing.
 */
public class ExpectedFailures private constructor(private val ids: Set<String>) {
    /**
     * Returns a test that runs [body] and requires it to throw an [AssertionError] if [id] is
     * listed, or to complete normally otherwise.
     */
    public fun test(id: String, body: () -> Unit): DynamicTest =
        DynamicTest.dynamicTest(id) {
            if (id !in ids) {
                body()
                return@dynamicTest
            }
            val failed =
                try {
                    body()
                    false
                } catch (_: AssertionError) {
                    true
                }
            if (!failed) {
                throw AssertionFailedError("$id passes; remove it from spec-expected-failures.txt")
            }
        }

    /** Returns a test that requires every listed id to be one of [allIds]. */
    public fun staleEntriesTest(allIds: Collection<String>): DynamicTest =
        DynamicTest.dynamicTest("spec-expected-failures.txt lists only existing cases") {
            val stale = ids - allIds.toSet()
            if (stale.isNotEmpty()) {
                throw AssertionFailedError(
                    "Unknown cases in spec-expected-failures.txt: ${stale.sorted()}"
                )
            }
        }

    public companion object {
        /** Loads the list from the resources of [anchor]'s class loader. */
        public fun load(anchor: Class<*>): ExpectedFailures {
            val text = anchor.getResource("/spec-expected-failures.txt")?.readText().orEmpty()
            val ids =
                text
                    .lines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            return ExpectedFailures(ids)
        }
    }
}
