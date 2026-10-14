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

package com.google.vtfx.session

/**
 * Tells [listener] the latest value of a property that several threads may change, without a lock
 * held, so that the last value it is told is the current one.
 *
 * The thread that changed the value tells it, unless another thread is telling this listener at the
 * time: that thread then tells the latest value after it returns. Values recorded in the meantime
 * are told as one, the latest. So a listener that changes the value again is told again after it
 * returns, not inside its own call.
 */
internal class LatestValueNotifier<T : Any>(private val listener: (T) -> Unit) {
    private val lock = Any()

    /** The value not yet told, if any. Guarded by [lock]. */
    private var pending: T? = null

    /** Whether a thread is inside [listener]. Guarded by [lock]. */
    private var telling = false

    /**
     * Records [value] as the latest. Call it with the lock that orders the changes held, so that
     * the values are recorded in the order of the changes.
     */
    fun record(value: T) {
        synchronized(lock) { pending = value }
    }

    /** Tells the recorded value, unless another thread is telling one, which then tells it. */
    fun tell() {
        while (true) {
            val value =
                synchronized(lock) {
                    if (telling) return
                    val value = pending ?: return
                    pending = null
                    telling = true
                    value
                }
            try {
                listener(value)
            } finally {
                synchronized(lock) { telling = false }
            }
        }
    }
}
