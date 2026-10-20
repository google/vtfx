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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import java.io.Serializable

/**
 * The module boundaries declared in `config/boundaries.toml` (docs/decisions/0044): for each module
 * of the build, the modules it may depend on.
 */
class Boundaries private constructor(private val allowedDependencies: Map<String, Set<String>>) {
    /**
     * The violations of a build whose projects have [projectPaths]: every project must be declared,
     * and every declared module must be a project.
     */
    fun moduleViolations(projectPaths: Set<String>): List<String> {
        val projects = projectPaths.map { it.removePrefix(":") }.toSet()
        val undeclared =
            (projects - allowedDependencies.keys).sorted().map {
                "project ':$it' is not declared in config/boundaries.toml"
            }
        val missing =
            (allowedDependencies.keys - projects).sorted().map {
                "module '$it' of config/boundaries.toml is not a project of the build"
            }
        return undeclared + missing
    }

    /**
     * The violations among [edges]: an edge to a project of this build must come from a declared
     * module that may depend on it, or from the project itself (such as the tests of a module
     * depending on its own test fixtures). An edge from a module of a repository to a project, or
     * to a project of another build, is a violation however it came about (for example a dependency
     * substitution), since it puts code on the class path that the declared edges do not show.
     */
    fun dependencyViolations(edges: List<DependencyEdge>): List<String> = edges.mapNotNull { edge ->
        violationOf(edge)?.let { "${edge.configuration}: $it" }
    }

    private fun violationOf(edge: DependencyEdge): String? {
        val to =
            when (val node = edge.to) {
                is DependencyNode.Project -> node
                is DependencyNode.OtherBuild ->
                    return "${edge.from} depends on $node, which is not part of this build"
                is DependencyNode.External -> return null
            }
        val from =
            when (val node = edge.from) {
                is DependencyNode.Project -> node
                is DependencyNode.OtherBuild,
                is DependencyNode.External -> return "$node resolves to $to"
            }
        if (from == to) return null
        val allowed =
            allowedDependencies[from.moduleName]
                ?: return "$from is not declared in config/boundaries.toml"
        if (to.moduleName in allowed) return null
        return "'${from.moduleName}' must not depend on '${to.moduleName}'"
    }

    companion object {
        /** Reads the text of `config/boundaries.toml`. Fails on anything it does not understand. */
        fun parse(text: String): Boundaries {
            val root = TomlMapper().readTree(text)
            val unknownTables = root.fieldNames().asSequence().toSet() - TOP_LEVEL_KEYS
            require(unknownTables.isEmpty()) { "unknown keys at the top level: $unknownTables" }
            val modules = root.requireField("modules", "the top level")
            require(modules.isObject) { "modules must be a table" }
            val allowedDependencies =
                modules.properties().associate { (name, module) ->
                    require(module.isObject) { "modules.$name must be a table" }
                    val unknown = module.fieldNames().asSequence().toSet() - MODULE_KEYS
                    require(unknown.isEmpty()) { "unknown keys in modules.$name: $unknown" }
                    name to
                        module
                            .requireField("allowed_dependencies", "modules.$name")
                            .strings("modules.$name.allowed_dependencies")
                }
            for ((name, allowed) in allowedDependencies) {
                val unknown = allowed - allowedDependencies.keys
                require(unknown.isEmpty()) {
                    "modules.$name.allowed_dependencies names undeclared modules: $unknown"
                }
            }
            return Boundaries(allowedDependencies)
        }

        // restricted_apis is read by scripts/check_boundaries.py.
        private val TOP_LEVEL_KEYS = setOf("modules", "restricted_apis")
        private val MODULE_KEYS = setOf("allowed_dependencies")

        private fun JsonNode.requireField(name: String, where: String): JsonNode =
            requireNotNull(get(name)) { "$where has no $name" }

        private fun JsonNode.strings(where: String): Set<String> {
            require(isArray) { "$where must be an array" }
            return map {
                require(it.isTextual) { "$where must hold strings only" }
                it.textValue()
            }
                .toSet()
        }
    }
}

/** A component of a dependency graph, as the boundary check sees it. */
sealed interface DependencyNode : Serializable {
    /** A project of this build, by its path (e.g. `:vtfx-core`). */
    data class Project(val path: String) : DependencyNode {
        /** The name of the module in `config/boundaries.toml`. */
        val moduleName: String
            get() = path.removePrefix(":")

        override fun toString(): String = "project '$path'"
    }

    /** A project of another build, such as an included build. */
    data class OtherBuild(val buildPath: String, val projectPath: String) : DependencyNode {
        override fun toString(): String = "project '$projectPath' of build '$buildPath'"
    }

    /** Anything that is not a project: a module of a repository, or files. */
    data class External(val name: String) : DependencyNode {
        override fun toString(): String = "'$name'"
    }
}

/** A dependency from [from] on [to], declared in or resolved for [configuration]. */
data class DependencyEdge(
    val configuration: String,
    val from: DependencyNode,
    val to: DependencyNode,
) : Serializable
