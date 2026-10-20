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

import kotlin.test.assertContains
import org.junit.jupiter.api.Test

class ClassReferencesTest {
    private val uses = ClassReferences.read(classFile("fixtures/references/Uses"))

    @Test
    fun types_arrayInDescriptor_includesElementType() {
        assertContains(uses.types, "fixtures/references/Element")
    }

    @Test
    fun types_typeArgumentInSignature_includesIt() {
        assertContains(uses.types, "fixtures/references/Argument")
    }

    @Test
    fun types_cast_includesIt() {
        assertContains(uses.types, "fixtures/references/Cast")
    }

    @Test
    fun types_classLiteral_includesIt() {
        assertContains(uses.types, "fixtures/references/Literal")
    }

    @Test
    fun types_caughtException_includesIt() {
        assertContains(uses.types, "fixtures/references/Caught")
    }

    @Test
    fun types_annotation_includesIt() {
        assertContains(uses.types, "fixtures/references/Marker")
    }

    @Test
    fun members_staticField_includesIt() {
        assertContains(
            uses.members,
            MemberReference(
                "fixtures/references/Statics",
                "counter",
                "I",
                MemberReference.Kind.FIELD,
            ),
        )
    }
}

/** The bytes of the compiled test class with the internal name [name]. */
fun classFile(name: String): ByteArray =
    checkNotNull(ClassReferencesTest::class.java.classLoader.getResourceAsStream("$name.class")) {
            "no class $name"
        }
        .use { it.readBytes() }
