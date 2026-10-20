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

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** What [Boundaries.classViolations] finds in the compiled fixtures of fixtures/session. */
class ClassViolationsTest {
    private val boundaries =
        Boundaries.parse(
            """
            [modules.vtfx-gate]
            allowed_dependencies = []
            check_classes = true

            [modules.vtfx-session]
            allowed_dependencies = ["vtfx-gate"]
            check_classes = true

            [modules.vtfx-sample]
            allowed_dependencies = ["vtfx-session"]
            check_classes = false

            [[restricted_members]]
            classes = ["fixtures/gate/HostConnection"]
            reason = "Only the gate may use the connection."
            allowed_modules = ["vtfx-gate"]
            """
        )

    private val hierarchy = ClassHierarchy { name ->
        ClassViolationsTest::class.java.classLoader.getResourceAsStream("$name.class")?.use {
            it.readBytes()
        }
    }

    @Test
    fun classViolations_typeOnly_none() {
        assertEquals(emptyList(), violations("vtfx-session", "fixtures/session/HandsOver"))
    }

    @Test
    fun classViolations_callOfMethod_reportsIt() {
        val violations = violations("vtfx-session", "fixtures/session/CallsWrite")

        assertEquals(
            listOf(
                "fixtures/session/CallsWrite uses fixtures/gate/HostConnection.write([B)V: " +
                    "Only the gate may use the connection."
            ),
            violations,
        )
    }

    @Test
    fun classViolations_callThroughSubtype_reportsIt() {
        val violations = violations("vtfx-session", "fixtures/session/CallsWriteThroughSubtype")

        assertEquals(
            listOf(
                "fixtures/session/CallsWriteThroughSubtype uses " +
                    "fixtures/gate/BufferedConnection.write([B)V: Only the gate may use the connection."
            ),
            violations,
        )
    }

    @Test
    fun classViolations_readOfProperty_reportsIt() {
        val violations = violations("vtfx-session", "fixtures/session/ReadsProperty")

        assertEquals(
            listOf(
                "fixtures/session/ReadsProperty uses fixtures/gate/HostConnection.isOpen()Z: " +
                    "Only the gate may use the connection."
            ),
            violations,
        )
    }

    @Test
    fun classViolations_methodReference_reportsIt() {
        val violations = violations("vtfx-session", "fixtures/session/ReferencesWrite")

        // The compiler puts the code in a class of its own, whose name it chooses.
        assertEquals(
            listOf(
                "fixtures/gate/HostConnection.write([B)V: Only the gate may use the connection."
            ),
            violations.map { it.substringAfter(" uses ") },
        )
    }

    @Test
    fun classViolations_callInLambda_reportsIt() {
        val violations = violations("vtfx-session", "fixtures/session/CallsWriteInLambda")

        // The compiler puts the code in a class of its own, whose name it chooses.
        assertEquals(
            listOf(
                "fixtures/gate/HostConnection.write([B)V: Only the gate may use the connection."
            ),
            violations.map { it.substringAfter(" uses ") },
        )
    }

    @Test
    fun classViolations_allowedModule_none() {
        assertEquals(emptyList(), violations("vtfx-gate", "fixtures/gate/Gate"))
    }

    @Test
    fun classViolations_moduleWithoutClassCheck_none() {
        assertEquals(emptyList(), violations("vtfx-sample", "fixtures/session/CallsWrite"))
    }

    /** The violations of the class [className] and the classes nested in it, in [module]. */
    private fun violations(module: String, className: String): List<String> {
        val directory =
            File(checkNotNull(javaClass.classLoader.getResource("$className.class")).toURI())
                .parentFile
        val simpleName = className.substringAfterLast('/')
        val classes =
            checkNotNull(
                    directory.listFiles { file ->
                        file.name == "$simpleName.class" || file.name.startsWith("$simpleName$")
                    }
                )
                .map { ClassReferences.read(it.readBytes()) }
        return boundaries.classViolations(module, classes, hierarchy)
    }
}
