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

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class BoundariesTest {
    private val boundaries =
        Boundaries.parse(
            """
            [modules.vtfx-core]
            allowed_dependencies = []

            [modules.vtfx-gate]
            allowed_dependencies = ["vtfx-core"]
            """
        )

    @Test
    fun moduleViolations_declaredProjects_none() {
        assertEquals(
            emptyList<String>(),
            boundaries.moduleViolations(setOf(":vtfx-core", ":vtfx-gate")),
        )
    }

    @Test
    fun moduleViolations_undeclaredProject_reportsIt() {
        val violations =
            boundaries.moduleViolations(setOf(":vtfx-core", ":vtfx-gate", ":vtfx-extra"))

        assertEquals(
            listOf("project ':vtfx-extra' is not declared in config/boundaries.toml"),
            violations,
        )
    }

    @Test
    fun moduleViolations_declaredModuleWithoutProject_reportsIt() {
        val violations = boundaries.moduleViolations(setOf(":vtfx-core"))

        assertEquals(
            listOf("module 'vtfx-gate' of config/boundaries.toml is not a project of the build"),
            violations,
        )
    }

    @Test
    fun moduleViolations_nestedProject_reportsIt() {
        val violations =
            boundaries.moduleViolations(setOf(":vtfx-core", ":vtfx-gate", ":vtfx-gate:x"))

        assertEquals(
            listOf("project ':vtfx-gate:x' is not declared in config/boundaries.toml"),
            violations,
        )
    }

    @Test
    fun dependencyViolations_allowedEdge_none() {
        val edge = DependencyEdge("implementation", project(":vtfx-gate"), project(":vtfx-core"))

        assertEquals(emptyList<String>(), boundaries.dependencyViolations(listOf(edge)))
    }

    @Test
    fun dependencyViolations_edgeNotAllowed_reportsIt() {
        val edge = DependencyEdge("implementation", project(":vtfx-core"), project(":vtfx-gate"))

        val violations = boundaries.dependencyViolations(listOf(edge))

        assertEquals(
            listOf("implementation: 'vtfx-core' must not depend on 'vtfx-gate'"),
            violations,
        )
    }

    @Test
    fun dependencyViolations_edgeToItself_none() {
        val edge =
            DependencyEdge("testCompileClasspath", project(":vtfx-core"), project(":vtfx-core"))

        assertEquals(emptyList<String>(), boundaries.dependencyViolations(listOf(edge)))
    }

    @Test
    fun dependencyViolations_externalModuleResolvedToProject_reportsIt() {
        val edge =
            DependencyEdge(
                "runtimeClasspath",
                DependencyNode.External("com.example:library"),
                project(":vtfx-gate"),
            )

        val violations = boundaries.dependencyViolations(listOf(edge))

        assertEquals(
            listOf("runtimeClasspath: 'com.example:library' resolves to project ':vtfx-gate'"),
            violations,
        )
    }

    @Test
    fun dependencyViolations_projectOfOtherBuild_reportsIt() {
        val edge =
            DependencyEdge(
                "compileClasspath",
                project(":vtfx-core"),
                DependencyNode.OtherBuild(":other", ":vtfx-gate"),
            )

        val violations = boundaries.dependencyViolations(listOf(edge))

        assertEquals(
            listOf(
                "compileClasspath: project ':vtfx-core' depends on project ':vtfx-gate' of build " +
                    "':other', which is not part of this build"
            ),
            violations,
        )
    }

    @Test
    fun dependencyViolations_fromUndeclaredProject_reportsIt() {
        val edge = DependencyEdge("implementation", project(":vtfx-extra"), project(":vtfx-core"))

        val violations = boundaries.dependencyViolations(listOf(edge))

        assertEquals(
            listOf(
                "implementation: project ':vtfx-extra' is not declared in config/boundaries.toml"
            ),
            violations,
        )
    }

    @Test
    fun dependencyViolations_edgeToExternalModule_none() {
        val edge =
            DependencyEdge("implementation", project(":vtfx-core"), DependencyNode.External("a:b"))

        assertEquals(emptyList<String>(), boundaries.dependencyViolations(listOf(edge)))
    }

    @Test
    fun parse_allowedDependencyNotDeclared_throws() {
        assertFailsWith<IllegalArgumentException> {
            Boundaries.parse(
                """
                [modules.vtfx-gate]
                allowed_dependencies = ["vtfx-core"]
                """
            )
        }
    }

    @Test
    fun parse_unknownKeyOfModule_throws() {
        assertFailsWith<IllegalArgumentException> {
            Boundaries.parse(
                """
                [modules.vtfx-core]
                allowed_dependencies = []
                allowed_test_dependencies = []
                """
            )
        }
    }

    @Test
    fun parse_unknownTable_throws() {
        assertFailsWith<IllegalArgumentException> {
            Boundaries.parse(
                """
                [modules.vtfx-core]
                allowed_dependencies = []

                [exceptions]
                paths = []
                """
            )
        }
    }

    @Test
    fun parse_moduleWithoutAllowedDependencies_throws() {
        assertFailsWith<IllegalArgumentException> { Boundaries.parse("[modules.vtfx-core]\n") }
    }

    private fun project(path: String) = DependencyNode.Project(path)
}
