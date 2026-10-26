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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import signatures.sample.Point

class KotlinSignaturesTest {
    private val result: KotlinSignatures.Result = run {
        val root = Path.of(Point::class.java.protectionDomain.codeSource.location.toURI())
        val classes =
            Files.walk(root.resolve("signatures/sample")).use { paths ->
                paths.filter { it.extension == "class" }.map { it.readBytes() }.toList()
            }
        KotlinSignatures.dump(classes, setOf("signatures.sample.Internal"))
    }

    @Test
    fun rendersWhatTheAbiDumpLeavesOut() {
        assertEquals(EXPECTED, result.text)
    }

    @Test
    fun reportsPublicConstants() {
        assertEquals(listOf("signatures.sample.Limits.MAX"), result.publicConstants)
    }

    private companion object {
        val EXPECTED =
            """
            public final class signatures.sample.Box<T : kotlin.CharSequence> {
                @Deprecated(HIDDEN) public final fun map(transform: kotlin.Function1<T, kotlin.String>): kotlin.collections.List<kotlin.String>
                @Deprecated(WARNING) public final fun old(): kotlin.Unit
                public constructor(items: kotlin.collections.List<T>, label: kotlin.String? = ...)
                public final fun <R> map(transform: kotlin.Function1<T, R>, limit: kotlin.Int = ...): kotlin.collections.List<R>
                public final val items: kotlin.collections.List<T>
                public final val size: kotlin.Int
                public final var count: kotlin.Int { private set }
                public final var label: kotlin.String?
            }

            public fun interface signatures.sample.Callback {
                public abstract fun call(value: kotlin.String?): kotlin.Unit
            }

            public annotation class signatures.sample.Internal : kotlin.Annotation {
                public constructor()
            }

            public object signatures.sample.Limits {
                public final const val MAX: kotlin.Int
                public final val max: kotlin.Int
            }

            public enum class signatures.sample.Mode : kotlin.Enum<signatures.sample.Mode> {
                entry B
                entry A
            }

            public final data class signatures.sample.Point {
                public constructor(x: kotlin.Int, y: kotlin.Int = ...)
                public final fun copy(x: kotlin.Int = ..., y: kotlin.Int = ...): signatures.sample.Point
                public final operator fun component1(): kotlin.Int
                public final operator fun component2(): kotlin.Int
                public final val x: kotlin.Int
                public final val y: kotlin.Int
                public open fun hashCode(): kotlin.Int
                public open fun toString(): kotlin.String
                public open operator fun equals(other: kotlin.Any?): kotlin.Boolean
            }

            file signatures.sample.SampleKt {
                @Deprecated(WARNING) public typealias OldNames = kotlin.collections.List<kotlin.String>
                public final fun count(vararg names: kotlin.String): kotlin.Int
                public typealias Names = kotlin.collections.List<kotlin.String>
            }

            public sealed interface signatures.sample.Shape {
            }
            """
                .trimIndent() + "\n\n"
    }
}
