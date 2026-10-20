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
import kotlin.test.assertContains
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The edges that the boundary check reads from Gradle's model of a build, not from build files. */
class DependencyEdgesTest {
    @TempDir lateinit var directory: File

    private val root by lazy { ProjectBuilder.builder().withProjectDir(directory).build() }

    @Test
    fun declaredDependencyEdges_dependencyAddedByPlugin_includesIt() {
        val core = javaLibrary("vtfx-core")
        val gate = javaLibrary("vtfx-gate")
        gate.pluginManager.apply(DependsOnCore::class.java)

        val edges = gate.declaredDependencyEdges()

        assertContains(edges, DependencyEdge("implementation", node(gate), node(core)))
    }

    @Test
    fun declaredDependencyEdges_configurationNeitherResolvedNorExtended_includesIt() {
        val core = javaLibrary("vtfx-core")
        val gate = javaLibrary("vtfx-gate")
        gate.configurations.dependencyScope("unused")
        gate.dependencies.add("unused", core)

        val edges = gate.declaredDependencyEdges()

        assertContains(edges, DependencyEdge("unused", node(gate), node(core)))
    }

    @Test
    fun resolvedDependencyEdges_dependencyAddedOnResolution_includesIt() {
        val core = javaLibrary("vtfx-core")
        val gate = javaLibrary("vtfx-gate")
        gate.configurations.getByName("implementation").withDependencies {
            add(gate.dependencies.create(core))
        }

        val edges = resolvedEdges(gate, "compileClasspath")

        assertContains(edges, DependencyEdge("compileClasspath", node(gate), node(core)))
    }

    @Test
    fun resolvedDependencyEdges_substitutedModule_includesProject() {
        val core = javaLibrary("vtfx-core")
        val gate = javaLibrary("vtfx-gate")
        gate.dependencies.add("implementation", "com.example:library:1.0")
        gate.configurations
            .getByName("compileClasspath")
            .resolutionStrategy
            .dependencySubstitution {
                substitute(module("com.example:library")).using(project(":vtfx-core"))
            }

        val edges = resolvedEdges(gate, "compileClasspath")

        assertContains(edges, DependencyEdge("compileClasspath", node(gate), node(core)))
    }

    @Test
    fun resolvedDependencyEdges_transitiveProjects_includesEdgeBetweenThem() {
        val core = javaLibrary("vtfx-core")
        val input = javaLibrary("vtfx-input")
        val session = javaLibrary("vtfx-session")
        input.dependencies.add("api", core)
        session.dependencies.add("implementation", input)

        val edges = resolvedEdges(session, "compileClasspath")

        assertContains(edges, DependencyEdge("compileClasspath", node(input), node(core)))
    }

    private fun javaLibrary(name: String): Project =
        ProjectBuilder.builder().withName(name).withParent(root).build().also {
            it.pluginManager.apply("java-library")
        }

    private fun resolvedEdges(project: Project, configuration: String): List<DependencyEdge> =
        resolvedDependencyEdges(
            configuration,
            project.configurations.getByName(configuration).incoming.resolutionResult.root,
        )

    private fun node(project: Project) = DependencyNode.Project(project.path)

    /** Stands for a convention plugin that adds a dependency the build file does not mention. */
    class DependsOnCore : Plugin<Project> {
        override fun apply(target: Project) {
            target.dependencies.add("implementation", target.project(":vtfx-core"))
        }
    }
}
