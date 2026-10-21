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

package com.google.vtfx.core

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs [block] on a new thread and returns its result, or fails if it takes longer than [limit]. A
 * thread cannot be stopped, so on a timeout it is interrupted and abandoned, as a daemon thread.
 *
 * A JUnit 4 timeout covers a whole test; this covers only [block], and [block] runs on a thread of
 * its own, so a test can measure the CPU time of that thread.
 */
fun <T> assertCompletesWithin(limit: Duration, block: () -> T): T {
    val task = FutureTask(block)
    val thread = Thread(task, "assertCompletesWithin").apply { isDaemon = true }
    thread.start()
    try {
        return task.get(limit.toNanos(), TimeUnit.NANOSECONDS)
    } catch (_: TimeoutException) {
        thread.interrupt()
        throw AssertionError("did not complete within $limit")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
