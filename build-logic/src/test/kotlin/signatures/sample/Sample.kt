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

// Declarations that KotlinSignaturesTest dumps: one of each kind of change that the ABI dump does
// not show.
package signatures.sample

/** Stands for InternalVtfxApi: what it marks is left out. */
annotation class Internal

sealed interface Shape

fun interface Callback {
    fun call(value: String?)
}

data class Point(val x: Int, val y: Int = 0)

class Box<T : CharSequence>(val items: List<T>, var label: String? = null) {
    val size: Int
        get() = items.size

    var count: Int = 0
        private set

    fun <R> map(transform: (T) -> R, limit: Int = 10): List<R> = items.take(limit).map(transform)

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun map(transform: (T) -> String): List<String> = items.map(transform)

    @Deprecated("Use map") fun old(): Unit = Unit

    @Internal fun shared(): Unit = Unit

    @Internal val sharedValue: Int = 0

    internal fun notPublic(): Unit = Unit
}

@Internal
class Excluded {
    class Nested
}

object Limits {
    const val MAX: Int = 1
    val max: Int = 2
}

enum class Mode {
    B,
    A,
}

fun count(vararg names: String): Int = names.size
