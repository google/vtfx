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
 * of the build, the modules it may depend on, and what its compiled classes may refer to.
 */
class Boundaries
private constructor(
    private val modules: Map<String, Module>,
    private val restrictedMembers: List<RestrictedMembers>,
) {
    private class Module(val allowedDependencies: Set<String>, val checkClasses: Boolean)

    /**
     * Classes whose fields and methods only [allowedModules] may use. Other modules may still name
     * the classes as types, for example to pass an instance on.
     */
    private class RestrictedMembers(
        val classes: Set<String>,
        val reason: String,
        val allowedModules: Set<String>,
    )

    /**
     * The violations of a build whose projects have [projectPaths]: every project must be declared,
     * and every declared module must be a project.
     */
    fun moduleViolations(projectPaths: Set<String>): List<String> {
        val projects = projectPaths.map { it.removePrefix(":") }.toSet()
        val undeclared =
            (projects - modules.keys).sorted().map {
                "project ':$it' is not declared in config/boundaries.toml"
            }
        val missing =
            (modules.keys - projects).sorted().map {
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
            modules[from.moduleName]?.allowedDependencies
                ?: return "$from is not declared in config/boundaries.toml"
        if (to.moduleName in allowed) return null
        return "'${from.moduleName}' must not depend on '${to.moduleName}'"
    }

    /**
     * The violations in [classes], the main classes of [module]: uses of the members of restricted
     * classes, directly or through a subtype, which [hierarchy] tells. None if the module's classes
     * are not checked (`check_classes = false`).
     */
    fun classViolations(
        module: String,
        classes: List<ClassReferences>,
        hierarchy: ClassHierarchy,
    ): List<String> {
        val declared =
            requireNotNull(modules[module]) {
                "module '$module' is not declared in config/boundaries.toml"
            }
        if (!declared.checkClasses) return emptyList()
        val rules = restrictedMembers.filter { module !in it.allowedModules }
        return classes.flatMap { references ->
            references.members
                .sortedBy { it.toString() }
                .flatMap { member ->
                    rules
                        .filter { rule ->
                            rule.classes.any { hierarchy.isSubtype(member.owner, it) }
                        }
                        .map { "${references.name} uses $member: ${it.reason}" }
                }
        }
    }

    companion object {
        /** Reads the text of `config/boundaries.toml`. Fails on anything it does not understand. */
        fun parse(text: String): Boundaries {
            val root = TomlMapper().readTree(text)
            root.requireKeys(TOP_LEVEL_KEYS, "the top level")
            val modulesNode = root.requireField("modules", "the top level")
            require(modulesNode.isObject) { "modules must be a table" }
            val modules =
                modulesNode.properties().associate { (name, module) ->
                    val where = "modules.$name"
                    require(module.isObject) { "$where must be a table" }
                    module.requireKeys(MODULE_KEYS, where)
                    name to
                        Module(
                            module
                                .requireField("allowed_dependencies", where)
                                .strings("$where.allowed_dependencies"),
                            module
                                .requireField("check_classes", where)
                                .boolean("$where.check_classes"),
                        )
                }
            for ((name, module) in modules) {
                module.allowedDependencies.requireModules(
                    modules,
                    "modules.$name.allowed_dependencies",
                )
            }
            val restrictedMembers =
                root.optionalArray("restricted_members").mapIndexed { index, rule ->
                    val where = "restricted_members[$index]"
                    require(rule.isObject) { "$where must be a table" }
                    rule.requireKeys(RESTRICTED_MEMBERS_KEYS, where)
                    RestrictedMembers(
                        rule.requireField("classes", where).strings("$where.classes"),
                        rule.requireField("reason", where).text("$where.reason"),
                        rule
                            .requireField("allowed_modules", where)
                            .strings("$where.allowed_modules")
                            .also { it.requireModules(modules, "$where.allowed_modules") },
                    )
                }
            return Boundaries(modules, restrictedMembers)
        }

        // restricted_apis is read by scripts/check_boundaries.py.
        private val TOP_LEVEL_KEYS = setOf("modules", "restricted_members", "restricted_apis")
        private val MODULE_KEYS = setOf("allowed_dependencies", "check_classes")
        private val RESTRICTED_MEMBERS_KEYS = setOf("classes", "reason", "allowed_modules")

        private fun JsonNode.requireKeys(known: Set<String>, where: String) {
            val unknown = fieldNames().asSequence().toSet() - known
            require(unknown.isEmpty()) { "unknown keys in $where: $unknown" }
        }

        private fun JsonNode.requireField(name: String, where: String): JsonNode =
            requireNotNull(get(name)) { "$where has no $name" }

        private fun JsonNode.optionalArray(name: String): List<JsonNode> {
            val node = get(name) ?: return emptyList()
            require(node.isArray) { "$name must be an array of tables" }
            return node.toList()
        }

        private fun JsonNode.strings(where: String): Set<String> {
            require(isArray) { "$where must be an array" }
            return map { it.text(where) }.toSet()
        }

        private fun JsonNode.text(where: String): String {
            require(isTextual) { "$where must hold strings only" }
            return textValue()
        }

        private fun JsonNode.boolean(where: String): Boolean {
            require(isBoolean) { "$where must be true or false" }
            return booleanValue()
        }

        private fun Set<String>.requireModules(modules: Map<String, Module>, where: String) {
            val unknown = this - modules.keys
            require(unknown.isEmpty()) { "$where names undeclared modules: $unknown" }
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
