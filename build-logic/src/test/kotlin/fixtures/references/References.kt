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

package fixtures.references

class Element

class Argument

class Cast

class Literal

class Caught : Exception()

class Statics {
    companion object {
        @JvmField var counter: Int = 0
    }
}

annotation class Marker

/** Names each of the classes above in one place only, of a different kind. */
@Marker
class Uses {
    val elements: Array<Element> = emptyArray()

    val arguments: List<Argument> = emptyList()

    fun cast(value: Any): Any = value as Cast

    fun literal(): Class<*> = Literal::class.java

    fun caught(block: () -> Unit) {
        try {
            block()
        } catch (e: Caught) {
            Unit
        }
    }

    fun increment() {
        Statics.counter++
    }
}
