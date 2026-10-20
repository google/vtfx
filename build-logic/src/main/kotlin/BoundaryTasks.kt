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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails if the projects of the build differ from the modules of `config/boundaries.toml`. */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckModulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val boundaries: RegularFileProperty

    /** The paths of all projects of the build but the root project. */
    @get:Input abstract val projectPaths: SetProperty<String>

    @TaskAction
    fun run() {
        val violations =
            Boundaries.parse(boundaries.get().asFile.readText())
                .moduleViolations(projectPaths.get())
        failOn(violations)
    }
}

/**
 * Fails if a project depends on a module that `config/boundaries.toml` does not allow, in any of
 * its configurations.
 */
@DisableCachingByDefault(because = "A check with no outputs")
abstract class CheckDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val boundaries: RegularFileProperty

    /**
     * The declared and the resolved edges of the project ([declaredDependencyEdges],
     * [resolvedDependencyEdges]).
     */
    @get:Input abstract val edges: ListProperty<DependencyEdge>

    @TaskAction
    fun run() {
        val violations =
            Boundaries.parse(boundaries.get().asFile.readText()).dependencyViolations(edges.get())
        failOn(violations)
    }
}

private fun failOn(violations: List<String>) {
    val distinct = violations.distinct()
    if (distinct.isEmpty()) return
    throw GradleException(
        (distinct + "${distinct.size} boundary violation(s). See config/boundaries.toml.")
            .joinToString("\n")
    )
}

/** The project dependencies declared in every configuration of the project. */
fun Project.declaredDependencyEdges(): List<DependencyEdge> =
    configurations.flatMap { configuration ->
        configuration.dependencies.withType(ProjectDependency::class.java).map {
            DependencyEdge(
                configuration.name,
                DependencyNode.Project(path),
                DependencyNode.Project(it.path),
            )
        }
    }

/**
 * The edges to projects in the dependency graph resolved for [configuration], whose root is [root]:
 * from the root and from every component it reaches. A dependency that did not resolve still counts
 * as an edge to the project it requested.
 */
fun resolvedDependencyEdges(
    configuration: String,
    root: ResolvedComponentResult,
): List<DependencyEdge> {
    val edges = mutableListOf<DependencyEdge>()
    val visited = mutableSetOf(root.id)
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        val from = nodeOf(component.id)
        for (dependency in component.dependencies) {
            val to =
                when (dependency) {
                    is ResolvedDependencyResult -> {
                        val selected = dependency.selected
                        if (visited.add(selected.id)) pending.addLast(selected)
                        nodeOf(selected.id)
                    }
                    is UnresolvedDependencyResult ->
                        when (val requested = dependency.requested) {
                            is ProjectComponentSelector ->
                                projectNode(requested.buildPath, requested.projectPath)
                            else -> continue
                        }
                    else -> error("Unknown kind of dependency result: $dependency")
                }
            if (to !is DependencyNode.External) edges += DependencyEdge(configuration, from, to)
        }
    }
    return edges
}

private fun nodeOf(id: ComponentIdentifier): DependencyNode =
    when (id) {
        is ProjectComponentIdentifier -> projectNode(id.build.buildPath, id.projectPath)
        is ModuleComponentIdentifier -> DependencyNode.External("${id.group}:${id.module}")
        else -> DependencyNode.External(id.displayName)
    }

private fun projectNode(buildPath: String, projectPath: String): DependencyNode =
    if (buildPath == ":") DependencyNode.Project(projectPath)
    else DependencyNode.OtherBuild(buildPath, projectPath)
